package dev.phosphor.launcher

import org.json.JSONArray

/** Independent from the five-slot dock; order follows the user's selection order. */
class Favorites(private val host: MainActivity) {
    private var source: String? = null
    private var keys: List<String> = emptyList()
    private var resolvedKeys: List<String>? = null
    private var resolvedApps: List<AppEntry>? = null
    private var resolved: List<AppEntry> = emptyList()

    private fun load() {
        val raw = host.prefs.getString(PREF_KEY, null)
        if (raw == source) return
        source = raw
        keys = if (raw == null) emptyList() else try {
            val array = JSONArray(raw)
            List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun entries(): List<AppEntry> {
        load()
        val apps = host.device.apps
        if (resolvedKeys === keys && resolvedApps === apps) return resolved
        val installed = apps.associateBy { it.component.flattenToString() }
        resolved = keys.mapNotNull { installed[it] }
        resolvedKeys = keys
        resolvedApps = apps
        return resolved
    }

    fun isFavorite(app: AppEntry): Boolean {
        load()
        return app.component.flattenToString() in keys
    }

    fun toggle(app: AppEntry) {
        load()
        val key = app.component.flattenToString()
        save(if (key in keys) keys.filterNot { it == key } else keys + key)
    }

    fun edit() {
        load()
        if (host.device.apps.isEmpty()) {
            host.toast("Apps are still loading")
            return
        }
        host.pickApps("favorite apps", keys) { selected -> save(selected) }
    }

    private fun save(next: List<String>) {
        host.prefs.edit().putString(PREF_KEY, JSONArray(next.distinct()).toString()).apply()
        host.redraw()
    }

    private companion object {
        const val PREF_KEY = "favorite_apps"
    }
}
