package com.adshield.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SavedPage(val url: String, val title: String, val time: Long)

/** Keeps history, bookmarks and download ids on the phone (private to the app). */
object BrowserStore {

    private const val PREFS = "browser"
    private const val KEY_HISTORY = "history"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_DOWNLOADS = "downloads"
    private const val MAX_HISTORY = 500

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(c: Context, key: String): MutableList<SavedPage> {
        val out = ArrayList<SavedPage>()
        try {
            val arr = JSONArray(prefs(c).getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(SavedPage(o.getString("u"), o.optString("t", ""), o.optLong("d", 0L)))
            }
        } catch (e: Exception) {
            // corrupted data: start fresh
        }
        return out
    }

    private fun write(c: Context, key: String, list: List<SavedPage>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().put("u", p.url).put("t", p.title).put("d", p.time))
        }
        prefs(c).edit().putString(key, arr.toString()).apply()
    }

    // ---- History (newest first) ----

    @Synchronized
    fun history(c: Context): List<SavedPage> = read(c, KEY_HISTORY)

    @Synchronized
    fun addHistory(c: Context, url: String, title: String) {
        val list = read(c, KEY_HISTORY)
        val entry = SavedPage(url, title, System.currentTimeMillis())
        if (list.isNotEmpty() && list[0].url == url) {
            list[0] = entry
        } else {
            list.add(0, entry)
        }
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        write(c, KEY_HISTORY, list)
    }

    @Synchronized
    fun removeHistory(c: Context, url: String, time: Long) {
        val list = read(c, KEY_HISTORY)
        list.removeAll { it.url == url && it.time == time }
        write(c, KEY_HISTORY, list)
    }

    @Synchronized
    fun clearHistory(c: Context) {
        write(c, KEY_HISTORY, emptyList())
    }

    // ---- Bookmarks (newest first) ----

    @Synchronized
    fun bookmarks(c: Context): List<SavedPage> = read(c, KEY_BOOKMARKS)

    @Synchronized
    fun isBookmarked(c: Context, url: String): Boolean =
        read(c, KEY_BOOKMARKS).any { it.url == url }

    /** Adds the bookmark if missing, removes it if present. Returns true if it is now bookmarked. */
    @Synchronized
    fun toggleBookmark(c: Context, url: String, title: String): Boolean {
        val list = read(c, KEY_BOOKMARKS)
        val existing = list.indexOfFirst { it.url == url }
        val nowBookmarked: Boolean
        if (existing >= 0) {
            list.removeAt(existing)
            nowBookmarked = false
        } else {
            list.add(0, SavedPage(url, title, System.currentTimeMillis()))
            nowBookmarked = true
        }
        write(c, KEY_BOOKMARKS, list)
        return nowBookmarked
    }

    @Synchronized
    fun removeBookmark(c: Context, url: String) {
        val list = read(c, KEY_BOOKMARKS)
        list.removeAll { it.url == url }
        write(c, KEY_BOOKMARKS, list)
    }

    @Synchronized
    fun clearBookmarks(c: Context) {
        write(c, KEY_BOOKMARKS, emptyList())
    }

    // ---- Downloads (ids from Android's DownloadManager, newest first) ----

    @Synchronized
    fun downloadIds(c: Context): List<Long> {
        val out = ArrayList<Long>()
        try {
            val arr = JSONArray(prefs(c).getString(KEY_DOWNLOADS, "[]"))
            for (i in 0 until arr.length()) out.add(arr.getLong(i))
        } catch (e: Exception) {
        }
        return out
    }

    @Synchronized
    fun addDownload(c: Context, id: Long) {
        val ids = downloadIds(c).toMutableList()
        ids.add(0, id)
        saveDownloads(c, ids)
    }

    @Synchronized
    fun removeDownload(c: Context, id: Long) {
        val ids = downloadIds(c).toMutableList()
        ids.remove(id)
        saveDownloads(c, ids)
    }

    private fun saveDownloads(c: Context, ids: List<Long>) {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        prefs(c).edit().putString(KEY_DOWNLOADS, arr.toString()).apply()
    }
}
