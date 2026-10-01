package com.notemove.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notemove.app.ui.FxTarget
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.model.EffectSlot
import com.notemove.core.model.EffectType
import com.notemove.core.model.MAX_EFFECTS

/** Insert effect chains for each track and the master bus. Touch & hold an effect for options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffectsPanel(vm: StudioViewModel, ui: StudioUi, modifier: Modifier = Modifier) {
    val project = ui.project ?: return
    val target = ui.fxTarget.takeIf { it.trackId == null || project.track(it.trackId) != null } ?: FxTarget(ui.track?.id)
    val slots = vm.effectsOf(project, target)
    val color = target.trackId?.let { project.track(it)?.color }?.let { NM.track(it) } ?: NM.accent
    var addMenu by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in project.tracks) Chip(t.name, target.trackId == t.id, { vm.selectTrack(t.id) }, color = NM.track(t.color))
            Chip("Master", target.trackId == null, { vm.setFxTarget(FxTarget(null)) })
        }
        Spacer(Modifier.padding(4.dp))
        if (slots.isEmpty()) {
            Text("No effects yet. Add one below — they run in order, left to right like Live's device chain.", fontSize = 12.sp, color = NM.textDim)
        }
        slots.forEachIndexed { i, slot -> EffectCard(vm, target, slot, i, slots.size, color) }
        Box(Modifier.padding(top = 8.dp)) {
            Chip(if (slots.size < MAX_EFFECTS) "+ Add effect" else "Chain full", false, { if (slots.size < MAX_EFFECTS) addMenu = true }, color = color)
            DropdownMenu(addMenu, { addMenu = false }) {
                for (type in EffectType.entries) DropdownMenuItem({ Text(type.label) }, { addMenu = false; vm.addEffect(target, type) })
            }
        }
        Spacer(Modifier.padding(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun EffectCard(vm: StudioViewModel, target: FxTarget, slot: EffectSlot, index: Int, count: Int, color: androidx.compose.ui.graphics.Color) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(NM.surface)
            .border(1.dp, if (slot.enabled) color.copy(alpha = 0.6f) else NM.line, RoundedCornerShape(10.dp)).padding(8.dp),
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToggleBox(if (slot.enabled) "ON" else "OFF", slot.enabled, { vm.toggleEffect(target, slot.id) }, onColor = color)
                Text(slot.type.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NM.text, modifier = Modifier.weight(1f))
                if (index > 0) Chip("◀", false, { vm.moveEffect(target, slot.id, -1) })
                if (index < count - 1) Chip("▶", false, { vm.moveEffect(target, slot.id, 1) })
                Chip("⋯", false, { menu = true })
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (slot.enabled) "Bypass" else "Enable") }, { menu = false; vm.toggleEffect(target, slot.id) })
                DropdownMenuItem({ Text("Duplicate") }, { menu = false; vm.duplicateEffect(target, slot.id) })
                DropdownMenuItem({ Text("Reset knobs") }, { menu = false; vm.resetEffect(target, slot.id) })
                if (index > 0) DropdownMenuItem({ Text("Move earlier") }, { menu = false; vm.moveEffect(target, slot.id, -1) })
                if (index < count - 1) DropdownMenuItem({ Text("Move later") }, { menu = false; vm.moveEffect(target, slot.id, 1) })
                HorizontalDivider()
                DropdownMenuItem({ Text("Remove", color = NM.record) }, { menu = false; vm.removeEffect(target, slot.id) })
            }
        }
        FlowRow(Modifier.alpha(if (slot.enabled) 1f else 0.5f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            slot.type.params.forEachIndexed { pi, spec ->
                Knob(spec.name, slot.value(pi), { vm.setEffectValue(target, slot.id, pi, it) }, color = color, default = spec.default, size = 46.dp, display = spec.format)
            }
        }
    }
}
