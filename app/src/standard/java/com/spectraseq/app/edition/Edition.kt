package com.spectraseq.app.edition

import android.content.Context
import androidx.compose.runtime.Composable
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.core.model.TrackKind

/** What this edition adds to SpectraSeq: nothing (the Garden edition adds Botanica's engine, src/garden). */
object Edition {
    /** The kinds of track offered (a GARDEN track in a shared set still plays, as a synth). */
    val trackKinds: List<TrackKind> = TrackKind.entries.filter { it != TrackKind.GARDEN }

    fun install(context: Context) {}
}

@Composable
fun GardenEditor(vm: StudioViewModel, ui: StudioUi) {}
