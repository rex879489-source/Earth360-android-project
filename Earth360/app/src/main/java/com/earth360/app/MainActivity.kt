package com.earth360.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import org.json.JSONTokener
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())

    private var pageLoaded = false
    private var pendingShared: String? = null
    private var extractor: BackgroundExtractor? = null
    private var webUserAgent = "" // cached on the UI thread, read from the request thread

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    // Hosts whose responses get a permissive CORS header so the canvas is never tainted.
    private val corsHosts = listOf("streetviewpixels-pa.googleapis.com", "googleusercontent.com")

    // ───────────────────────────── lifecycle ─────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(0xFF080C0F.toInt()) }
        web = WebView(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webUserAgent = web.settings.userAgentString
        web.setBackgroundColor(0xFF080C0F.toInt())
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                assetLoader.shouldInterceptRequest(request.url)?.let { return it }
                return corsProxy(request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                openExternal(request.url.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/")) {
                    pageLoaded = true
                    pendingShared?.let { pushSharedText(it) }
                    pendingShared = null
                }
            }
        }

        web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/index.html")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        extractor?.stop()
        web.destroy()
        super.onDestroy()
    }

    // ───────────────────────────── share intent ─────────────────────────────

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Maps/Earth share text is usually "Place name\nhttps://..." -> keep only the URL
        val url = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        if (pageLoaded) pushSharedText(url) else pendingShared = url
    }

    private fun pushSharedText(url: String) {
        val js = "(function(t){var e=document.getElementById('urlInput');" +
            "if(e){e.value=t;if(window.onUrlChange)onUrlChange();}})(" + JSONObject.quote(url) + ")"
        web.evaluateJavascript(js, null)
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    // ───────────────────────────── CORS proxy ─────────────────────────────

    private fun corsProxy(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val host = uri.host ?: return null
        if (request.method != "GET" || uri.scheme != "https") return null
        if (corsHosts.none { host == it || host.endsWith(".$it") }) return null
        return try {
            val conn = URL(uri.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", webUserAgent)
            request.requestHeaders["Accept"]?.let { conn.setRequestProperty("Accept", it) }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null // let WebView handle errors / redirects normally
            }
            val mime = (conn.contentType ?: "application/octet-stream").substringBefore(";").trim()
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "max-age=3600"
            )
            WebResourceResponse(mime, null, code, "OK", headers, conn.inputStream)
        } catch (e: Exception) {
            null
        }
    }

    // ───────────────────────────── JS bridge ─────────────────────────────

    inner class Bridge {
        private var out: OutputStream? = null
        private var uri: Uri? = null

        @JavascriptInterface
        fun beginSave(name: String, mime: String) {
            abortSave()
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, safe)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Earth360")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            uri = u
            out = contentResolver.openOutputStream(u)
                ?: throw IllegalStateException("Cannot open output stream")
        }

        @JavascriptInterface
        fun writeChunk(b64: String) {
            out?.write(Base64.decode(b64, Base64.DEFAULT))
        }

        @JavascriptInterface
        fun endSave() {
            val u = uri ?: return
            try {
                out?.flush()
                out?.close()
                val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                contentResolver.update(u, values, null, null)
                toast("Saved to Pictures/Earth360")
            } finally {
                out = null
                uri = null
            }
        }

        @JavascriptInterface
        fun abortSave() {
            try {
                out?.close()
            } catch (_: Exception) {
            }
            uri?.let {
                try {
                    contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
            out = null
            uri = null
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread { this@MainActivity.openExternal(url) }
        }

        @JavascriptInterface
        fun startExtract(url: String) {
            runOnUiThread {
                extractor?.stop()
                extractor = BackgroundExtractor().also { it.start(url) }
            }
        }

        @JavascriptInterface
        fun cancelExtract() {
            runOnUiThread {
                extractor?.stop()
                extractor = null
            }
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ───────────────────────────── background "desktop tab" ─────────────────────────────

    /**
     * Loads the Google Maps / Earth link in an invisible, desktop-mode WebView, polls its address bar
     * and hands every URL to the page (window.onAutoUrl). When the page says the URL contains
     * what it needs (pano id + lh3 token) the hidden browser is closed again.
     */
    private inner class BackgroundExtractor {
        private var view: WebView? = null
        private var startedAt = 0L
        private var lastUrl = ""
        private var finished = false

        private val desktopUa =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val poll = object : Runnable {
            override fun run() {
                val v = view ?: return
                if (SystemClock.elapsedRealtime() - startedAt > TIMEOUT_MS) {
                    fail("Background capture timed out.")
                    return
                }
                v.evaluateJavascript("location.href") { raw -> onHref(raw) }
                ui.postDelayed(this, 800)
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun start(url: String) {
            startedAt = SystemClock.elapsedRealtime()
            val v = WebView(this@MainActivity)
            view = v
            v.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = desktopUa // desktop site, like a desktop browser tab
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
            v.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val s = request.url.scheme
                    return s != "http" && s != "https" // block intent:// / market:// redirects
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    onHref(JSONObject.quote(url))
                }
            }
            // Keep it attached and "visible" (Maps needs a live surface) but far off-screen.
            root.addView(v, FrameLayout.LayoutParams(1280, 800))
            v.translationX = -5000f
            v.onResume()
            v.loadUrl(url)
            ui.postDelayed(poll, 1000)
        }

        /** [raw] is either a JSON string (from evaluateJavascript) or already a quoted string. */
        private fun onHref(raw: String?) {
            if (finished || raw == null) return
            val parsed: Any? = try {
                JSONTokener(raw).nextValue()
            } catch (e: Exception) {
                null
            }
            val href = parsed as? String ?: return
            if (href == lastUrl) return

            // Cookie-consent wall (EU etc.): try to press "accept"
            if (href.contains("consent.google.com")) {
                view?.evaluateJavascript(CONSENT_JS, null)
                return
            }
            if (!href.startsWith("http") || !href.contains("google.")) return
            lastUrl = href

            val js = "(window.onAutoUrl?window.onAutoUrl(" + JSONObject.quote(href) + "):false)"
            web.evaluateJavascript(js) { result ->
                if (result == "true" && !finished) {
                    finished = true
                    stop()
                }
            }
        }

        private fun fail(msg: String) {
            if (finished) return
            finished = true
            stop()
            web.evaluateJavascript(
                "window.onExtractFail&&window.onExtractFail(" + JSONObject.quote(msg) + ")",
                null
            )
        }

        fun stop() {
            ui.removeCallbacks(poll)
            finished = true
            view?.let {
                it.stopLoading()
                root.removeView(it)
                it.destroy()
            }
            view = null
            if (extractor === this) extractor = null
        }
    }

    companion object {
        private const val TIMEOUT_MS = 45_000L

        private const val CONSENT_JS =
            "(function(){var r=/accept all|i agree|agree|alle akzeptieren|tout accepter|aceptar todo|accetta tutto/i;" +
                "var b=Array.prototype.slice.call(document.querySelectorAll('button,input[type=submit]'))" +
                ".filter(function(x){return r.test((x.innerText||x.value||''));})[0];" +
                "if(b){b.click();return 1}return 0})()"
    }
}
