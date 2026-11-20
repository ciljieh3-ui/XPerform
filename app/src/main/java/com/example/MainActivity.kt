package com.example

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private var bridgeRef: XPerformBridge? = null
    private var webViewRef: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-Edge penuh dengan status bar & navigation bar transparan menyatu OLED #000000
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            XPerformWebViewScreen(
                onWebViewReady = { webView, bridge ->
                    webViewRef = webView
                    bridgeRef = bridge
                }
            )
        }
    }

    override fun onDestroy() {
        bridgeRef?.cleanup()
        bridgeRef = null
        webViewRef?.apply {
            stopLoading()
            removeJavascriptInterface("XPerformBridge")
            destroy()
        }
        webViewRef = null
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun XPerformWebViewScreen(
    onWebViewReady: (WebView, XPerformBridge) -> Unit
) {
    val context = LocalContext.current

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Akselerasi perangkat keras penuh untuk 60-120 FPS halus
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(Color.BLACK)

            // Matikan efek overscroll glow & sembunyikan scrollbar agar fisika scroll bersih
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isScrollbarFadingEnabled = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false

                // Matikan kontrol zoom berat yang tidak perlu
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false

                useWideViewPort = true
                loadWithOverviewMode = true
            }

            // Sinkronkan safe-area insets status bar & navigation bar ke CSS WebView
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val density = view.resources.displayMetrics.density
                val topDp = (bars.top / density).toInt()
                val bottomDp = (bars.bottom / density).toInt()
                view.post {
                    evaluateJavascript(
                        "document.documentElement.style.setProperty('--safe-top', '${topDp}px');" +
                            "document.documentElement.style.setProperty('--safe-bottom', '${bottomDp}px');",
                        null
                    )
                }
                insets
            }
        }
    }

    val bridge = remember(webView) {
        XPerformBridge(context.applicationContext, webView).also { xBridge ->
            webView.addJavascriptInterface(xBridge, "XPerformBridge")
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    ViewCompat.requestApplyInsets(webView)
                    xBridge.checkAndRequestShizuku()
                    xBridge.getInstalledApps()
                }
            }
            webView.loadUrl("file:///android_asset/index.html")
            onWebViewReady(webView, xBridge)
        }
    }

    DisposableEffect(bridge) {
        onDispose {
            // Lifecycle cleanup utama ditangani di MainActivity.onDestroy()
        }
    }

    BackHandler(enabled = true) {
        webView.evaluateJavascript(
            "(function(){ return typeof window.handleAndroidBack === 'function' ? window.handleAndroidBack() : false; })()"
        ) { handled ->
            if (handled != "true") {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    (context as? ComponentActivity)?.finish()
                }
            }
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor.Black)
    )
}
