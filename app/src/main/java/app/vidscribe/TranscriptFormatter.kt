package app.vidscribe

data class Paragraph(val start: Double, val text: String)

data class Transcript(val title: String, val source: String, val paragraphs: List<Paragraph>) {
    val wordCount: Int get() = paragraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }
}

/**
 * Turns the raw timed segments into readable paragraphs:
 * a new paragraph starts at a natural pause after a finished sentence,
 * or when the current one gets long. Each paragraph keeps its start time.
 */
object TranscriptFormatter {

    private val sentenceEnd = Regex("[.?!…][\"')\\]]*(\\s|$)")

    fun paragraphs(segments: List<Segment>): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        val sb = StringBuilder()
        var start = 0.0
        var lastEnd = 0.0
        var sentences = 0

        for (seg in segments) {
            val t = seg.text.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) continue

            if (sb.isNotEmpty()) {
                val gap = seg.start - lastEnd
                val endsSentence = sentenceEnd.containsMatchIn(sb.takeLast(4))
                val shouldBreak =
                    (endsSentence && ((gap >= 1.2 && sentences >= 2) || sentences >= 5 || sb.length >= 650)) ||
                    sb.length >= 1100
                if (shouldBreak) {
                    out += Paragraph(start, sb.toString())
                    sb.clear()
                    sentences = 0
                }
            }
            if (sb.isEmpty()) start = seg.start else sb.append(' ')
            sb.append(t)
            sentences += sentenceEnd.findAll(t).count()
            lastEnd = seg.end
        }
        if (sb.isNotEmpty()) out += Paragraph(start, sb.toString())
        return out
    }

    fun stamp(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }

    fun asPlainText(t: Transcript, withTimes: Boolean = true): String = buildString {
        appendLine(t.title)
        appendLine(t.source)
        appendLine()
        for (p in t.paragraphs) {
            if (withTimes) appendLine("[${stamp(p.start)}]")
            appendLine(p.text)
            appendLine()
        }
    }.trimEnd() + "\n"
}
