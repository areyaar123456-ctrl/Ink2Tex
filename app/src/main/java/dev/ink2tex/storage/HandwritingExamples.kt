package dev.ink2tex.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

data class HandwritingExample(val documentId: String, val source: String, val transcription: String)

data class HandwritingProfile(
    val enabled: Boolean = true,
    val examples: List<HandwritingExample> = emptyList()
) {
    fun forScan(sources: List<String>): List<HandwritingExample> =
        if (!enabled) emptyList()
        else examples.filter { it.source !in sources && File(it.source).isFile }
}

class HandwritingExamples(private val context: Context) {
    private val saved = stringPreferencesKey("handwriting_examples")
    private val enabled = booleanPreferencesKey("handwriting_examples_enabled")

    val profile = context.settingsStore.data.map {
        HandwritingProfile(it[enabled] ?: true, decode(it[saved]))
    }

    suspend fun save(example: HandwritingExample) {
        require(example.transcription.isNotBlank() && example.transcription.length <= MAX_TEXT) {
            "Use a short snip with up to $MAX_TEXT characters of checked transcription."
        }
        context.settingsStore.edit { preferences ->
            val others = decode(preferences[saved]).filter {
                it.documentId != example.documentId && it.source != example.source
            }
            preferences[saved] = encode((others + example).takeLast(MAX_EXAMPLES))
        }
    }

    suspend fun remove(documentId: String) {
        context.settingsStore.edit { preferences ->
            preferences[saved] = encode(decode(preferences[saved]).filterNot { it.documentId == documentId })
        }
    }

    suspend fun enable(value: Boolean) {
        context.settingsStore.edit { it[enabled] = value }
    }

    private fun decode(value: String?): List<HandwritingExample> {
        if (value == null) return emptyList()
        return try {
            val entries = JSONArray(value)
            (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                HandwritingExample(entry.getString("document"), entry.getString("source"), entry.getString("text"))
            }.takeLast(MAX_EXAMPLES)
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun encode(examples: List<HandwritingExample>): String = JSONArray().apply {
        examples.forEach { example ->
            put(JSONObject().put("document", example.documentId).put("source", example.source)
                .put("text", example.transcription))
        }
    }.toString()

    companion object {
        const val MAX_EXAMPLES = 2
        const val MAX_TEXT = 400
    }
}
