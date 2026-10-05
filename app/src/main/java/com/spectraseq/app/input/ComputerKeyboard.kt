package com.spectraseq.app.input

import android.view.KeyEvent

/**
 * Ableton Live's Computer MIDI Keyboard: the home row plays a piano octave and a bit
 * (A = C, W = C#, S = D … K = C one octave up, through ' = F), Z / X shift the octave and
 * C / V change the velocity.
 *
 * Keys are matched by physical position (Linux scan codes) when the keyboard reports them, so the
 * layout works the same on QWERTZ / AZERTY keyboards; otherwise by key code.
 */
object ComputerKeyboard {
    /** Semitone offset from the octave's C for each note key, by scan code (physical position). */
    private val byScanCode = mapOf(
        30 to 0, 17 to 1, 31 to 2, 18 to 3, 32 to 4, 33 to 5, 20 to 6, 34 to 7, 21 to 8, 35 to 9,
        22 to 10, 36 to 11, 37 to 12, 24 to 13, 38 to 14, 25 to 15, 39 to 16, 40 to 17,
    )
    private val byKeyCode = mapOf(
        KeyEvent.KEYCODE_A to 0, KeyEvent.KEYCODE_W to 1, KeyEvent.KEYCODE_S to 2, KeyEvent.KEYCODE_E to 3,
        KeyEvent.KEYCODE_D to 4, KeyEvent.KEYCODE_F to 5, KeyEvent.KEYCODE_T to 6, KeyEvent.KEYCODE_G to 7,
        KeyEvent.KEYCODE_Y to 8, KeyEvent.KEYCODE_H to 9, KeyEvent.KEYCODE_U to 10, KeyEvent.KEYCODE_J to 11,
        KeyEvent.KEYCODE_K to 12, KeyEvent.KEYCODE_O to 13, KeyEvent.KEYCODE_L to 14, KeyEvent.KEYCODE_P to 15,
        KeyEvent.KEYCODE_SEMICOLON to 16, KeyEvent.KEYCODE_APOSTROPHE to 17,
    )

    enum class Control { OCTAVE_DOWN, OCTAVE_UP, VELOCITY_DOWN, VELOCITY_UP }

    private val controlsByScan = mapOf(44 to Control.OCTAVE_DOWN, 45 to Control.OCTAVE_UP, 46 to Control.VELOCITY_DOWN, 47 to Control.VELOCITY_UP)
    private val controlsByKey = mapOf(
        KeyEvent.KEYCODE_Z to Control.OCTAVE_DOWN, KeyEvent.KEYCODE_X to Control.OCTAVE_UP,
        KeyEvent.KEYCODE_C to Control.VELOCITY_DOWN, KeyEvent.KEYCODE_V to Control.VELOCITY_UP,
    )

    private fun scanKnown(e: KeyEvent) = e.scanCode in 1..255 && (e.scanCode in byScanCode || e.scanCode in controlsByScan || e.scanCode in otherScanCodes)
    // Letters that aren't note keys (Q R I and the bottom row past V), so a known scan code with no note means "no note".
    private val otherScanCodes = setOf(16, 19, 23, 48, 49, 50)

    /** Semitone offset (0..17) if [e] is a note key, else null. */
    fun noteOffset(e: KeyEvent): Int? = if (scanKnown(e)) byScanCode[e.scanCode] else byKeyCode[e.keyCode]

    fun control(e: KeyEvent): Control? = if (scanKnown(e)) controlsByScan[e.scanCode] else controlsByKey[e.keyCode]

    /** Stable id for a physical key, used to release exactly the note a key started. */
    fun keyId(e: KeyEvent): Int = if (e.scanCode > 0) e.scanCode else 1000 + e.keyCode

    /** Live's velocity steps for C / V. */
    val VELOCITIES = intArrayOf(1, 20, 40, 60, 80, 100, 120, 127)

    fun nextVelocity(v: Int, up: Boolean): Int =
        if (up) VELOCITIES.firstOrNull { it > v } ?: 127 else VELOCITIES.lastOrNull { it < v } ?: 1
}
