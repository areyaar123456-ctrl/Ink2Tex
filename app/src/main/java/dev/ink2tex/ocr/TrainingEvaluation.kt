package dev.ink2tex.ocr

object TrainingEvaluation {
    private val whitespace = Regex("\\s+")

    fun errors(expected: String, actual: String): Int {
        val a = expected.trim().replace(whitespace, " ")
        val b = actual.trim().replace(whitespace, " ")
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    previous[j + 1] + 1,
                    current[j] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            val buffer = previous
            previous = current
            current = buffer
        }
        return previous.last()
    }

    fun accept(baseline: List<Int>, candidate: List<Int>): Boolean =
        baseline.isNotEmpty() && baseline.size == candidate.size &&
            baseline.indices.all { candidate[it] <= baseline[it] } &&
            baseline.indices.any { candidate[it] < baseline[it] }
}
