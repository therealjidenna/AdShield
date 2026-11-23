package com.adshield.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class BrowserActivity : Activity() {

    private val tabs = ArrayList<WebView>()
    private var current = -1

    private lateinit var container: FrameLayout
    private lateinit var urlBox: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: Button
    private lateinit var blockedText: TextView

    private var pendingDownload: Array<String?>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            blockedText.text = "Blocked: ${AdStats.pageBlocked.get()}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        container = findViewById(R.id.container)
        urlBox = findViewById(R.id.urlBox)
        progress = findViewById(R.id.progress)
        tabButton = findViewById(R.id.tabsButton)
        blockedText = findViewById(R.id.blockedText)

        Blocklist.load(this)

        urlBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(urlBox.text.toString())
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.goButton).setOnClickListener { go(urlBox.text.toString()) }
        findViewById<Button>(R.id.backButton).setOnClickListener {
            currentWebView()?.let { if (it.canGoBack()) it.goBack() }
        }
        findViewById<Button>(R.id.forwardButton).setOnClickListener {
            currentWebView()?.let { if (it.canGoForward()) it.goForward() }
        }
        findViewById<Button>(R.id.reloadButton).setOnClickListener { currentWebView()?.reload() }
        tabButton.setOnClickListener { showTabs() }
        findViewById<Button>(R.id.menuButton).setOnClickListener { showMenu() }

        val startUrl = intent?.dataString
        newTab(startUrl ?: HOME)
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.onResume() }
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        tabs.forEach { it.onPause() }
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        for (t in tabs) {
            container.removeView(t)
            t.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = currentWebView()
        when {
            wv != null && wv.canGoBack() -> wv.goBack()
            tabs.size > 1 -> closeCurrentTab()
            else -> super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LIBRARY && resultCode == RESULT_OK) {
            val url = data?.getStringExtra(LibraryActivity.EXTRA_URL)
            if (url != null) currentWebView()?.loadUrl(url)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val p = pendingDownload
            pendingDownload = null
            if (p != null && p[0] != null &&
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startDownload(p[0]!!, p[1], p[2], p[3])
            } else {
                Toast.makeText(this, "Storage permission is needed to save downloads", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun currentWebView(): WebView? =
        if (current in tabs.indices) tabs[current] else null

    private fun newTab(url: String) {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = false // blocks script pop-ups
        s.setSupportMultipleWindows(false)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        wv.webViewClient = AdBlockClient()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === currentWebView()) {
                    progress.progress = newProgress
                    progress.visibility =
                        if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
        wv.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            startDownload(downloadUrl, userAgent, contentDisposition, mimeType)
        }

        tabs.add(wv)
        switchTo(tabs.size - 1)
        wv.loadUrl(url)
    }

    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        container.removeAllViews()
        current = index
        container.addView(tabs[index])
        tabButton.text = tabs.size.toString()
        progress.visibility = android.view.View.GONE
        urlBox.setText(tabs[index].url ?: "")
    }

    private fun closeCurrentTab() {
        val wv = currentWebView() ?: return
        val index = current
        container.removeView(wv)
        tabs.removeAt(index)
        wv.destroy()
        if (tabs.isEmpty()) {
            current = -1
            newTab(HOME)
        } else {
            switchTo(minOf(index, tabs.size - 1))
        }
    }

    private fun showTabs() {
        val titles = tabs.mapIndexed { i, t ->
            val name = t.title?.takeIf { it.isNotBlank() } ?: t.url ?: "New tab"
            (if (i == current) "● " else "   ") + name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs")
            .setItems(titles) { _, which -> switchTo(which) }
            .setPositiveButton("New tab") { _, _ -> newTab(HOME) }
            .setNeutralButton("Close this tab") { _, _ -> closeCurrentTab() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMenu() {
        val url = currentWebView()?.url
        val bookmarked = url != null && BrowserStore.isBookmarked(this, url)
        val items = arrayOf(
            if (bookmarked) "Remove bookmark" else "Add bookmark",
            "Bookmarks",
            "History",
            "Downloads",
            "Clear browsing data"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleBookmark()
                    1 -> openLibrary(LibraryActivity.MODE_BOOKMARKS)
                    2 -> openLibrary(LibraryActivity.MODE_HISTORY)
                    3 -> openLibrary(LibraryActivity.MODE_DOWNLOADS)
                    4 -> confirmClearData()
                    else -> {}
                }
            }
            .show()
    }

    private fun toggleBookmark() {
        val wv = currentWebView() ?: return
        val url = wv.url
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show()
            return
        }
        val now = BrowserStore.toggleBookmark(this, url, wv.title ?: "")
        Toast.makeText(this, if (now) "Bookmark added" else "Bookmark removed", Toast.LENGTH_SHORT).show()
    }

    private fun openLibrary(mode: String) {
        val intent = Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, mode)
        startActivityForResult(intent, REQ_LIBRARY)
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear browsing data?")
            .setMessage("This removes history, cookies, saved site data and the cache. Bookmarks and downloads are kept.")
            .setPositiveButton("Clear") { _, _ ->
                BrowserStore.clearHistory(this)
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.clearCache(true) }
                Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "This type of download isn't supported", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = arrayOf(url, userAgent, contentDisposition, mimeType)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
            if (mimeType != null) request.setMimeType(mimeType)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent)
            request.setTitle(fileName)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            BrowserStore.addDownload(this, id)
            Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start the download", Toast.LENGTH_LONG).show()
        }
    }

    private fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
        }
        currentWebView()?.loadUrl(url)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBox.windowToken, 0)
        urlBox.clearFocus()
    }

    private inner class AdBlockClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (request.isForMainFrame) return null
            val url = request.url
            val host = url.host ?: return null
            val path = url.path ?: ""
            if (Blocklist.isBlocked(host) || path.contains("/pagead/")) {
                AdStats.pageBlocked.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return false
            if (scheme == "http" || scheme == "https") return false
            if (scheme == "tel" || scheme == "mailto" || scheme == "sms") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                }
            }
            // anything else (intent:, market:, ...) is usually an ad forcing an app open, so it's dropped
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (view === currentWebView() && !urlBox.hasFocus()) {
                urlBox.setText(url ?: "")
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(HIDE_ADS_JS, null)
            if (view === currentWebView() && !urlBox.hasFocus()) {
                urlBox.setText(url ?: "")
            }
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                BrowserStore.addHistory(this@BrowserActivity, url, view.title ?: "")
            }
        }
    }

    companion object {
        private const val HOME = "https://duckduckgo.com/"
        private const val REQ_LIBRARY = 2
        private const val REQ_STORAGE = 3

        // hides leftover empty ad boxes after the blocked requests fail to load
        private const val HIDE_ADS_JS = """
            (function() {
              var s = document.createElement('style');
              s.textContent = 'ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],' +
                'iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"],' +
                '[class*="adsbygoogle"],[data-ad-slot],[data-ad-client]' +
                '{display:none !important;}';
              (document.head || document.documentElement).appendChild(s);
            })();
        """
    }
}
