package com.spectraseq.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.data.ProjectSummary
import com.spectraseq.app.ui.DeviceLayout
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.components.AppSettingsSheet
import com.spectraseq.app.ui.components.Chip
import com.spectraseq.app.ui.components.displayName
import com.spectraseq.app.ui.components.holdClickable
import com.spectraseq.app.ui.guardTextInput
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.model.Scale
import com.spectraseq.core.model.SetTemplate
import java.text.DateFormat
import java.util.Date
import kotlin.math.sin

private enum class SortBy(val label: String) { RECENT("Recent"), NAME("Name"), TEMPO("Tempo") }

/** Start screen: new sketch from a template, recent sets with clip-grid thumbnails, search and import. */
@Composable
fun LibraryScreen(vm: StudioViewModel, ui: StudioUi, layout: DeviceLayout) {
    val sets by vm.library.collectAsState()
    val context = LocalContext.current
    var naming by remember { mutableStateOf<SetTemplate?>(null) }
    var settings by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortBy.RECENT) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    // Back clears a selection first; leaving the app needs a second press so it never closes by accident.
    var lastBack by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        when {
            ui.librarySelection.isNotEmpty() -> vm.clearLibrarySelection()
            query.isNotEmpty() -> query = ""
            now - lastBack < 2000 -> (context as? android.app.Activity)?.moveTaskToBack(true)
            else -> { lastBack = now; vm.toast("Press back again to leave SpectraSeq") }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importFile(uri, displayName(context, uri))
    }
    val shown = remember(sets, query, sort) {
        sets.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.let { l ->
            when (sort) {
                SortBy.RECENT -> l.sortedByDescending { it.modifiedAt }
                SortBy.NAME -> l.sortedBy { it.name.lowercase() }
                SortBy.TEMPO -> l.sortedBy { it.tempo }
            }
        }
    }
    val cover = layout.mode == DeviceLayout.Mode.COVER

    Box(Modifier.fillMaxSize().background(NM.bg)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (cover) 160.dp else 230.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(onImport = { importer.launch(arrayOf("*/*")) }, onSettings = { settings = true })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                NewSketchRow(cover, onQuick = { vm.newProject(vm.nextSketchName(), SetTemplate.BAND) }, onTemplate = { naming = it })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (ui.librarySelection.isNotEmpty()) SelectionBar(vm, ui)
                    else Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Your sets", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
                        Text("  ${sets.size}", fontSize = 13.sp, color = NM.textDim, modifier = Modifier.weight(1f))
                        for (s in SortBy.entries) Chip(s.label, sort == s, { sort = s }, Modifier.padding(start = 4.dp))
                    }
                    if (sets.size > 3) {
                        OutlinedTextField(
                            query, { query = it }, singleLine = true, placeholder = { Text("Search sets") },
                            leadingIcon = { Icon(Icons.Filled.Search, null, tint = NM.textDim) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).guardTextInput(),
                        )
                    }
                    if (sets.isEmpty()) {
                        Text(
                            "No sets yet. Pick a template above, play some pads and hit record — then export it as an Ableton Live Set " +
                                "or render it to audio from the share button. You can also open .als files made in Live.",
                            color = NM.textDim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            items(shown, key = { it.id }) { s -> SetCard(vm, s, s.id in ui.librarySelection, ui.librarySelection.isNotEmpty()) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
    naming?.let { template ->
        var name by remember(template) { mutableStateOf(vm.nextSketchName()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            confirmButton = { TextButton({ naming = null; vm.newProject(name, template) }) { Text("Create") } },
            dismissButton = { TextButton({ naming = null }) { Text("Cancel") } },
            title = { Text("New set · ${template.label}") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            containerColor = NM.surface,
        )
    }
    if (settings) AppSettingsSheet(vm) { settings = false }
}

@Composable
private fun Header(onImport: () -> Unit, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SpectrumLogo(Modifier.size(44.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("SpectraSeq", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NM.text)
            Text("Sketch on the go · finish in Ableton Live", fontSize = 13.sp, color = NM.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onImport) { Icon(Icons.Filled.FileOpen, "Open .als or set", tint = NM.text) }
        IconButton(onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = NM.text) }
    }
}

/** Five animated spectrum bars in the accent colour. */
@Composable
private fun SpectrumLogo(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "logo").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val accent = NM.accent
    val second = NM.solo
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(NM.surfaceHigh)) {
        val n = 5
        val gap = size.width * 0.06f
        val bw = (size.width - gap * (n + 1)) / n
        for (i in 0 until n) {
            val h = size.height * (0.3f + 0.5f * (0.5f + 0.5f * sin(t + i * 1.3f)))
            drawRoundRect(
                Brush.verticalGradient(listOf(accent, second), startY = size.height - h, endY = size.height),
                Offset(gap + i * (bw + gap), size.height - h - gap), Size(bw, h), CornerRadius(bw / 2),
            )
        }
    }
}

@Composable
private fun NewSketchRow(cover: Boolean, onQuick: () -> Unit, onTemplate: (SetTemplate) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Primary: one tap to a new sketch.
            Box(
                Modifier.width(if (cover) 150.dp else 200.dp).height(120.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(NM.accent, NM.accent.copy(alpha = 0.55f))))
                    .holdClickable(onClick = onQuick, onLongClick = { onTemplate(SetTemplate.BAND) })
                    .padding(14.dp),
            ) {
                Icon(Icons.Filled.Add, null, tint = Color.Black, modifier = Modifier.size(30.dp))
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text("New sketch", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("Tap to start · hold to name", fontSize = 11.sp, color = Color.Black.copy(alpha = 0.7f))
                }
            }
            for (t in SetTemplate.entries.filter { it != SetTemplate.BAND }) TemplateCard(t) { onTemplate(t) }
        }
    }
}

