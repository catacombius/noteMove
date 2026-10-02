package com.notemove.app.ui

import android.view.KeyEvent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import com.notemove.app.input.ComputerKeyboard
import com.notemove.core.model.ClipOps
import com.notemove.core.model.Scale
import com.notemove.core.model.TrackKind

/**
 * Hardware keyboard support (Bluetooth / USB keyboards, keyboard covers, DeX): Live's computer MIDI
 * keyboard plus the usual Live shortcuts. Key events come from [MainActivity.dispatchKeyEvent] before
 * Compose sees them, so arrows / Space / Tab never move UI focus around.
 */
class KeyboardShortcuts(private val vm: StudioViewModel, private val back: () -> Unit) {
    /** Physical key -> pitch it started, so octave changes never leave notes hanging. */
    private val held = HashMap<Int, Int>()

    fun releaseAll() {
        held.values.forEach(vm::padUp)
        held.clear()
    }

    fun handle(e: KeyEvent): Boolean {
        if (TextInputGuard.focused > 0) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (!down && e.action != KeyEvent.ACTION_UP) return false
        val first = down && e.repeatCount == 0
        if (!down) held.remove(ComputerKeyboard.keyId(e))?.let { vm.padUp(it); return true }
        if (e.keyCode == KeyEvent.KEYCODE_ESCAPE) { if (first) back(); return true }
        val ui = vm.ui.value
        if (ui.project == null) return false
        val cmd = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        if (e.isAltPressed) return false

        if (cmd) {
            val action: (() -> Unit) = when (e.keyCode) {
                KeyEvent.KEYCODE_Z -> if (shift) vm::redo else vm::undo
                KeyEvent.KEYCODE_Y -> vm::redo
                KeyEvent.KEYCODE_C -> if (shift) vm::capture else ::copy
                KeyEvent.KEYCODE_X -> ::cut
                KeyEvent.KEYCODE_V -> ::paste
                KeyEvent.KEYCODE_D -> ::duplicate
                KeyEvent.KEYCODE_A -> vm::selectAllNotes
                KeyEvent.KEYCODE_S -> { { vm.flushSave(); vm.toast("Saved") } }
                KeyEvent.KEYCODE_U -> ::quantize
                KeyEvent.KEYCODE_1 -> { { stepGrid(finer = true) } }
                KeyEvent.KEYCODE_2 -> { { stepGrid(finer = false) } }
                KeyEvent.KEYCODE_T -> { { vm.addTrack(if (shift) TrackKind.DRUMS else TrackKind.SYNTH) } }
                KeyEvent.KEYCODE_E -> { { vm.requestOverlay(Overlay.EXPORT) } }
                KeyEvent.KEYCODE_COMMA -> { { vm.requestOverlay(Overlay.SETTINGS) } }
                else -> return false
            }
            val repeatable = e.keyCode == KeyEvent.KEYCODE_Z || e.keyCode == KeyEvent.KEYCODE_Y
            if (first || (down && repeatable)) action()
            return true
        }

        when (e.keyCode) {
            KeyEvent.KEYCODE_SPACE -> { if (first) vm.togglePlay(); return true }
            KeyEvent.KEYCODE_F9 -> { if (first) vm.toggleRecord(); return true }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> { if (first) delete(); return true }
            KeyEvent.KEYCODE_TAB -> {
                if (first) {
                    val all = Panel.entries
                    vm.setPanel(all[Math.floorMod(all.indexOf(ui.panel) + if (shift) -1 else 1, all.size)])
                }
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (first) {
                    if (shift) vm.launchScene(ui.selectedScene)
                    else ui.track?.let { t -> if (t.clips[ui.selectedScene] != null) vm.launchClip(t.id, ui.selectedScene) else vm.createClip(t.id, ui.selectedScene) }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) arrow(e.keyCode, shift)
                return true
            }
            KeyEvent.KEYCODE_M -> if (!shift) { if (first) vm.toggleComputerKeyboard(); return true }
        }

        // Computer MIDI keyboard
        val keys = vm.computerKeys.value
        if (!keys.enabled || shift) return false
        ComputerKeyboard.control(e)?.let { c ->
            if (first) when (c) {
                ComputerKeyboard.Control.OCTAVE_DOWN -> vm.shiftKeyboardOctave(-1)
                ComputerKeyboard.Control.OCTAVE_UP -> vm.shiftKeyboardOctave(1)
                ComputerKeyboard.Control.VELOCITY_DOWN -> vm.stepKeyboardVelocity(up = false)
                ComputerKeyboard.Control.VELOCITY_UP -> vm.stepKeyboardVelocity(up = true)
            }
            return true
        }
        val offset = ComputerKeyboard.noteOffset(e) ?: return false
        if (first) {
            val id = ComputerKeyboard.keyId(e)
            val pitch = vm.keyboardBaseNote() + offset
            if (id !in held && pitch in 0..127) {
                held[id] = pitch
                vm.padDown(pitch, keys.velocity)
            }
        }
        return true
    }

    private fun copy() {
        val ui = vm.ui.value
        if (ui.noteSelection.isNotEmpty() || (ui.panel == Panel.EDIT && ui.clip != null)) vm.copyNotes()
        else if (ui.clipSelection.isNotEmpty()) vm.copySelectedClips()
        else ui.clip?.let { vm.copyNotes() }
    }

    private fun cut() {
        val ui = vm.ui.value
        when {
            ui.noteSelection.isNotEmpty() -> { vm.copyNotes(); vm.deleteSelectedNotes() }
            ui.clipSelection.isNotEmpty() -> { vm.copySelectedClips(); vm.deleteSelectedClips() }
        }
    }

    private fun paste() {
        val ui = vm.ui.value
        if (ui.panel == Panel.SESSION || ui.clipSelection.isNotEmpty()) vm.pasteClips() else vm.pasteNotes()
    }

