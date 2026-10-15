package echo.music.iad1tya.ui.screens.settings

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import echo.music.iad1tya.ui.utils.isTrustedHttpsUrl
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordTokenWebView(onTokenExtracted: (String) -> Unit) {
  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { context ->
      WebView(context).apply {
        layoutParams =
          android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
          )
        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          allowFileAccess = false
          allowContentAccess = false
          mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
          loadWithOverviewMode = true
          useWideViewPort = true
          userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        webViewClient =
          object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
              view: WebView,
              request: WebResourceRequest,
            ): Boolean = request.isForMainFrame && !request.url.toString().isTrustedDiscordUrl()

            override fun onPageFinished(view: WebView, url: String) {
              super.onPageFinished(view, url)
              if (url.isTrustedDiscordUrl() && (url.contains("/channels") || url.contains("/app"))) {
                view.evaluateJavascript(
                  """
                                (function() {
                                    try {
                                        var iframe = document.createElement('iframe');
                                        document.head.append(iframe);
                                        var pd = Object.getOwnPropertyDescriptor(iframe.contentWindow, 'localStorage');
                                        iframe.remove();
                                        Object.defineProperty(window, 'localStorage', pd);
                                        return window.localStorage.getItem('token');
                                    } catch(e) {
                                        return null;
                                    }
                                })();
                                """
                    .trimIndent(),
                ) { result ->
                  val token =
                    runCatching {
                      Json.decodeFromString<String>(result)
                    }.getOrNull()
                  if (!token.isNullOrBlank()) onTokenExtracted(token)
                }
              }
            }
          }
        loadUrl("https://discord.com/login")
      }
    },
    update = { webView ->
      // Update block if needed
    }
  )
}

private fun String?.isTrustedDiscordUrl(): Boolean = isTrustedHttpsUrl(this, "discord.com")