@Composable
private fun TemplateCard(t: SetTemplate, onClick: () -> Unit) {
    val colors = when (t) {
        SetTemplate.BEAT -> listOf(1, 9)
        SetTemplate.EMPTY -> listOf(1, 5)
        SetTemplate.SLICE -> listOf(12, 12, 12, 12)
        SetTemplate.GENERAL_MIDI -> listOf(1, 8, 11)
        SetTemplate.BAND -> listOf(1, 9, 5, 11)
    }
    Column(
        Modifier.width(150.dp).height(120.dp).clip(RoundedCornerShape(16.dp)).background(NM.surface)
            .border(1.dp, NM.line, RoundedCornerShape(16.dp))
            .holdClickable(onClick = onClick).padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (c in colors) Box(Modifier.size(width = 16.dp, height = 22.dp).clip(RoundedCornerShape(4.dp)).background(NM.track(c)))
        }
        Spacer(Modifier.weight(1f))
        Text(t.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
        Text(t.description, fontSize = 11.sp, color = NM.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelectionBar(vm: StudioViewModel, ui: StudioUi) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(vm::clearLibrarySelection) { Text("✕ ${ui.librarySelection.size} selected") }
        OutlinedButton(vm::duplicateSelectedProjects) { Text("Duplicate") }
        Button({ confirm = true }) { Text("Delete") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        confirmButton = { TextButton({ confirm = false; vm.deleteSelectedProjects() }) { Text("Delete", color = NM.record) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        title = { Text("Delete ${ui.librarySelection.size} sets?") },
        text = { Text("They are removed from this phone with their samples.") },
        containerColor = NM.surface,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetCard(vm: StudioViewModel, s: ProjectSummary, selected: Boolean, selecting: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) NM.surfaceHigh else NM.surface)
                .border(2.dp, if (selected) NM.accent else NM.line.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .holdClickable(onClick = { if (selecting) vm.toggleLibrarySelection(s.id) else vm.openProject(s.id) }, onLongClick = { menu = true })
                .padding(10.dp),
        ) {
            Thumbnail(s, Modifier.fillMaxWidth().height(64.dp))
            Spacer(Modifier.height(8.dp))
            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NM.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${"%.0f".format(s.tempo)} BPM · ${Scale.NOTE_NAMES[Math.floorMod(s.rootNote, 12)]} ${s.scale} · ${s.tracks} tracks",
                fontSize = 11.sp, color = NM.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.modifiedAt)), fontSize = 10.sp, color = NM.textDim)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Open") }, { menu = false; vm.openProject(s.id) })
            DropdownMenuItem({ Text(if (selected) "Deselect" else "Select (multi)") }, { menu = false; vm.toggleLibrarySelection(s.id) })
            DropdownMenuItem({ Text("Duplicate") }, { menu = false; vm.duplicateProject(s.id, "${s.name} copy") })
            DropdownMenuItem({ Text("Delete", color = NM.record) }, { menu = false; confirmDelete = true })
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = { TextButton({ confirmDelete = false; vm.deleteProject(s.id) }) { Text("Delete", color = NM.record) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
            title = { Text("Delete \"${s.name}\"?") },
            text = { Text("This removes the set and its samples from this phone.") },
            containerColor = NM.surface,
        )
    }
}

/** Mini session view: one column per track, one row per scene, filled where a clip has notes. */
@Composable
private fun Thumbnail(s: ProjectSummary, modifier: Modifier) {
    val trackColors = s.trackColors.map { NM.track(it) }
    val empty = NM.padDim
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(NM.bg)) {
        val cols = trackColors.size.coerceAtLeast(1)
        val rows = s.thumbScenes.coerceAtLeast(1)
        val gap = 3f
        val cw = (size.width - gap * (cols + 1)) / cols
        val rh = (size.height - gap * (rows + 1)) / rows
        for (t in 0 until cols) for (r in 0 until rows) {
            val on = (t * rows + r) in s.thumbCells
            drawRoundRect(
                if (on) trackColors.getOrElse(t) { empty } else empty,
                Offset(gap + t * (cw + gap), gap + r * (rh + gap)), Size(cw, rh), CornerRadius(3f),
            )
        }
    }
}
