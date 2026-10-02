package com.notemove.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.notemove.app.app
import com.notemove.app.ui.components.displayName
import com.notemove.app.ui.screens.LibraryScreen
import com.notemove.app.ui.screens.StudioScreen
import com.notemove.app.ui.theme.NoteMoveTheme

class MainActivity : ComponentActivity() {
    private val vm: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            NoteMoveTheme {
                val ui by vm.ui.collectAsState()
                val layout = rememberDeviceLayout(this)
                val hideNav by vm.hideNavBar.collectAsState()
                LaunchedEffect(hideNav) { applyFullscreen() }
                val inSet = ui.project != null
                LaunchedEffect(inSet) {
                    if (inSet) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (inSet) StudioScreen(vm, ui, layout) else LibraryScreen(vm, ui, layout)
            }
        }
    }

    /** Fullscreen: the status bar is always hidden (swipe down to peek); the navigation bar optionally too. */
    private fun applyFullscreen() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.statusBars())
        if (vm.hideNavBar.value) c.hide(WindowInsetsCompat.Type.navigationBars()) else c.show(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Opening an .als / .notemove / exported zip from a file manager or the share sheet imports it. */
    private fun handleIntent(intent: Intent?) {
        val i = intent ?: return
        val uri: Uri = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        vm.importFile(uri, displayName(this, uri))
        i.action = null
    }

    override fun onStart() {
        super.onStart()
        app.output.start()
    }

    override fun onStop() {
        super.onStop()
        vm.flushSave()
        // Keep playing in the background only while the transport runs (e.g. jamming with the screen off).
        if (!app.engine.state.playing && !isChangingConfigurations) app.output.stop()
    }
}