    private fun duplicate() {
        val ui = vm.ui.value
        when {
            ui.noteSelection.isNotEmpty() -> vm.duplicateSelectedNotes()
            ui.clipSelection.isNotEmpty() -> vm.duplicateSelectedClips()
            else -> ui.track?.let { vm.duplicateClip(it.id, ui.selectedScene) }
        }
    }

    private fun delete() {
        val ui = vm.ui.value
        when {
            ui.noteSelection.isNotEmpty() -> vm.deleteSelectedNotes()
            ui.clipSelection.isNotEmpty() -> vm.deleteSelectedClips()
        }
    }

    private fun quantize() {
        val ui = vm.ui.value
        val clip = ui.clip ?: return
        val g = ui.stepGrid
        if (ui.noteSelection.isNotEmpty()) vm.transformNotes { n -> n.copy(start = ClipOps.snap(n.start, g) % clip.lengthBeats) }
        else vm.quantizeClip(g)
    }

    private fun stepGrid(finer: Boolean) {
        val grids = ClipOps.GRIDS.filter { !it.second.endsWith("T") }.sortedByDescending { it.first } // straight grids, coarse -> fine
        val i = grids.indexOfFirst { kotlin.math.abs(it.first - vm.ui.value.stepGrid) < 1e-9 }.coerceAtLeast(0)
        val next = grids[(i + if (finer) 1 else -1).coerceIn(0, grids.lastIndex)]
        vm.setStepGrid(next.first)
        vm.toast("Grid ${next.second}")
    }

    /**
     * With notes selected: ←/→ move by the grid, Shift+←/→ shorten / lengthen, ↑/↓ transpose (Shift = octave).
     * Otherwise ←/→ pick the track and ↑/↓ the scene.
     */
    private fun arrow(key: Int, shift: Boolean) {
        val ui = vm.ui.value
        val clip = ui.clip
        val g = ui.stepGrid
        if (ui.noteSelection.isNotEmpty() && clip != null) {
            val len = clip.lengthBeats
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> vm.transformNotes { n ->
                    if (shift) n.copy(duration = (n.duration - g).coerceAtLeast(g / 4)) else n.copy(start = ((n.start - g) % len + len) % len)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> vm.transformNotes { n ->
                    if (shift) n.copy(duration = (n.duration + g).coerceAtMost(len - n.start)) else n.copy(start = (n.start + g) % len)
                }
                KeyEvent.KEYCODE_DPAD_UP -> vm.transformNotes { n -> n.copy(pitch = (n.pitch + if (shift) 12 else 1).coerceAtMost(127)) }
                KeyEvent.KEYCODE_DPAD_DOWN -> vm.transformNotes { n -> n.copy(pitch = (n.pitch - if (shift) 12 else 1).coerceAtLeast(0)) }
            }
            return
        }
        val p = ui.project ?: return
        when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> vm.selectScene((ui.selectedScene - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN -> vm.selectScene((ui.selectedScene + 1).coerceAtMost(p.sceneCount - 1))
            else -> {
                val i = p.tracks.indexOfFirst { it.id == ui.track?.id }
                val j = (i + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1).coerceIn(0, p.tracks.lastIndex)
                vm.selectTrack(p.tracks[j].id)
            }
        }
    }

    companion object {
        /** The cheat sheet shown in Settings. */
        val HELP = listOf(
            "A W S E D F T G Y H U J K O L P ; '" to "Play notes (like Live's computer MIDI keyboard)",
            "Z / X" to "Octave down / up",
            "C / V" to "Velocity down / up",
            "M" to "Computer MIDI keyboard on / off",
            "Space" to "Play / stop",
            "F9" to "Record",
            "Enter / Shift+Enter" to "Launch clip / launch scene",
            "Tab / Shift+Tab" to "Next / previous view",
            "← → ↑ ↓" to "Track / scene — with notes selected: move and transpose (Shift: length / octave)",
            "Delete / Backspace" to "Delete selected notes or clips",
            "Ctrl+Z / Ctrl+Shift+Z" to "Undo / redo",
            "Ctrl+C / X / V / D" to "Copy / cut / paste / duplicate",
            "Ctrl+A" to "Select all notes",
            "Ctrl+U" to "Quantize",
            "Ctrl+1 / Ctrl+2" to "Finer / coarser grid",
            "Ctrl+Shift+C" to "Capture MIDI",
            "Ctrl+T / Ctrl+Shift+T" to "New synth / drum track",
            "Ctrl+S / Ctrl+E / Ctrl+," to "Save / export to Live / set settings",
            "Esc" to "Back / close",
        )

        val MOUSE_HELP = listOf(
            "Right-click" to "Same as touch & hold (options, multi-select)",
            "Wheel on a knob or fader" to "Adjust (Shift = fine)",
            "Wheel in the editor" to "Scroll (Shift = sideways, Ctrl = zoom)",
            "Click / drag in the editor" to "Add or select notes / move them (Ctrl+drag copies), drag empty space to box-select",
            "Double-click a note" to "Delete it",
        )

        fun octaveLabel(baseNote: Int) = Scale.noteName(baseNote)
    }
}

/** Main-window text fields register here so typing in them doesn't play notes or trigger shortcuts. */
object TextInputGuard {
    @Volatile var focused = 0
}

fun Modifier.guardTextInput(): Modifier = composed {
    val has = remember { BooleanArray(1) }
    DisposableEffect(Unit) { onDispose { if (has[0]) { has[0] = false; TextInputGuard.focused-- } } }
    onFocusChanged { f ->
        if (f.isFocused != has[0]) {
            has[0] = f.isFocused
            TextInputGuard.focused += if (f.isFocused) 1 else -1
        }
    }
}
