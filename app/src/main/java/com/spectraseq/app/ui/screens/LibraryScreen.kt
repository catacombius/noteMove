package com.spectraseq.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.spectraseq.app.ui.components.holdClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.data.ProjectSummary
import com.spectraseq.app.ui.DeviceLayout
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.components.displayName
import com.spectraseq.app.ui.theme.NM
import java.text.DateFormat
import java.util.Date

/** The list of sets on the phone (like Note's / Move's set browser). */
@Composable
fun LibraryScreen(vm: StudioViewModel, ui: StudioUi, layout: DeviceLayout) {
    val sets by vm.library.collectAsState()
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    // Back clears a selection first; leaving the app needs a second press so it never closes by accident.
    var lastBack by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        when {
            ui.librarySelection.isNotEmpty() -> vm.clearLibrarySelection()
            now - lastBack < 2000 -> (context as? android.app.Activity)?.moveTaskToBack(true)
            else -> { lastBack = now; vm.toast("Press back again to leave SpectraSeq") }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importFile(uri, displayName(context, uri))
    }
    Box(Modifier.fillMaxSize().background(NM.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SpectraSeq", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NM.text)
                    Text("Sketch on the go · finish in Ableton Live", fontSize = 13.sp, color = NM.textDim)
                }
            }
            if (ui.librarySelection.isNotEmpty()) {
                var confirm by remember { mutableStateOf(false) }
                Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
            } else Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ creating = true }) { Text("+ New set") }
                OutlinedButton({ importer.launch(arrayOf("*/*")) }) { Text("Open .als / set…") }
            }
            if (sets.isEmpty()) {
                Text(
                    "No sets yet. Start a new set, play some pads, hit record — then export it as an Ableton Live Set " +
                        "from the share button. You can also open .als files made in Live.",
                    color = NM.textDim, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (layout.mode == DeviceLayout.Mode.COVER) 160.dp else 220.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(sets, key = { it.id }) { s -> SetCard(vm, s, s.id in ui.librarySelection, ui.librarySelection.isNotEmpty()) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
    if (creating) {
        var name by remember { mutableStateOf("Sketch ${sets.size + 1}") }
        AlertDialog(
            onDismissRequest = { creating = false },
            confirmButton = { TextButton({ creating = false; vm.newProject(name) }) { Text("Create") } },
            dismissButton = { TextButton({ creating = false }) { Text("Cancel") } },
            title = { Text("New set") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            containerColor = NM.surface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetCard(vm: StudioViewModel, s: ProjectSummary, selected: Boolean, selecting: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) NM.surfaceHigh else NM.surface)
                .border(2.dp, if (selected) NM.solo else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                .holdClickable(onClick = { if (selecting) vm.toggleLibrarySelection(s.id) else vm.openProject(s.id) }, onLongClick = { menu = true })
                .padding(14.dp),
        ) {
            Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NM.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("${"%.0f".format(s.tempo)} BPM · ${s.tracks} tracks · ${s.clips} clips", fontSize = 12.sp, color = NM.textDim)
            Spacer(Modifier.weight(1f))
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.modifiedAt)), fontSize = 11.sp, color = NM.textDim)
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
