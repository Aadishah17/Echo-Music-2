package echo.music.iad1tya.lyrics

import android.content.Context
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.FetchFasterLyricsKey
import echo.music.iad1tya.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import echo.music.iad1tya.models.MediaMetadata
import echo.music.iad1tya.playback.LyricsWithProvider
import echo.music.iad1tya.utils.NetworkConnectivityObserver
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.reportException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LyricsHelper
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val networkConnectivity: NetworkConnectivityObserver,
) {

  private suspend fun resolveLyricsProviders(): List<LyricsProvider> {
    val defaultOrder = LyricsProviderRegistry.getDefaultProviderOrder()
    return defaultOrder.mapNotNull { LyricsProviderRegistry.getProviderByName(it) }
  }

  private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
  private val inFlightRequests = ConcurrentHashMap<String, Deferred<LyricsWithProvider>>()
  private var currentLyricsJob: Job? = null

  suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
    currentLyricsJob?.cancel()

    val cached = cache.get(mediaMetadata.id)?.firstOrNull()
    if (cached != null) {
      return LyricsWithProvider(cached.lyrics, cached.providerName)
    }

    inFlightRequests[mediaMetadata.id]?.let {
      return it.await()
    }

    val isNetworkAvailable =
      try {
        networkConnectivity.isCurrentlyConnected()
      } catch (e: Exception) {
        true
      }

    if (!isNetworkAvailable) {
      return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
    }

    val providers = resolveLyricsProviders()
    if (providers.isEmpty()) return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")

    return coroutineScope {
      val deferred =
        async {
          try {
            val channel = Channel<LyricsWithProvider?>(providers.size)
            providers.forEach { provider ->
              launch {
                val lyrics = fetchLyricsFromProvider(provider, mediaMetadata)
                val result =
                  lyrics
                    ?.takeIf { it != LYRICS_NOT_FOUND && it.isNotBlank() }
                    ?.let { LyricsWithProvider(it, provider.name) }
                channel.send(result)
              }
            }

            var responses = 0
            val receivedUnsynced = mutableListOf<LyricsWithProvider>()

            while (responses < providers.size) {
              val result = channel.receive()
              responses++
              if (result != null) {
                val isSynced = result.lyrics?.trimStart()?.startsWith("[") == true
                if (isSynced) {
                  coroutineContext.cancelChildren()
                  cache.put(
                    mediaMetadata.id,
                    listOf(LyricsResult(result.providerName, result.lyrics ?: "")),
                  )
                  return@async result
                } else {
                  receivedUnsynced.add(result)
                }
              }
            }
            val best = receivedUnsynced.firstOrNull() ?: LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
            if (best.lyrics != null && best.lyrics != LYRICS_NOT_FOUND) {
              cache.put(
                mediaMetadata.id,
                listOf(LyricsResult(best.providerName, best.lyrics ?: "")),
              )
            }
            best
          } finally {
            inFlightRequests.remove(mediaMetadata.id)
          }
        }
      inFlightRequests[mediaMetadata.id] = deferred
      deferred.await()
    }
  }

  private suspend fun fetchLyricsFromProvider(
    provider: LyricsProvider,
    mediaMetadata: MediaMetadata,
  ): String? =
    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
      try {
        provider
          .getLyrics(
            mediaMetadata.id,
            mediaMetadata.title,
            mediaMetadata.artists.joinToString { it.name },
            mediaMetadata.duration,
            mediaMetadata.album?.title,
          )
          .getOrElse {
            if (it is CancellationException) throw it
            reportException(it)
            null
          }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        reportException(e)
        null
      }
    }

  suspend fun getAllLyrics(
    mediaId: String,
    songTitle: String,
    songArtists: String,
    duration: Int,
    album: String? = null,
    callback: (LyricsResult) -> Unit,
  ) {
    currentLyricsJob?.cancel()

    val cacheKey = "$songArtists-$songTitle".replace(" ", "")
    cache.get(cacheKey)?.let { results ->
      results.forEach { callback(it) }
      return
    }

    val isNetworkAvailable =
      try {
        networkConnectivity.isCurrentlyConnected()
      } catch (e: Exception) {
        true
      }

    if (!isNetworkAvailable) {
      return
    }

    val allResult = java.util.concurrent.CopyOnWriteArrayList<LyricsResult>()
    val providers = resolveLyricsProviders()
    currentLyricsJob =
      CoroutineScope(SupervisorJob()).launch {
        val jobs =
          providers.mapNotNull { provider ->
            if (provider.isEnabled(context)) {
              launch {
                try {
                  provider.getAllLyrics(mediaId, songTitle, songArtists, duration, album) { lyrics
                    ->
                    val result = LyricsResult(provider.name, lyrics)
                    allResult += result
                    callback(result)
                  }
                } catch (e: Exception) {
                  reportException(e)
                }
              }
            } else null
          }
        jobs.forEach { it.join() }
        cache.put(cacheKey, allResult.toList())
      }

    currentLyricsJob?.join()
  }

  fun cancelCurrentLyricsJob() {
    currentLyricsJob?.cancel()
    currentLyricsJob = null
  }

  companion object {
    private const val MAX_CACHE_SIZE = 20
    private const val PROVIDER_TIMEOUT_MS = 5_000L
  }
}

data class LyricsResult(
  val providerName: String,
  val lyrics: String,
)
