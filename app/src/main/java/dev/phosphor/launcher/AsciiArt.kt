package dev.phosphor.launcher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/** Metadata from the bundled, pinned ascii.rest catalogue. */
data class ArtPiece(val id: String, val name: String, val category: String, val note: String, val cols: Int, val rows: Int, val fps: Int)

class AsciiArt(private val host: MainActivity, private val container: FrameLayout) {
    private var catalogueError: String? = null
    private var surfaceError: String? = null
    val error: String? get() = catalogueError ?: surfaceError

    val pieces: List<ArtPiece> by lazy {
        try {
            val json = host.assets.open("ascii/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
            val entries = json.getJSONArray("pieces")
            require(entries.length() > 0) { "Empty artwork catalogue" }
            List(entries.length()) { index ->
                val piece = entries.getJSONObject(index)
                ArtPiece(piece.getString("id"), piece.getString("name"), piece.getString("category"),
                    piece.getString("note"), piece.getInt("cols"), piece.getInt("rows"), piece.getInt("fps"))
            }
        } catch (e: Exception) {
            catalogueError = "Artwork catalogue unavailable: ${e.message ?: e.javaClass.simpleName}"
            host.redraw()
            emptyList()
        }
    }

    private var selectedId: String? = null
    private var selectedPiece: ArtPiece? = null
    val selected: ArtPiece
        get() {
            val id = host.prefs.getString("ascii_piece", DEFAULT_PIECE) ?: DEFAULT_PIECE
            selectedPiece?.let { if (id == selectedId) return it }
            val catalogue = pieces
            val piece = catalogue.firstOrNull { it.id == id }
                ?: catalogue.firstOrNull { it.id == DEFAULT_PIECE }
                ?: catalogue.firstOrNull() ?: FALLBACK_PIECE
            selectedId = id
            selectedPiece = piece
            return piece
        }

    private val power = host.getSystemService(PowerManager::class.java)
    private var saver = power?.isPowerSaveMode == true
    private var themeName: String? = null
    private var themeInk: Ink? = null
    private var receiverRegistered = false
    private val saverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!closed) {
                saver = power?.isPowerSaveMode == true
                updateSurface()
                host.redraw()
            }
        }
    }
    private var web: WebView? = null
    private var resumed = false
    private var wanted = false
    private var closed = false
    private var pageLoaded = false
    private var ready = false
    private var surfaceResumed = false
    private var left = 0
    private var top = 0
    private var width = 0
    private var height = 0
    private var boundsApplied = false
    private var appliedLeft = 0
    private var appliedTop = 0
    private var appliedWidth = 0
    private var appliedHeight = 0
    private var configApplied = false
    private var configuredPiece: String? = null
    private var configuredMono = false
    private var configuredPaused = true
    private var configuredInk = 0
    private var configuredBg = 0
    private var configuredFps = 0

    fun beginFrame() { wanted = false }

    fun place(x: Int, y: Int, width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        wanted = true
        left = x
        top = y
        this.width = width
        this.height = height
    }

    fun endFrame() { updateSurface() }

    fun resume() {
        if (closed) return
        resumed = true
        saver = power?.isPowerSaveMode == true
        if (!receiverRegistered) {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(saverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else host.registerReceiver(saverReceiver, filter)
            receiverRegistered = true
        }
        updateSurface()
    }

    fun pause() {
        resumed = false
        unregisterReceiver()
        updateSurface()
    }

    fun retry() {
        if (closed) return
        destroySurface()
        surfaceError = null
        updateSurface()
        host.redraw()
    }

    fun close() {
        if (closed) return
        closed = true
        resumed = false
        unregisterReceiver()
        destroySurface()
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            host.unregisterReceiver(saverReceiver)
            receiverRegistered = false
        }
    }

    private fun updateSurface() {
        if (closed) return
        if (!wanted || !resumed) {
            val view = web ?: return
            if (pageLoaded) syncConfiguration(view)
            if (view !== web) return
            if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE
            if (surfaceResumed) {
                view.onPause()
                surfaceResumed = false
            }
            return
        }
        if (error != null || pieces.isEmpty()) return
        val view = web ?: createSurface() ?: return
        if (!surfaceResumed) {
            view.onResume()
            surfaceResumed = true
        }
        if (!boundsApplied || appliedLeft != left || appliedTop != top || appliedWidth != width || appliedHeight != height) {
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.leftMargin = left
            params.topMargin = top
            params.width = width
            params.height = height
            view.layoutParams = params
            appliedLeft = left
            appliedTop = top
            appliedWidth = width
            appliedHeight = height
            boundsApplied = true
        }
        if (pageLoaded) syncConfiguration(view)
        if (ready && view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createSurface(): WebView? {
        try {
            WebView.setWebContentsDebuggingEnabled(host.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            val view = object : WebView(host) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
            }
            web = view
            view.visibility = View.INVISIBLE
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            view.isClickable = false
            view.isLongClickable = false
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.isHorizontalScrollBarEnabled = false
            view.isVerticalScrollBarEnabled = false
            view.overScrollMode = View.OVER_SCROLL_NEVER
            view.setBackgroundColor(Inks.get(host.prefs.getString("theme", "phosphor")).bg)
            view.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
                domStorageEnabled = false
                databaseEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            view.webViewClient = assetClient
            view.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        fail(view, "Artwork script error: ${message.message()}")
                    }
                    return true
                }
            }
            container.addView(view, minOf(1, container.childCount), FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = left
                topMargin = top
            })
            surfaceResumed = true
            view.loadUrl(INDEX_URL)
            return view
        } catch (e: Exception) {
            fail(web, "Artwork WebView unavailable: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
    }

    private val assetClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        @Deprecated("Used by older WebView providers")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val asset = assetPath(request.url)
            if (request.method != "GET" || asset == null) return deniedResponse()
            return try {
                val mime = when (asset) {
                    "index.html" -> "text/html"
                    "player.js" -> "application/javascript"
                    "catalog.json" -> "application/json"
                    else -> "text/plain"
                }
                WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                    "Cache-Control" to "no-store",
                    "X-Content-Type-Options" to "nosniff",
                    "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'none'; img-src 'none'; font-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'"
                ), host.assets.open("ascii/$asset"))
            } catch (e: Exception) {
                if (request.isForMainFrame) view.post { fail(view, "Artwork asset unavailable: ${e.message ?: asset}") }
                deniedResponse()
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view === web && url != INDEX_URL) fail(view, "Artwork navigation blocked")
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== web || url != INDEX_URL) return
            pageLoaded = true
            syncConfiguration(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) fail(view, "Artwork load failed: ${error.description}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) fail(view, "Artwork load failed: HTTP ${response.statusCode}")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            fail(view, "Artwork connection blocked")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            fail(view, if (detail.didCrash()) "Artwork renderer crashed; retry to reload" else "Artwork renderer stopped; retry to reload", rendererGone = true)
            return true
        }
    }

    private fun syncConfiguration(view: WebView) {
        if (view !== web || !pageLoaded || surfaceError != null) return
        val piece = selected
        val mono = host.prefs.getBoolean("ascii_mono", false)
        val paused = !resumed || !wanted || host.prefs.getBoolean("ascii_paused", false)
        val ink = currentInk()
        val fps = minOf(piece.fps.coerceAtLeast(1), if (saver) 4 else 12)
        if (configApplied && configuredPiece == piece.id && configuredMono == mono && configuredPaused == paused &&
            configuredInk == ink.fg && configuredBg == ink.bg && configuredFps == fps) return
        configApplied = true
        configuredPiece = piece.id
        configuredMono = mono
        configuredPaused = paused
        configuredInk = ink.fg
        configuredBg = ink.bg
        configuredFps = fps
        view.setBackgroundColor(ink.bg)
        val config = JSONObject().put("piece", piece.id).put("mono", mono).put("paused", paused)
            .put("ink", rgb(ink.fg)).put("bg", rgb(ink.bg)).put("fps", fps)
        val script = """(function(){try{if(!window.PhosphorArt||typeof window.PhosphorArt.configure!=="function")throw new Error("Local artwork player did not initialize");var s=window.PhosphorArt.configure($config);if(!s||typeof s!=="object")throw new Error("Invalid artwork player status");return JSON.stringify({error:s.error||null});}catch(e){return JSON.stringify({error:String(e)});}})()"""
        try {
            view.evaluateJavascript(script) { result ->
                if (view !== web) return@evaluateJavascript
                try {
                    val status = JSONTokener(result).nextValue() as? String
                        ?: throw IllegalStateException("Artwork player returned no status")
                    val state = JSONObject(status)
                    if (!state.isNull("error")) {
                        fail(view, "Artwork player failed: ${state.getString("error")}")
                    } else if (!ready) {
                        ready = true
                        updateSurface()
                        host.redraw()
                    }
                } catch (e: Exception) {
                    fail(view, "Artwork initialization failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            fail(view, "Artwork player unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(view: WebView?, message: String, rendererGone: Boolean = false) {
        if (closed || (view != null && view !== web)) return
        surfaceError = message
        destroySurface(rendererGone)
        host.redraw()
    }

    private fun destroySurface(rendererGone: Boolean = false) {
        val view = web
        web = null
        pageLoaded = false
        ready = false
        surfaceResumed = false
        configApplied = false
        boundsApplied = false
        if (view != null) {
            container.removeView(view)
            if (!rendererGone) {
                view.stopLoading()
                view.onPause()
            }
            view.destroy()
        }
    }

    private fun assetPath(uri: Uri): String? {
        if (uri.scheme != "https" || uri.host != ASSET_HOST || (uri.port != -1 && uri.port != 443) ||
            uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return when (uri.encodedPath) {
            "/ascii/index.html" -> "index.html"
            "/ascii/player.js" -> "player.js"
            "/ascii/catalog.json" -> "catalog.json"
            "/ascii/LICENSE" -> "LICENSE"
            else -> null
        }
    }

    private fun deniedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    private fun currentInk(): Ink {
        val name = host.prefs.getString("theme", "phosphor")
        themeInk?.let { if (themeName == name) return it }
        val ink = Inks.get(name)
        themeName = name
        themeInk = ink
        return ink
    }

    private fun rgb(color: Int) = "#" + (color and 0xffffff).toString(16).padStart(6, '0')

    private companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val INDEX_URL = "https://$ASSET_HOST/ascii/index.html"
        const val DEFAULT_PIECE = "night-coast"
        val FALLBACK_PIECE = ArtPiece(DEFAULT_PIECE, "Night Coast", "", "", 200, 100, 15)
    }
}
