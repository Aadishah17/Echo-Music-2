package echo.music.iad1tya.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.innertube.YouTube
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.AccountChannelHandleKey
import echo.music.iad1tya.constants.AccountEmailKey
import echo.music.iad1tya.constants.AccountNameKey
import echo.music.iad1tya.constants.DataSyncIdKey
import echo.music.iad1tya.constants.InnerTubeCookieKey
import echo.music.iad1tya.constants.SavedAccountsKey
import echo.music.iad1tya.constants.VisitorDataKey
import echo.music.iad1tya.models.AccountData
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.utils.backToMain
import echo.music.iad1tya.ui.utils.isTrustedHttpsUrl
import echo.music.iad1tya.utils.rememberPreference
import echo.music.iad1tya.utils.reportException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)
@Composable
fun LoginScreen(
  navController: NavController,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var visitorData by rememberPreference(VisitorDataKey, "")
  var dataSyncId by rememberPreference(DataSyncIdKey, "")
  var innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
  var accountName by rememberPreference(AccountNameKey, "")
  var accountEmail by rememberPreference(AccountEmailKey, "")
  var accountChannelHandle by rememberPreference(AccountChannelHandleKey, "")
  var savedAccountsJson by rememberPreference(SavedAccountsKey, "[]")
  var hasCompletedLogin by remember { mutableStateOf(false) }

  var webView: WebView? = null

  AndroidView(
    modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current).fillMaxSize(),
    factory = { webViewContext ->
      WebView(webViewContext).apply {
        webViewClient =
          object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
              view: WebView,
              request: WebResourceRequest,
            ): Boolean = request.isForMainFrame && !request.url.toString().isTrustedLoginUrl()

            override fun onPageFinished(view: WebView, url: String?) {
              if (!isTrustedHttpsUrl(url, "music.youtube.com") || hasCompletedLogin) return
              hasCompletedLogin = true

              view.evaluateJavascript(YOUTUBE_LOGIN_DATA_SCRIPT) { result ->
                if (!isTrustedHttpsUrl(view.url, "music.youtube.com")) {
                  hasCompletedLogin = false
                  return@evaluateJavascript
                }

                val loginData =
                  runCatching {
                    Json.decodeFromString<List<String?>>(
                      Json.decodeFromString<String>(result)
                    )
                  }.getOrNull()
                if (loginData == null) {
                  hasCompletedLogin = false
                  return@evaluateJavascript
                }

                visitorData = loginData.getOrNull(0).orEmpty()
                dataSyncId = loginData.getOrNull(1)?.substringBefore("||").orEmpty()
                innerTubeCookie = CookieManager.getInstance().getCookie(url).orEmpty()

                coroutineScope.launch {
                  delay(500)

                  YouTube.cookie = innerTubeCookie
                  YouTube.dataSyncId = dataSyncId
                  YouTube.visitorData = visitorData

                  Timber.d("Login: YouTube object initialized, validating...")

                  YouTube.accountInfo()
                    .onSuccess {
                      accountName = it.name
                      accountEmail = it.email.orEmpty()
                      accountChannelHandle = it.channelHandle.orEmpty()

                      val newAccount =
                        AccountData(
                          name = it.name,
                          email = it.email.orEmpty(),
                          channelHandle = it.channelHandle.orEmpty(),
                          cookie = innerTubeCookie,
                          visitorData = visitorData,
                          dataSyncId = dataSyncId,
                          avatarUrl = it.thumbnailUrl.orEmpty()
                        )
                      val accounts =
                        try {
                            Json.decodeFromString<List<AccountData>>(savedAccountsJson)
                          } catch (e: Exception) {
                            emptyList()
                          }
                          .toMutableList()
                      accounts.removeAll { acc -> acc.name == newAccount.name }
                      accounts.add(newAccount)
                      savedAccountsJson = Json.encodeToString(accounts)

                      Timber.d("Login: Successfully logged in as ${it.name}, restarting app...")

                      webView?.apply {
                        stopLoading()
                        clearHistory()
                        clearCache(true)
                        clearFormData()
                      }

                      val intent =
                        context.packageManager.getLaunchIntentForPackage(context.packageName)
                      intent?.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                      )
                      context.startActivity(intent)
                      delay(500)
                      Runtime.getRuntime().exit(0)
                    }
                    .onFailure {
                      Timber.e(it, "Login: Authentication validation failed")
                      hasCompletedLogin = false
                      reportException(it)
                    }
                }
              }
            }
          }
        settings.apply {
          javaScriptEnabled = true
          allowFileAccess = false
          allowContentAccess = false
          mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
          setSupportZoom(true)
          builtInZoomControls = true
          displayZoomControls = false
        }
        webView = this

        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
      }
    }
  )

  TopAppBar(
    title = { Text(stringResource(R.string.login)) },
    navigationIcon = {
      IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
      }
    }
  )

  BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }
}

private fun String?.isTrustedLoginUrl(): Boolean =
  isTrustedHttpsUrl(this, "accounts.google.com") || isTrustedHttpsUrl(this, "music.youtube.com")

private const val YOUTUBE_LOGIN_DATA_SCRIPT =
  "JSON.stringify([window.yt?.config_?.VISITOR_DATA ?? null, window.yt?.config_?.DATASYNC_ID ?? null])"
