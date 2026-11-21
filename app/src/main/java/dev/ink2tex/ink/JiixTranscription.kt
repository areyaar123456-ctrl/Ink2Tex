package dev.ink2tex.ink

import dev.ink2tex.format.MathLayout
import org.json.JSONObject

internal object JiixTranscription {
    fun parse(value: String, strokeCount: Int): StrokeResult {
        val root = JSONObject(value)
        require(root.optString("type") == "Raw Content") { "Unexpected MyScript result format." }
        val elements = root.optJSONArray("elements")
        val paragraphs = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val covered = BooleanArray(strokeCount)
        var incomplete = false
        if (elements != null) for (index in 0 until elements.length()) {
            val block = elements.getJSONObject(index)
            val text = block.optString("label").trim()
            val ranges = block.optJSONArray("range")
            if (ranges != null) for (r in 0 until ranges.length()) {
                val item = ranges.getJSONObject(r)
                val from = item.getJSONObject("from").optInt("stroke", -1)
                val to = item.getJSONObject("to").optInt("stroke", -1)
                require(from in covered.indices && to in from..covered.lastIndex) { "Invalid MyScript stroke ranges." }
                for (position in from..to) covered[position] = true
            }
            when {
                block.optString("type") == "Text" && text.isNotBlank() -> paragraphs += MathLayout.displayStandalone(text)
                block.optString("type") == "Math" && text.isNotBlank() -> paragraphs += "\$\$\n$text\n\$\$"
                else -> {
                    paragraphs += "[Untranscribed ink in block ${index + 1}]"
                    warnings += "MyScript block ${index + 1} needs review."
                    incomplete = true
                }
            }
        }
        if (covered.any { !it }) {
            warnings += "MyScript left ink without a mapped text or math block. Check the original page."
            incomplete = true
        }
        if (paragraphs.isEmpty() && strokeCount > 0) paragraphs += "[Untranscribed ink]"
        return StrokeResult(paragraphs.joinToString("\n\n"), warnings, incomplete)
    }
}
