package dev.ink2tex.ocr

object TrainingEvaluation {
    private val whitespace = Regex("\\s+")
    private val math = Regex("""(?s)(?<!\\)\$\$(.+?)(?<!\\)\$\$|(?<![\\$])\$(?!\$)(.+?)(?<!\\)\$(?!\$)""")
    private val mathToken = Regex("""\\[A-Za-z]+|\\.|[^\s]""")

    fun errors(expected: String, actual: String): Int =
        distance(expected.trim().replace(whitespace, " "), actual.trim().replace(whitespace, " "))

    fun mathErrors(expected: String, actual: String): Int =
        distance(formulas(expected), formulas(actual))

    private fun formulas(text: String): String = math.findAll(text).joinToString("\n") { expression ->
        val body = expression.groups[1]?.value ?: expression.groups[2]?.value.orEmpty()
        mathToken.findAll(body).joinToString(" ") { it.value }
    }

    private fun distance(a: String, b: String): Int {
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

    fun accept(
        baseline: List<Int>, candidate: List<Int>, baselineMath: List<Int>, candidateMath: List<Int>
    ): Boolean {
        val counts = baseline.size
        if (counts == 0 || listOf(candidate.size, baselineMath.size, candidateMath.size).any { it != counts }) return false
        return baseline.indices.all { candidate[it] <= baseline[it] && candidateMath[it] <= baselineMath[it] } &&
            baseline.indices.any { candidate[it] < baseline[it] || candidateMath[it] < baselineMath[it] }
    }
}
