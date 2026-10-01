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
                val inSet = ui.project != null
                LaunchedEffect(inSet) {
                    if (inSet) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (inSet) StudioScreen(vm, ui, layout) else LibraryScreen(vm, ui, layout)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Opening an .als / .notemove / exported zip from a file manager or the share sheet imports it. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        vm.importFile(uri, displayName(this, uri))
        intent?.action = null
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
