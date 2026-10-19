package dev.phosphor.launcher

import org.json.JSONObject

/** Display-only metadata; catalogue IDs, grouping keys and original text stay untouched. */
object ArtLocale {
    private data class Translation(val name: String, val note: String)
    private class Metadata(val pieces: Map<String, Translation>, val categories: Map<String, String>)

    private val russian: Metadata by lazy {
        val json = L10n.context.assets.open("ascii/catalog.ru.json").bufferedReader().use { JSONObject(it.readText()) }
        val entries = json.getJSONObject("pieces")
        val pieces = HashMap<String, Translation>(entries.length())
        val ids = entries.keys()
        while (ids.hasNext()) {
            val id = ids.next()
            val entry = entries.getJSONArray(id)
            pieces[id] = Translation(entry.getString(0), entry.getString(1))
        }
        val sourceCategories = json.getJSONObject("categories")
        val categories = HashMap<String, String>(sourceCategories.length())
        val keys = sourceCategories.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            categories[key] = sourceCategories.getString(key)
        }
        Metadata(pieces, categories)
    }

    fun name(piece: ArtPiece): String = if (L10n.isRussian) russian.pieces[piece.id]?.name ?: piece.name else piece.name
    fun note(piece: ArtPiece): String = if (L10n.isRussian) russian.pieces[piece.id]?.note ?: piece.note else piece.note
    fun category(key: String): String = if (L10n.isRussian) russian.categories[key] ?: key else key
}
