package com.adshield.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val done: Long,
    val total: Long
)

/** One screen for History, Bookmarks and Downloads. The mode is passed in the intent. */
class LibraryActivity : Activity() {

    private lateinit var mode: String
    private lateinit var listBox: LinearLayout
    private lateinit var filterBox: EditText
    private lateinit var clearButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            // keeps refreshing only while a download is still running
            if (render()) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HISTORY
        listBox = findViewById(R.id.listBox)
        filterBox = findViewById(R.id.filterBox)
        clearButton = findViewById(R.id.clearButton)

        findViewById<TextView>(R.id.titleText).text = when (mode) {
            MODE_BOOKMARKS -> "Bookmarks"
            MODE_DOWNLOADS -> "Downloads"
            else -> "History"
        }

        if (mode == MODE_DOWNLOADS) {
            filterBox.visibility = View.GONE
            clearButton.visibility = View.GONE
        }

        filterBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                refresh()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        clearButton.setOnClickListener { confirmClearAll() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun refresh() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** Redraws the list. Returns true if a download is still in progress. */
    private fun render(): Boolean {
        listBox.removeAllViews()
        return when (mode) {
            MODE_HISTORY -> {
                renderPages(BrowserStore.history(this), true)
                false
            }
            MODE_BOOKMARKS -> {
                renderPages(BrowserStore.bookmarks(this), false)
                false
            }
            else -> renderDownloads()
        }
    }

    private fun renderPages(pages: List<SavedPage>, showTime: Boolean) {
        val q = filterBox.text.toString().trim().lowercase()
        val shown = pages.filter {
            q.isEmpty() || it.url.lowercase().contains(q) || it.title.lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            listBox.addView(message(if (pages.isEmpty()) "Nothing here yet" else "No matches"))
            return
        }
        for (p in shown.take(300)) {
            val sub = if (showTime) {
                "${DateFormat.format("d MMM, HH:mm", p.time)}  ·  ${p.url}"
            } else {
                p.url
            }
            listBox.addView(
                row(
                    p.title.ifBlank { p.url },
                    sub,
                    { openInBrowser(p.url) },
                    {
                        if (mode == MODE_HISTORY) {
                            BrowserStore.removeHistory(this, p.url, p.time)
                        } else {
                            BrowserStore.removeBookmark(this, p.url)
                        }
                        refresh()
                    }
                )
            )
        }
    }

    private fun renderDownloads(): Boolean {
        val ids = BrowserStore.downloadIds(this)
        if (ids.isEmpty()) {
            listBox.addView(message("No downloads yet"))
            return false
        }
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val items = ArrayList<DownloadItem>()
        try {
            val cursor = dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val doneCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    items.add(
                        DownloadItem(
                            c.getLong(idCol),
                            c.getString(titleCol) ?: "Download",
                            c.getInt(statusCol),
                            c.getLong(doneCol),
                            c.getLong(totalCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            listBox.addView(message("Couldn't read downloads"))
            return false
        }

        // forget downloads that were removed elsewhere (e.g. from the system Downloads app)
        val foundIds = items.map { it.id }.toSet()
        for (id in ids) {
            if (id !in foundIds) BrowserStore.removeDownload(this, id)
        }

        if (items.isEmpty()) {
            listBox.addView(message("No downloads yet"))
            return false
        }

        var running = false
        for (item in items.sortedByDescending { it.id }) {
            val sub = when (item.status) {
                DownloadManager.STATUS_SUCCESSFUL ->
                    "Done  ·  ${Formatter.formatShortFileSize(this, item.total)}"
                DownloadManager.STATUS_FAILED -> "Failed"
                DownloadManager.STATUS_PAUSED -> "Paused"
                else -> {
                    running = true
                    if (item.total > 0) {
                        "${item.done * 100 / item.total}%  of  ${Formatter.formatShortFileSize(this, item.total)}"
                    } else {
                        "Downloading…"
                    }
                }
            }
            listBox.addView(
                row(
                    item.title,
                    sub,
                    { if (item.status == DownloadManager.STATUS_SUCCESSFUL) openFile(item.id) },
                    { confirmDelete(item.id) }
                )
            )
        }
        return running
    }

    private fun openFile(id: Long) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id)
        if (uri == null) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Delete this download?")
            .setMessage("The file will also be removed from your phone.")
            .setPositiveButton("Delete") { _, _ ->
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(id)
                BrowserStore.removeDownload(this, id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        val what = if (mode == MODE_BOOKMARKS) "bookmarks" else "history"
        AlertDialog.Builder(this)
            .setTitle("Clear all $what?")
            .setPositiveButton("Clear") { _, _ ->
                if (mode == MODE_BOOKMARKS) BrowserStore.clearBookmarks(this)
                else BrowserStore.clearHistory(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openInBrowser(url: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun message(text: String): View = TextView(this).apply {
        this.text = text
        alpha = 0.6f
        setPadding(0, dp(16), 0, dp(16))
    }

    private fun row(title: String, sub: String, onClick: () -> Unit, onDelete: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            alpha = 0.6f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val delete = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setPadding(dp(16), dp(10), dp(4), dp(10))
            setOnClickListener { onDelete() }
        }
        row.addView(texts)
        row.addView(delete)
        return row
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_URL = "url"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
        const val MODE_DOWNLOADS = "downloads"
    }
}
