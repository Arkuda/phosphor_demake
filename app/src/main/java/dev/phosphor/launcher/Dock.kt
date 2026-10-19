package dev.phosphor.launcher

import android.content.ComponentName
import org.json.JSONArray
import org.json.JSONObject

class Dock(private val host: MainActivity) {
    private var cachedApps: List<AppEntry>? = null
    private var cachedSource: String? = null
    private var slots: List<String?> = List(SLOT_COUNT) { null }
    private var cachedEntries: List<AppEntry?> = List(SLOT_COUNT) { null }
    private var namesSource: String? = null
    private var namesApps: List<AppEntry>? = null
    private var customNames: JSONObject? = null
    private val displayNames = HashMap<ComponentName, String>()

    fun displayName(app: AppEntry): String {
        val source = host.prefs.getString(NAME_KEY, null)
        val apps = host.device.apps
        if (source != namesSource || namesApps !== apps) {
            namesSource = source
            namesApps = apps
            customNames = source?.let { runCatching { JSONObject(it) }.getOrNull() }
            displayNames.clear()
        }
        return displayNames.getOrPut(app.component) {
            customNames?.optString(app.component.flattenToString())?.takeIf { it.isNotBlank() }
                ?: app.label.lowercase()
        }
    }

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
        host.choose(L10n.text(R.string.menus_dock_title), current.mapIndexed { index, app ->
            "${index + 1} · ${app?.let(::displayName) ?: L10n.text(R.string.common_empty)}"
        } + L10n.text(R.string.menus_reset_defaults)) { index ->
            if (index < SLOT_COUNT) chooseSlot(index)
            else {
                val apps = host.device.apps
                if (apps.isEmpty()) host.toast(L10n.text(R.string.menus_apps_loading))
                else {
                    host.prefs.edit().remove(NAME_KEY).apply()
                    save(defaults(apps))
                }
            }
        }
    }

    fun chooseSlot(index: Int) {
        if (index !in 0 until SLOT_COUNT) return
        val app = entries()[index]
        if (app == null) {
            pickSlot(index)
            return
        }
        host.choose(L10n.text(R.string.menus_dock_slot_title,index+1,displayName(app)), listOf(L10n.text(R.string.menus_choose_app),L10n.text(R.string.menus_rename_label),L10n.text(R.string.menus_empty_slot))) { action ->
            when (action) {
                0 -> pickSlot(index)
                1 -> host.inputLine(L10n.text(R.string.menus_dock_label), displayName(app), L10n.text(R.string.menus_blank_label,app.label)) { value ->
                    val names = runCatching { JSONObject(host.prefs.getString(NAME_KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }
                    val key = app.component.flattenToString()
                    val label = value.trim()
                    if (label.isEmpty()) names.remove(key) else names.put(key, label)
                    host.prefs.edit().putString(NAME_KEY, names.toString()).apply()
                    host.redraw()
                }
                2 -> assign(index, null)
            }
        }
    }

    private fun pickSlot(index: Int) {
        val current = entries()
        host.pickApp(L10n.text(R.string.menus_dock_slot,index+1), current[index], allowEmpty = true) { app ->
            assign(index, app?.component?.flattenToString())
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
        else host.choose(L10n.text(R.string.menus_replace_slot,app.label), current.mapIndexed { index, entry ->
            "${index + 1} · ${entry?.let(::displayName) ?: L10n.text(R.string.common_empty)}"
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
        const val NAME_KEY = "dock_names"
        val DEFAULT_NAMES = listOf("phone", "messages", "chrome", "camera", "maps", "browser", "music", "files", "settings")
    }
}
