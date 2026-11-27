package com.x1rain0.chaoxingstudio

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.x1rain0.chaoxingstudio.ui.MonitorApp
import com.x1rain0.chaoxingstudio.ui.theme.ChaoxingStudioTheme

class MainActivity : ComponentActivity() {
    private val monitorViewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val debugShowMapPicker = isDebuggable && intent.getBooleanExtra(EXTRA_DEBUG_MAP_PICKER, false)
        val debugSignPreviewKind = if (isDebuggable) {
            intent.getIntExtra(EXTRA_DEBUG_SIGN_PREVIEW, -1).takeIf { it in 0..5 }
        } else {
            null
        }
        setContent {
            ChaoxingStudioTheme {
                MonitorApp(
                    monitorViewModel,
                    debugShowMapPicker = debugShowMapPicker,
                    debugSignPreviewKind = debugSignPreviewKind,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        monitorViewModel.onHostStarted()
    }

    override fun onStop() {
        monitorViewModel.onHostStopped()
        super.onStop()
    }

    companion object {
        const val EXTRA_DEBUG_MAP_PICKER = "debug_map_picker"
        const val EXTRA_DEBUG_SIGN_PREVIEW = "debug_sign_preview"
    }
}
