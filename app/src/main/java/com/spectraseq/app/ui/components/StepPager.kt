package com.spectraseq.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.theme.NM
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Sequencer pages always offer at least this many pages to swipe between (steps past the clip end extend it). */
const val MIN_SEQ_PAGES = 2

fun sequencerPages(ui: StudioUi, pageBeats: Double): Int {
    val length = ui.clip?.lengthBeats ?: (ui.newClipBars * 4.0)
    return maxOf(MIN_SEQ_PAGES, ceil(length / pageBeats - 1e-9).toInt()).coerceAtMost(64)
}

/**
 * Pages of steps you can slide through smoothly with a horizontal swipe (snapping to a page), kept in
 * sync with the selected step page (page dots, Push's loop selector, the editor).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StepPager(vm: StudioViewModel, ui: StudioUi, pages: Int, modifier: Modifier = Modifier, state: PagerState = rememberStepPagerState(ui, pages), content: @Composable (page: Int) -> Unit) {
    // Selected page -> pager (dots, loop selector)
    LaunchedEffect(ui.stepPage, pages) {
        val target = ui.stepPage.coerceIn(0, pages - 1)
        if (state.currentPage != target && !state.isScrollInProgress) state.animateScrollToPage(target)
    }
    // Pager -> selected page (after a swipe settles)
    LaunchedEffect(state) {
        snapshotFlow { state.settledPage }.collect { p -> if (p != vm.ui.value.stepPage) vm.setStepPage(p) }
    }
    HorizontalPager(state = state, modifier = modifier, pageSpacing = 10.dp, beyondViewportPageCount = 1, key = { it }) { page -> content(page) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberStepPagerState(ui: StudioUi, pages: Int): PagerState =
    rememberPagerState(initialPage = ui.stepPage.coerceIn(0, maxOf(0, pages - 1))) { pages }

/** Page dots; the current page follows the swipe position, tap a dot to slide there. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageDots(state: PagerState, pages: Int, inClipPages: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (p in 0 until pages) {
            val current = p == state.currentPage
            Box(
                Modifier.width(if (current) 22.dp else 16.dp).height(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (current) NM.text else if (p < inClipPages) NM.textDim else NM.line)
                    .clickable { scope.launch { state.animateScrollToPage(p) } }
                    .padding(2.dp),
            )
        }
    }
}
