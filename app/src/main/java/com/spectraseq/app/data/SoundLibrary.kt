package com.spectraseq.app.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A free SoundFont the app can download into the SoundFont library.
 * [urls] are tried in order: SpectraSeq's own copy first, then the original source as a fallback.
 */
data class SoundPack(
    val id: String,
    val name: String,
    val description: String,
    val urls: List<String>,
    val bytes: Long,
    val credit: String,
)

object SoundLibrary {
    /** SpectraSeq's own copies (release "soundfonts-v1" of catacombius/noteMove, with their licence files). */
    private const val MIRROR = "https://github.com/catacombius/noteMove/releases/download/soundfonts-v1/"

    val PACKS = listOf(
        SoundPack(
            "generaluser-gs", "GeneralUser GS",
            "Complete General MIDI library: 287 instruments (pianos, guitars, strings, brass, synths…) and 13 drum kits.",
            listOf(
                MIRROR + "GeneralUser-GS.sf2",
                "https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/main/GeneralUser-GS.sf2",
            ),
            32_319_396,
            "by S. Christian Collins · free to use in your music",
        ),
        SoundPack(
            "timgm6mb", "TimGM6mb",
            "Compact General MIDI library: 136 instruments and 8 drum kits in 6 MB.",
            listOf(
                MIRROR + "TimGM6mb.sf2",
                "https://raw.githubusercontent.com/craffel/pretty-midi/main/pretty_midi/TimGM6mb.sf2",
            ),
            5_994_284,
            "by Tim Brechbill · GPL v2",
        ),
    )

    /**
     * Downloads [pack] to [dest], reporting progress 0..1. Tries each of the pack's URLs in turn and throws only
     * if none of them gives a SoundFont.
     */
    fun download(pack: SoundPack, dest: File, progress: (Float) -> Unit) {
        var last: IOException? = null
        for (url in pack.urls) {
            try {
                downloadFrom(url, pack.bytes, dest, progress)
                return
            } catch (e: IOException) {
                dest.delete()
                last = e
                progress(0f)
            }
        }
        throw last ?: IOException("No download address")
    }

    private fun downloadFrom(address: String, expectedBytes: Long, dest: File, progress: (Float) -> Unit) {
        var url = URL(address)
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
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
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
