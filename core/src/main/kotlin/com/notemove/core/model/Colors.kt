package com.notemove.core.model

/**
 * Track colours. Each entry pairs an ARGB value for the UI with the closest index in
 * Ableton Live's clip/track colour palette, so colours carry over into exported sets.
 */
object TrackColors {
    data class Entry(val argb: Long, val liveIndex: Int)

    val ALL = listOf(
        Entry(0xFFFF94A6, 0),   // salmon
        Entry(0xFFFFA529, 1),   // orange
        Entry(0xFFCC9927, 2),   // ochre
        Entry(0xFFF7F47C, 3),   // light yellow
        Entry(0xFFBFFB00, 4),   // lime
        Entry(0xFF1AFF2F, 5),   // green
        Entry(0xFF25FFA8, 6),   // mint
        Entry(0xFF5CFFE8, 7),   // cyan
        Entry(0xFF8BC5FF, 8),   // sky
        Entry(0xFF5480E4, 9),   // blue
        Entry(0xFF92A7FF, 10),  // periwinkle
        Entry(0xFFD86CE4, 11),  // violet
        Entry(0xFFE553A0, 12),  // magenta
        Entry(0xFFFF3636, 14),  // red
        Entry(0xFFF66C03, 15),  // deep orange
        Entry(0xFF00BFAF, 20),  // teal
    )

    fun argb(index: Int): Long = ALL[Math.floorMod(index, ALL.size)].argb
    fun liveIndex(index: Int): Int = ALL[Math.floorMod(index, ALL.size)].liveIndex
}
