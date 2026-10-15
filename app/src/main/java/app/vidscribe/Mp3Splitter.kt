package app.vidscribe

import java.io.File
import java.io.RandomAccessFile

/**
 * Splits a constant-bitrate MP3 (which is what the app produces) into parts small enough for
 * the transcription service, cutting only on MP3 frame boundaries. Returns each part with its
 * start time in seconds, so timestamps stay correct across the whole recording.
 */
object Mp3Splitter {

    fun split(file: File, kbps: Int, maxBytes: Long): List<Pair<File, Double>> {
        val len = file.length()
        if (len <= maxBytes) return listOf(file to 0.0)

        val bytesPerSec = kbps * 125.0
        val target = (maxBytes * 0.95).toLong()
        val parts = mutableListOf<Pair<File, Double>>()

        RandomAccessFile(file, "r").use { raf ->
            var start = 0L
            var index = 0
            val buf = ByteArray(1 shl 16)
            while (start < len) {
                var end = minOf(start + target, len)
                if (end < len) end = nextFrame(raf, end, len)
                val part = File(file.parentFile, "part_${index}.mp3")
                part.outputStream().use { out ->
                    raf.seek(start)
                    var left = end - start
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                parts += part to start / bytesPerSec
                start = end
                index++
            }
        }
        return parts
    }

    /** First MPEG audio Layer III frame header at or after [from]. */
    private fun nextFrame(raf: RandomAccessFile, from: Long, len: Long): Long {
        val window = ByteArray(16 * 1024)
        raf.seek(from)
        val n = raf.read(window)
        for (i in 0 until n - 3) {
            val b1 = window[i].toInt() and 0xFF
            val b2 = window[i + 1].toInt() and 0xFF
            val b3 = window[i + 2].toInt() and 0xFF
            val isSync = b1 == 0xFF && (b2 and 0xE0) == 0xE0
            val layer3 = (b2 shr 1) and 0x3 == 1
            val bitrateIdx = (b3 shr 4) and 0xF
            val rateIdx = (b3 shr 2) and 0x3
            if (isSync && layer3 && bitrateIdx in 1..14 && rateIdx != 3) return from + i
        }
        return minOf(from, len)
    }
}
