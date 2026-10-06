package echo.music.usbaudio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * Identifies USB audio devices, checks interfaces, and filters out non-audio peripherals.
 */
class UsbDeviceDetector {

    /**
     * Checks if a device has an audio interface (`USB_CLASS_AUDIO` = 1).
     */
    fun isAudioClassDevice(hasAudioInterface: Boolean): Boolean {
        return hasAudioInterface
    }

    /**
     * Inspects a connected [UsbDevice] to determine if it exposes an Audio Class interface.
     */
    fun isUsbAudioDevice(device: UsbDevice): Boolean {
        // Direct device class
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) {
            return true
        }

        // Interface scanning
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }
}
