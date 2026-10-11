package com.spectraseq.core.model

import kotlinx.serialization.Serializable

/** Scales use the same names as Ableton Live so they survive the trip into a Live Set. */
@Serializable
enum class Scale(val liveName: String, val intervals: IntArray) {
    MAJOR("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    DORIAN("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    MIXOLYDIAN("Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    LYDIAN("Lydian", intArrayOf(0, 2, 4, 6, 7, 9, 11)),
    PHRYGIAN("Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    LOCRIAN("Locrian", intArrayOf(0, 1, 3, 5, 6, 8, 10)),
    HARMONIC_MINOR("Harmonic Minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    MELODIC_MINOR("Melodic Minor", intArrayOf(0, 2, 3, 5, 7, 9, 11)),
    MAJOR_PENTATONIC("Major Pentatonic", intArrayOf(0, 2, 4, 7, 9)),
    MINOR_PENTATONIC("Minor Pentatonic", intArrayOf(0, 3, 5, 7, 10)),
    MINOR_BLUES("Minor Blues", intArrayOf(0, 3, 5, 6, 7, 10)),
    WHOLE_TONE("Whole Tone", intArrayOf(0, 2, 4, 6, 8, 10)),
    HUNGARIAN_MINOR("Hungarian Minor", intArrayOf(0, 2, 3, 6, 7, 8, 11)),
    HIROJOSHI("Hirojoshi", intArrayOf(0, 2, 3, 7, 8)),
    CHROMATIC("Chromatic", intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));

    val label: String get() = liveName

    fun contains(pitch: Int, root: Int): Boolean = intervals.contains(((pitch - root) % 12 + 12) % 12)

    /** Pitch of the n-th scale degree above the root in [baseOctave] (Ableton octave naming, C3 = 60). */
    fun degreeToPitch(degree: Int, root: Int, baseOctave: Int): Int {
        val size = intervals.size
        val oct = Math.floorDiv(degree, size)
        val idx = Math.floorMod(degree, size)
        return (baseOctave + 2 + oct) * 12 + root + intervals[idx]
    }

    companion object {
        val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun noteName(pitch: Int): String = NOTE_NAMES[Math.floorMod(pitch, 12)] + (Math.floorDiv(pitch, 12) - 2)
    }
}
