#include "usb_stream_engine.h"
#include <sys/ioctl.h>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>
#include <cstring>
#include <algorithm>

#define LOG_TAG "UsbStreamEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace echo::music::usbaudio {

UsbStreamEngine::UsbStreamEngine() = default;

UsbStreamEngine::~UsbStreamEngine() {
    stopStream();
}

uint32_t UsbStreamEngine::calculateNominalPacketSamples(uint32_t sampleRate) {
    return sampleRate / MICROFRAMES_PER_SEC;
}

int UsbStreamEngine::startStream(int fd, int dataEp, int syncEp, uint32_t sampleRate, uint32_t bitDepth, uint32_t channels) {
    if (streaming_.load(std::memory_order_relaxed)) {
        stopStream();
    }

    fd_ = fd;
    data_ep_ = dataEp;
    sync_ep_ = syncEp;
    sample_rate_ = sampleRate;
    bit_depth_ = bitDepth;
    channels_ = channels;

    bytes_per_sample_ = (bitDepth + 7) / 8;
    bytes_per_frame_ = bytes_per_sample_ * channels_;

    // Pacing fraction in 16.16 fixed point: (sample_rate / 8000) << 16
    fractional_step_ = static_cast<uint32_t>((static_cast<uint64_t>(sampleRate) << 16) / MICROFRAMES_PER_SEC);
    fractional_accum_ = 0;
    feedback_rate_q16_ = fractional_step_;

    ring_buffer_.flush();

    streaming_.store(true, std::memory_order_release);
    stream_thread_ = std::thread(&UsbStreamEngine::streamLoop, this);

    LOGI("Started stream: SR=%u, bitDepth=%u, channels=%u, dataEp=0x%02x, syncEp=0x%02x",
         sampleRate, bitDepth, channels, dataEp, syncEp);
    return 0;
}

int UsbStreamEngine::stopStream() {
    if (!streaming_.exchange(false, std::memory_order_acq_rel)) {
        return 0;
    }

    if (stream_thread_.joinable()) {
        stream_thread_.join();
    }

    // Discard any active URBs on this file descriptor
    if (fd_ >= 0) {
        ioctl(fd_, USBDEVFS_DISCARDURB, nullptr);
    }

    data_urbs_.clear();
    sync_urbs_.clear();

    LOGI("Stopped stream");
    return 0;
}

size_t UsbStreamEngine::writeAudio(const uint8_t* buffer, size_t size) {
    return ring_buffer_.write(buffer, size);
}

void UsbStreamEngine::streamLoop() {
    // Elevate thread priority to SCHED_FIFO for real-time isochronous pacing
    struct sched_param param{};
    param.sched_priority = 2; // Real-time priority
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);

    // Max packet size calculation: e.g. for 384kHz 32-bit stereo -> 48 samples * 8 bytes = 384 bytes
    const uint32_t max_samples_per_pkt = (sample_rate_ + MICROFRAMES_PER_SEC - 1) / MICROFRAMES_PER_SEC + 4;
    const uint32_t max_packet_size = max_samples_per_pkt * bytes_per_frame_;
    const uint32_t urb_buffer_size = max_packet_size * PACKETS_PER_URB;

    data_urbs_.resize(NUM_URBS);
    for (auto& ctx : data_urbs_) {
        ctx.buffer.assign(urb_buffer_size, 0);
        ctx.iso_descs.resize(PACKETS_PER_URB);

        ctx.urb.type = USBDEVFS_URB_TYPE_ISO;
        ctx.urb.endpoint = static_cast<unsigned char>(data_ep_);
        ctx.urb.buffer = ctx.buffer.data();
        ctx.urb.buffer_length = static_cast<int>(urb_buffer_size);
        ctx.urb.number_of_packets = static_cast<int>(PACKETS_PER_URB);
        ctx.urb.usercontext = &ctx;

        for (size_t p = 0; p < PACKETS_PER_URB; ++p) {
            ctx.iso_descs[p].length = max_packet_size;
            ctx.iso_descs[p].actual_length = 0;
            ctx.iso_descs[p].status = 0;
        }

        if (fd_ >= 0) {
            ioctl(fd_, USBDEVFS_SUBMITURB, &ctx.urb);
        }
    }

    while (streaming_.load(std::memory_order_relaxed)) {
        if (fd_ < 0) {
            usleep(1000);
            continue;
        }

        usbdevfs_urb* reaped_urb = nullptr;
        int ret = ioctl(fd_, USBDEVFS_REAPURBNDELAY, &reaped_urb);

        if (ret == 0 && reaped_urb != nullptr) {
            auto* ctx = static_cast<UrbContext*>(reaped_urb->usercontext);

            // Fill next URB packets from ring buffer
            uint8_t* ptr = ctx->buffer.data();
            int total_length = 0;

            for (size_t p = 0; p < PACKETS_PER_URB; ++p) {
                // Determine sample count for this microframe (using fractional accumulator)
                fractional_accum_ += feedback_rate_q16_;
                uint32_t samples_to_send = fractional_accum_ >> 16;
                fractional_accum_ &= 0xFFFF;

                uint32_t bytes_to_send = samples_to_send * bytes_per_frame_;
                size_t read_bytes = ring_buffer_.read(ptr, bytes_to_send);

                // Zero out any underrun deficit to keep isochronous clock lock intact
                if (read_bytes < bytes_to_send) {
                    std::memset(ptr + read_bytes, 0, bytes_to_send - read_bytes);
                }

                // Apply 64-bit float software volume attenuation if multiplier != 1.0
                double mult = volume_multiplier_.load(std::memory_order_relaxed);
                if (std::abs(mult - 1.0) > 1e-6) {
                    if (bytes_per_sample_ == 2) { // 16-bit PCM
                        auto* samples = reinterpret_cast<int16_t*>(ptr);
                        size_t sample_count = bytes_to_send / sizeof(int16_t);
                        for (size_t i = 0; i < sample_count; ++i) {
                            double scaled = static_cast<double>(samples[i]) * mult;
                            samples[i] = static_cast<int16_t>(std::clamp(scaled, -32768.0, 32767.0));
                        }
                    } else if (bytes_per_sample_ == 4) { // 32-bit PCM
                        auto* samples = reinterpret_cast<int32_t*>(ptr);
                        size_t sample_count = bytes_to_send / sizeof(int32_t);
                        for (size_t i = 0; i < sample_count; ++i) {
                            double scaled = static_cast<double>(samples[i]) * mult;
                            samples[i] = static_cast<int32_t>(std::clamp(scaled, -2147483648.0, 2147483647.0));
                        }
                    }
                }

                ctx->iso_descs[p].length = bytes_to_send;
                ctx->iso_descs[p].actual_length = 0;
                ctx->iso_descs[p].status = 0;

                ptr += bytes_to_send;
                total_length += static_cast<int>(bytes_to_send);
            }

            ctx->urb.buffer_length = total_length;
            ctx->urb.number_of_packets = static_cast<int>(PACKETS_PER_URB);

            // Resubmit URB
            ioctl(fd_, USBDEVFS_SUBMITURB, &ctx->urb);
        } else {
            // Small sleep (125 microseconds = 1 microframe) to avoid CPU pegging when idle
            usleep(125);
        }
    }
}

} // namespace echo::music::usbaudio
