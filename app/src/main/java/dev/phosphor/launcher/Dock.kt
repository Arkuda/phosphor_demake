package dev.phosphor.launcher

import org.json.JSONArray

class Dock(private val host: MainActivity) {
    private var cachedApps: List<AppEntry>? = null
    private var cachedSource: String? = null
    private var slots: List<String?> = List(SLOT_COUNT) { null }
    private var cachedEntries: List<AppEntry?> = List(SLOT_COUNT) { null }

    fun entries(): List<AppEntry?> {
        val apps = host.device.apps
        var source = host.prefs.getString(PREF_KEY, null)
        if (cachedApps === apps && cachedSource == source) return cachedEntries

        if (source == null && apps.isNotEmpty()) {
            // StringSet pins had no defined order; migrate deterministically, retaining selections.
            val pins = host.prefs.getStringSet("pins", emptySet()).orEmpty().sorted().take(SLOT_COUNT)
            slots = defaults(apps, pins)
            source = encode(slots)
            host.prefs.edit().putString(PREF_KEY, source).remove("pins").apply()
        } else {
            slots = decode(source)
        }
        val byComponent = apps.associateBy { it.component.flattenToString() }
        cachedEntries = slots.map { key -> key?.let { byComponent[it] } }
        cachedApps = apps
        cachedSource = source
        return cachedEntries
    }

    fun settings() {
        val current = entries()
        host.choose("dock apps · five ordered slots", current.mapIndexed { index, app ->
            "${index + 1} · ${app?.label ?: "empty"}"
        } + "reset to defaults") { index ->
            if (index < SLOT_COUNT) chooseSlot(index)
            else {
                val apps = host.device.apps
                if (apps.isEmpty()) host.toast("Apps are still loading")
                else save(defaults(apps))
            }
        }
    }

    fun chooseSlot(index: Int) {
        if (index !in 0 until SLOT_COUNT) return
        entries()
        val apps = host.device.apps
        if (apps.isEmpty()) {
            host.toast("Apps are still loading")
            return
        }
        host.choose("dock slot ${index + 1}", listOf("empty · remove app") + apps.map {
            "${it.label} · ${it.component.flattenToShortString()}"
        }) { selected ->
            assign(index, if (selected == 0) null else apps[selected - 1].component.flattenToString())
        }
    }

    fun isPinned(app: AppEntry): Boolean {
        entries()
        return app.component.flattenToString() in slots
    }

    fun togglePin(app: AppEntry) {
        val current = entries()
        val key = app.component.flattenToString()
        val pinned = slots.indexOf(key)
        if (pinned >= 0) {
            assign(pinned, null)
            return
        }
        val empty = current.indexOfFirst { it == null }
        if (empty >= 0) assign(empty, key)
        else host.choose("pin ${app.label} · replace slot", current.mapIndexed { index, entry ->
            "${index + 1} · ${entry?.label ?: "empty"}"
        }) { index -> assign(index, key) }
    }

    private fun assign(index: Int, key: String?) {
        entries()
        val next = slots.toMutableList()
        val previous = if (key == null) -1 else next.indexOf(key)
        if (previous >= 0 && previous != index) next[previous] = next[index]
        next[index] = key
        save(next)
    }

    private fun save(next: List<String?>) {
        host.prefs.edit().putString(PREF_KEY, encode(next)).apply()
        // Rebuild even if the repository list itself has not changed.
        cachedApps = null
        host.redraw()
    }

    private fun defaults(apps: List<AppEntry>, selected: List<String> = emptyList()): List<String?> {
        val result = selected.distinct().take(SLOT_COUNT).toMutableList()
        for (name in DEFAULT_NAMES) {
            if (result.size == SLOT_COUNT) break
            val app = apps.firstOrNull {
                it.label.contains(name, ignoreCase = true) && it.component.flattenToString() !in result
            }
            if (app != null) result.add(app.component.flattenToString())
        }
        for (app in apps) {
            if (result.size == SLOT_COUNT) break
            val key = app.component.flattenToString()
            if (key !in result) result.add(key)
        }
        return List(SLOT_COUNT) { result.getOrNull(it) }
    }

    private fun decode(source: String?): List<String?> {
        if (source == null) return List(SLOT_COUNT) { null }
        return try {
            val array = JSONArray(source)
            val seen = HashSet<String>()
            List(SLOT_COUNT) { index ->
                val key = if (array.isNull(index)) null else array.optString(index).takeIf { it.isNotEmpty() }
                key?.takeIf { seen.add(it) }
            }
        } catch (_: Exception) {
            List(SLOT_COUNT) { null }
        }
    }

    private fun encode(entries: List<String?>): String {
        val array = JSONArray()
        entries.forEach { array.put(it ?: org.json.JSONObject.NULL) }
        return array.toString()
    }

    private companion object {
        const val SLOT_COUNT = 5
        const val PREF_KEY = "dock_apps"
        val DEFAULT_NAMES = listOf("phone", "messages", "chrome", "camera", "maps", "browser", "music", "files", "settings")
    }
}
