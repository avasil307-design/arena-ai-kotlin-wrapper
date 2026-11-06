package ai.arena.wrapper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val HOME = "https://arena.ai/"
        private const val PICK_FILE = 1
        private const val RETRY_URL = "retry_url"
        private const val SHOW_ERROR = "show_error"
    }

    private lateinit var webView: WebView
    private lateinit var errorView: View
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var retryUrl = HOME
    private var mainFrameFailed = false

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // Android 15 enforces edge-to-edge: keep content clear of both system bars and IME.
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout() or
                        android.view.WindowInsets.Type.ime()
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = true // Content URIs returned by the system picker.
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                if (android.os.Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = true
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return route(request.url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                mainFrameFailed = false
                if (isInternal(Uri.parse(url))) retryUrl = url
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                if (mainFrameFailed) showError()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) showError()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // Never bypass certificate validation.
                if (error.url == view.url || error.url == retryUrl) showError()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent().apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }, PICK_FILE)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    Toast.makeText(this@MainActivity, R.string.picker_missing, Toast.LENGTH_LONG).show()
                    true
                }
            }

            // target=_blank/window.open must go through the same host policy.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture) return false
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClient() {
                    private fun handle(uri: Uri): Boolean {
                        if (uri.toString() == "about:blank") return false
                        if (!route(uri) && isInternal(uri)) webView.loadUrl(uri.toString())
                        popup.stopLoading()
                        popup.destroy()
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = handle(request.url)
                    @Deprecated("Legacy WebView callback")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String) = handle(Uri.parse(url))
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        errorView = createErrorView()
        root.addView(errorView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.requestApplyInsets()
        retryUrl = savedInstanceState?.getString(RETRY_URL)?.takeIf { isInternal(Uri.parse(it)) } ?: HOME
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(retryUrl)
        }
        if (savedInstanceState?.getBoolean(SHOW_ERROR) == true) showError()
    }

    private fun isInternal(uri: Uri): Boolean =
        uri.scheme.equals("https", true) && uri.host.equals("arena.ai", true) &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null

    private fun route(uri: Uri): Boolean {
        if (isInternal(uri)) return false
        if (uri.scheme.equals("http", true) && uri.host.equals("arena.ai", true) &&
            (uri.port == -1 || uri.port == 80) && uri.userInfo == null) {
            webView.loadUrl(uri.buildUpon().scheme("https").encodedAuthority("arena.ai").build().toString())
            return true
        }
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        if (host == "accounts.google.com" || host.endsWith(".accounts.google.com")) {
            AlertDialog.Builder(this)
                .setTitle(R.string.google_title)
                .setMessage(R.string.google_message)
                .setPositiveButton(R.string.open_browser) { _, _ -> openExternal(Uri.parse(HOME)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else if (uri.scheme?.lowercase(java.util.Locale.ROOT) in setOf("https", "http", "mailto", "tel")) {
            openExternal(uri)
        }
        // Block javascript:, file:, content:, intent: and unknown schemes from navigation.
        return true
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.browser_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun createErrorView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(32, 32, 32, 32)
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        addView(TextView(context).apply {
            setText(R.string.error_title)
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            setText(R.string.error_message)
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        })
        addView(Button(context).apply {
            setText(R.string.retry)
            setOnClickListener {
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.loadUrl(retryUrl)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showError() {
        mainFrameFailed = true
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    @Deprecated("Uses native Activity back dispatch for API 26 compatibility")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        outState.putString(RETRY_URL, retryUrl)
        outState.putBoolean(SHOW_ERROR, mainFrameFailed)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    @Deprecated("Native file picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
