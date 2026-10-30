package com.spectraseq.app.edition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.botanica.engine.BotanicaEngine
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.components.Chip
import com.spectraseq.app.ui.components.Knob
import com.spectraseq.app.ui.components.SectionTitle
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.model.GardenPatch

/** A GARDEN track's sound: Botanica's factory sounds and kits, then any of its parameters, group by group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GardenEditor(vm: StudioViewModel, ui: StudioUi) {
    val track = ui.track ?: return
    val patch = track.garden ?: GardenPatch()
    val color = NM.track(track.color)
    val params = BotanicaEngine.PARAMS
    // a new track (or one from a set made without the Garden) starts on the first sound and kit
    LaunchedEffect(track.id, patch.values.size) {
        if (patch.values.size != params.size) {
            vm.setGarden(patch.copy(sound = BotanicaEngine.SOUNDS[0].name, kit = BotanicaEngine.KITS[0].name,
                values = GardenPresets.initial()))
        }
    }
    if (patch.values.size != params.size) return
    val values = patch.values

    fun preset(p: BotanicaEngine.Preset) = vm.setGarden(
        if (p.drums) patch.copy(kit = p.name, values = GardenPresets.apply(values, p))
        else patch.copy(sound = p.name, values = GardenPresets.apply(values, p)),
        undoable = true,
    )

    var category by rememberSaveable { mutableStateOf(BotanicaEngine.SOUNDS[0].category) }
    val categories = remember { BotanicaEngine.SOUNDS.map { it.category }.distinct() }
    SectionTitle("Botanica sound · ${patch.sound}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in categories) Chip(c, c == category, { category = c }, color = color)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in BotanicaEngine.SOUNDS.filter { it.category == category })
            Chip(p.name, p.name == patch.sound, { preset(p) }, color = color)
    }
    SectionTitle("Kit · ${patch.kit}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in BotanicaEngine.KITS) Chip(p.name, p.name == patch.kit, { preset(p) }, color = color)
    }

    val groups = remember { params.map { it.group }.distinct() }
    var group by rememberSaveable { mutableStateOf(groups.first()) }
    SectionTitle("Shape")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in groups) Chip(g, g == group, { group = g }, color = color)
    }
    fun set(id: Int, v: Double) = vm.setGarden(patch.copy(values = values.toMutableList().also { it[id] = v }))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in params.filter { it.group == group }) {
            if (p.steps == 0) {
                Knob(p.label, values[p.id].toFloat(), { v -> set(p.id, v.toDouble()) }, color = color,
                    default = p.default.toFloat(), display = { p.format(it.toDouble()) })
            } else {
                // a list or a switch: a tap steps it on
                val step = (Math.round(values[p.id] * p.steps).toInt() + 1) % (p.steps + 1)
                Chip("${p.label} ${p.format(values[p.id])}", false, { set(p.id, step / p.steps.toDouble()) }, color = color)
            }
        }
    }
    Text("Each GARDEN track is a whole Botanica: a few at once can be a lot for a phone.", fontSize = 11.sp, color = NM.textDim)
}
