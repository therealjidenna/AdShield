package com.adshield.app

import android.content.Context
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

object Blocklist {

    private const val PREFS = "adshield"
    private const val KEY_CUSTOM = "custom"

    @Volatile
    private var domains: Set<String> = emptySet()

    /** Loads the bundled list (assets/blocklist.txt) plus the user's own domains. */
    fun load(context: Context) {
        val set = HashSet<String>()
        try {
            context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { addLine(it, set) }
            }
        } catch (e: Exception) {
            // no bundled list found, custom domains still work
        }
        set.addAll(customDomains(context))
        domains = set
    }

    private fun addLine(raw: String, set: MutableSet<String>) {
        val line = raw.substringBefore('#').trim().lowercase()
        if (line.isEmpty()) return
        // supports plain "domain.com" lines and hosts-file lines like "0.0.0.0 domain.com"
        val token = line.split(Regex("\\s+")).last()
        if (token == "localhost" || token == "0.0.0.0" || token == "127.0.0.1" ||
            token == "broadcasthost" || token == "local"
        ) return
        set.add(token)
    }

    fun isBlocked(name: String): Boolean {
        val set = domains
        val parts = name.lowercase().trimEnd('.').split('.')
        if (parts.size < 2) return false
        // checks "a.b.example.com", "b.example.com", "example.com"
        for (i in 0 until parts.size - 1) {
            if (set.contains(parts.subList(i, parts.size).joinToString("."))) return true
        }
        return false
    }

    fun customDomains(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CUSTOM, emptySet())?.toSet() ?: emptySet()
    }

    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        s = s.removePrefix("http://").removePrefix("https://")
        s = s.substringBefore('/').substringBefore('?').substringBefore(':')
        s = s.removePrefix("www.")
        return if (Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(s)) s else null
    }

    fun addCustom(context: Context, domain: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = customDomains(context).toMutableSet()
        set.add(domain)
        prefs.edit().putStringSet(KEY_CUSTOM, set).apply()
        load(context)
    }

    fun removeCustom(context: Context, domain: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = customDomains(context).toMutableSet()
        set.remove(domain)
        prefs.edit().putStringSet(KEY_CUSTOM, set).apply()
        load(context)
    }
}

object AdStats {
    val total = AtomicInteger(0)
    val blocked = AtomicInteger(0)

    /** Requests blocked inside the built-in browser (separate from the VPN's DNS blocking). */
    val pageBlocked = AtomicInteger(0)

    private val recent = ConcurrentLinkedDeque<String>()

    fun addBlocked(name: String) {
        blocked.incrementAndGet()
        if (recent.peekFirst() != name) {
            recent.addFirst(name)
            while (recent.size > 30) recent.pollLast()
        }
    }

    fun recentList(): List<String> = recent.toList()

    fun reset() {
        total.set(0)
        blocked.set(0)
        recent.clear()
    }
}
