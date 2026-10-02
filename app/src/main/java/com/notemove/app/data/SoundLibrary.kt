package com.notemove.app.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A free SoundFont the app can download into the SoundFont library. */
data class SoundPack(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val bytes: Long,
    val credit: String,
)

object SoundLibrary {
    val PACKS = listOf(
        SoundPack(
            "generaluser-gs", "GeneralUser GS",
            "Complete General MIDI library: 287 instruments (pianos, guitars, strings, brass, synths…) and 13 drum kits.",
            "https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/main/GeneralUser-GS.sf2", 32_319_396,
            "by S. Christian Collins · free to use in your music",
        ),
        SoundPack(
            "timgm6mb", "TimGM6mb",
            "Compact General MIDI library: 136 instruments and 8 drum kits in 6 MB.",
            "https://raw.githubusercontent.com/craffel/pretty-midi/main/pretty_midi/TimGM6mb.sf2", 5_994_284,
            "by Tim Brechbill · GPL v2",
        ),
    )

    /** Downloads [pack] to [dest], reporting progress 0..1. Throws on network errors or if the file isn't a SoundFont. */
    fun download(pack: SoundPack, dest: File, progress: (Float) -> Unit) {
        var url = URL(pack.url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) {
                url = URL(url, conn.getHeaderField("Location") ?: throw IOException("Bad redirect"))
                conn.disconnect()
                continue
            }
            if (code != 200) { conn.disconnect(); throw IOException("Server returned $code") }
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: pack.bytes
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val head = ByteArray(12)
        dest.inputStream().use { it.read(head) }
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "sfbk") {
            dest.delete()
            throw IOException("Downloaded file isn't a SoundFont")
        }
    }
}
