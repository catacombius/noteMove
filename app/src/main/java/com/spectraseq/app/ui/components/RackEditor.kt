package com.spectraseq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spectraseq.app.ui.FxTarget
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.model.EffectSlot
import com.spectraseq.core.model.EffectType
import com.spectraseq.core.model.FactoryRacks
import com.spectraseq.core.model.LfoShape
import com.spectraseq.core.model.MacroTarget
import com.spectraseq.core.model.ModKind
import com.spectraseq.core.model.Modulator
import com.spectraseq.core.model.RackPreset
import com.spectraseq.core.model.syncDivision
import java.util.Locale
import kotlin.math.pow

/** What a "Map" press is assigning to: a macro or a modulator. */
private sealed interface MapTo {
    data class Macro(val index: Int) : MapTo
    data class Mod(val index: Int) : MapTo
}

/**
 * Effect creator, part 1: build a rack from built-in devices, map 8 macro knobs onto their parameters
 * (each with its own range) and add LFO / envelope / random modulators. Edits apply live to the slot,
 * so you hear them while building; "Save to library" keeps the rack for other sets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RackEditor(vm: StudioViewModel, ui: StudioUi, target: FxTarget, slotId: String, onClose: () -> Unit) {
    val project = ui.project ?: return
    val slot = vm.effectsOf(project, target).firstOrNull { it.id == slotId }
    val rack = slot?.rack
    if (slot == null || rack == null) { LaunchedEffect(Unit) { onClose() }; return }
    val color = NM.track(rack.color)
    var mapTo by remember { mutableStateOf<MapTo?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var startMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Int?>(null) } // -1 = rack name, else macro index
    var expanded by remember { mutableStateOf<Int?>(null) } // macro whose mappings are shown

    fun set(f: (RackPreset) -> RackPreset) = vm.updateEffectSlot(target, slotId, undoable = false) { s -> s.copy(rack = f(s.rack ?: rack)) }
    fun setMacroValue(i: Int, v: Float) = vm.setEffectValue(target, slotId, i, v)

    fun targetsOf(m: MapTo): List<MacroTarget> = when (m) {
        is MapTo.Macro -> rack.macros.getOrNull(m.index)?.targets.orEmpty()
        is MapTo.Mod -> rack.mods.getOrNull(m.index)?.targets.orEmpty()
    }

    /** Toggles inner parameter (si, pi) on the current map target. */
    fun toggleMapping(si: Int, pi: Int) {
        val m = mapTo ?: return
        val cur = rack.chain.getOrNull(si)?.value(pi) ?: 0f
        set { r ->
            when (m) {
                is MapTo.Macro -> r.copy(macros = r.macros.mapIndexed { i, mac ->
                    if (i != m.index) mac else {
                        val has = mac.targets.any { it.slot == si && it.param == pi }
                        mac.copy(targets = if (has) mac.targets.filter { !(it.slot == si && it.param == pi) } else mac.targets + MacroTarget(si, pi, 0f, maxOf(cur, 0.5f)))
                    }
                })
                is MapTo.Mod -> r.copy(mods = r.mods.mapIndexed { i, mod ->
                    if (i != m.index) mod else {
                        val has = mod.targets.any { it.slot == si && it.param == pi }
                        mod.copy(targets = if (has) mod.targets.filter { !(it.slot == si && it.param == pi) } else mod.targets + MacroTarget(si, pi, 0f, 1f))
                    }
                })
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(NM.bg).safeDrawingPadding().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Filled.Close, "Done", tint = NM.text) }
                Column(Modifier.weight(1f).clickable { renaming = -1 }) {
                    Text(rack.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
                    Text("Rack · tap the name to rename", fontSize = 11.sp, color = NM.textDim)
                }
                Box {
                    TextButton({ startMenu = true }) { Text("Start from…") }
                    DropdownMenu(startMenu, { startMenu = false }) {
                        for (f in FactoryRacks.ALL) DropdownMenuItem({ Text(f.name) }, {
                            startMenu = false
                            vm.updateEffectSlot(target, slotId) { it.copy(rack = f.copy(id = rack.id), values = f.macroValues) }
                        })
                    }
                }
                TextButton({ vm.saveRackToLibrary(rack.copy(macroValues = slot.values)) }) { Text("Save to library") }
                TextButton(onClose) { Text("Done") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // Macros
                SectionTitle(if (mapTo is MapTo.Macro) "Macros · now tap knob names below to map Macro ${(mapTo as MapTo.Macro).index + 1}" else "Macros")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rack.macros.forEachIndexed { i, m ->
                        val mapping = mapTo == MapTo.Macro(i)
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(if (mapping) color.copy(alpha = 0.25f) else NM.surface)
                                .border(1.dp, if (mapping) color else NM.line, RoundedCornerShape(8.dp)).padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Knob(m.name, slot.value(i), { setMacroValue(i, it) }, color = color, default = 0f, size = 46.dp)
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Chip(if (mapping) "Done" else "Map", mapping, { mapTo = if (mapping) null else MapTo.Macro(i) }, color = color)
                                Chip("${m.targets.size}", expanded == i, { expanded = if (expanded == i) null else i })
                            }
                            Text("Rename", fontSize = 10.sp, color = NM.textDim, modifier = Modifier.clickable { renaming = i }.padding(2.dp))
                        }
                    }
                }
                expanded?.let { mi ->
                    val m = rack.macros.getOrNull(mi)
                    if (m != null) MappingRanges(rack, m.targets, color) { si, pi, lo, hi, remove ->
                        set { r -> r.copy(macros = r.macros.mapIndexed { i, mac -> if (i != mi) mac else mac.copy(targets = if (remove) mac.targets.filter { !(it.slot == si && it.param == pi) } else mac.targets.map { if (it.slot == si && it.param == pi) it.copy(min = lo, max = hi) else it }) }) }
                    }
                }

                // Inner chain
                SectionTitle("Devices in the rack")
                if (rack.chain.isEmpty()) Text("Empty rack. Add devices below, then map macro knobs onto their parameters.", fontSize = 12.sp, color = NM.textDim)
                rack.chain.forEachIndexed { si, inner ->
                    InnerDevice(inner, si, rack.chain.size, color, mapTo?.let { targetsOf(it) },
                        onValue = { pi, v -> set { r -> r.copy(chain = r.chain.mapIndexed { i, s -> if (i == si) s.with(pi, v) else s }) } },
                        onToggleMap = { pi -> toggleMapping(si, pi) },
                        onMove = { d -> set { r -> r.copy(chain = r.chain.toMutableList().apply { val j = (si + d).coerceIn(0, lastIndex); add(j, removeAt(si)) }, macros = r.macros.map { it.copy(targets = it.targets.map { t -> t.copy(slot = remapIndex(t.slot, si, (si + d).coerceIn(0, r.chain.lastIndex))) }) }, mods = r.mods.map { it.copy(targets = it.targets.map { t -> t.copy(slot = remapIndex(t.slot, si, (si + d).coerceIn(0, r.chain.lastIndex))) }) }) } },
                        onRemove = {
                            set { r ->
                                fun fix(l: List<MacroTarget>) = l.filter { it.slot != si }.map { if (it.slot > si) it.copy(slot = it.slot - 1) else it }
                                r.copy(chain = r.chain.filterIndexed { i, _ -> i != si }, macros = r.macros.map { it.copy(targets = fix(it.targets)) }, mods = r.mods.map { it.copy(targets = fix(it.targets)) })
                            }
                        },
                    )
                }
                Box(Modifier.padding(top = 6.dp)) {
                    Chip("+ Add device", false, { addMenu = true }, color = color)
                    DropdownMenu(addMenu, { addMenu = false }) {
                        for ((cat, types) in EffectType.BUILT_IN.groupBy { it.category }) {
                            Text(cat.label.uppercase(), fontSize = 10.sp, color = NM.textDim, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                            for (t in types) DropdownMenuItem({ Text(t.label) }, { addMenu = false; set { r -> r.copy(chain = r.chain + EffectSlot(type = t)) } })
                        }
                    }
                }

                // Modulators
                SectionTitle("Modulators")
                rack.mods.forEachIndexed { mi, mod ->
                    ModulatorCard(mod, mi, color, mapTo == MapTo.Mod(mi),
                        onChange = { nm -> set { r -> r.copy(mods = r.mods.mapIndexed { i, x -> if (i == mi) nm else x }) } },
                        onMap = { mapTo = if (mapTo == MapTo.Mod(mi)) null else MapTo.Mod(mi) },
                        onRemove = { mapTo = null; set { r -> r.copy(mods = r.mods.filterIndexed { i, _ -> i != mi }) } },
                    )
                }
                if (rack.mods.size < 2) Chip("+ Add modulator", false, { set { r -> r.copy(mods = r.mods + Modulator()) } }, color = color)

                SectionTitle("Rack")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Knob("Dry / Wet", rack.dryWet, { v -> set { it.copy(dryWet = v) } }, color = color, default = 1f)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Colour", fontSize = 11.sp, color = NM.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in 0 until 16 step 2) Box(Modifier.width(20.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).background(NM.track(c))
                                .border(2.dp, if (rack.color == c) NM.text else NM.bg, RoundedCornerShape(4.dp)).clickable { set { it.copy(color = c) } })
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    renaming?.let { which ->
        var text by remember(which) { mutableStateOf(if (which < 0) rack.name else rack.macros.getOrNull(which)?.name ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                TextButton({
                    val t = text.trim().ifBlank { if (which < 0) "My Rack" else "Macro ${which + 1}" }
                    set { r -> if (which < 0) r.copy(name = t) else r.copy(macros = r.macros.mapIndexed { i, m -> if (i == which) m.copy(name = t.take(14)) else m }) }
                    renaming = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
            title = { Text(if (which < 0) "Rack name" else "Macro name") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            containerColor = NM.surface,
        )
    }
}

private fun remapIndex(i: Int, from: Int, to: Int): Int = when {
    i == from -> to
    from < to && i in (from + 1)..to -> i - 1
    from > to && i in to until from -> i + 1
    else -> i
}

/** One device inside the rack. In map mode, tapping a knob's name toggles it on the current macro / modulator. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InnerDevice(
    slot: EffectSlot, index: Int, count: Int, color: androidx.compose.ui.graphics.Color, mapped: List<MacroTarget>?,
    onValue: (Int, Float) -> Unit, onToggleMap: (Int) -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(NM.surface).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${index + 1}. ${slot.type.label}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NM.text, modifier = Modifier.weight(1f))
            if (index > 0) Chip("◀", false, { onMove(-1) })
            if (index < count - 1) Chip("▶", false, { onMove(1) })
            Chip("✕", false, onRemove, color = NM.record)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            slot.type.params.forEachIndexed { pi, spec ->
                val isMapped = mapped?.any { it.slot == index && it.param == pi } == true
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob(spec.name, slot.value(pi), { onValue(pi, it) }, color = if (isMapped) NM.accent else color, default = spec.default, size = 42.dp, display = spec.format)
                    if (mapped != null) Chip(if (isMapped) "Mapped" else "Map", isMapped, { onToggleMap(pi) }, color = NM.accent)
                }
            }
        }
    }
}

/** Range sliders for each parameter a macro drives. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MappingRanges(rack: RackPreset, targets: List<MacroTarget>, color: androidx.compose.ui.graphics.Color, onChange: (Int, Int, Float, Float, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(NM.surface).padding(8.dp)) {
        if (targets.isEmpty()) Text("Nothing mapped yet: press Map, then tap Map under any device knob.", fontSize = 12.sp, color = NM.textDim)
        for (t in targets) {
            val dev = rack.chain.getOrNull(t.slot) ?: continue
            val spec = dev.type.params.getOrNull(t.param) ?: continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${dev.type.label} · ${spec.name}", fontSize = 12.sp, color = NM.text, modifier = Modifier.width(150.dp))
                RangeSlider(t.min.coerceAtMost(t.max)..t.max.coerceAtLeast(t.min), { r -> onChange(t.slot, t.param, r.start, r.endInclusive, false) }, Modifier.weight(1f))
                Text("${spec.format(t.min)} → ${spec.format(t.max)}", fontSize = 10.sp, color = NM.textDim, modifier = Modifier.width(110.dp).padding(start = 4.dp))
                Chip("✕", false, { onChange(t.slot, t.param, 0f, 0f, true) })
            }
        }
        // Tip: inverting a mapping (min above max) is done by swapping in the model; the slider shows the span.
        if (targets.any { it.min > it.max }) Text("Some mappings run backwards (turning the macro up lowers them).", fontSize = 11.sp, color = color)
    }
}

@Composable
private fun ModulatorCard(mod: Modulator, index: Int, color: androidx.compose.ui.graphics.Color, mapping: Boolean, onChange: (Modulator) -> Unit, onMap: () -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (mapping) color.copy(alpha = 0.18f) else NM.surface)
            .border(1.dp, if (mapping) color else NM.line, RoundedCornerShape(10.dp)).padding(8.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mod ${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
            for (k in ModKind.entries) Chip(k.label, mod.kind == k, { onChange(mod.copy(kind = k)) }, color = color)
            Chip(if (mapping) "Done mapping" else "Map (${mod.targets.size})", mapping, onMap, color = NM.accent)
            Chip("✕", false, onRemove, color = NM.record)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            val msLabel: (Float) -> String = { v -> "${((0.02f + v * 1.5f) * 1000).toInt()} ms" }
            val syncLabel: (Float) -> String = { v -> syncDivision(v).second }
            val hzLabel: (Float) -> String = { v -> String.format(Locale.ROOT, "%.2f Hz", 0.05f * 400f.pow(v)) }
            val rateLabel = when {
                mod.kind == ModKind.ENVELOPE -> msLabel
                mod.sync || mod.kind == ModKind.RANDOM -> syncLabel
                else -> hzLabel
            }
            Knob(if (mod.kind == ModKind.ENVELOPE) "Release" else "Rate", mod.rate, { onChange(mod.copy(rate = it)) }, color = color, size = 42.dp, display = rateLabel)
            Knob("Depth", mod.depth, { onChange(mod.copy(depth = it)) }, color = color, size = 42.dp)
            if (mod.kind == ModKind.LFO) {
                Column {
                    ToggleBox(if (mod.sync) "Sync" else "Free", mod.sync, { onChange(mod.copy(sync = !mod.sync)) }, onColor = color)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (s in LfoShape.entries) Chip(s.label, mod.shape == s, { onChange(mod.copy(shape = s)) }, color = color)
                    }
                }
            }
        }
        if (mapping) Text("Tap Map under device knobs to choose what this modulator moves.", fontSize = 11.sp, color = NM.textDim)
    }
}
