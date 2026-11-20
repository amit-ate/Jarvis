package com.jarvis.edge.hud

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat

class MindActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()

        setContent {
            val state by MindUiBridge.state.collectAsState()
            MindScreen(
                state = state,
                onBack = { finish() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        MindUiBridge.refresh()
    }

    @Suppress("DEPRECATION")
    private fun applyDarkSystemBars() {
        val darkBg = Color.parseColor("#020617")
        window.statusBarColor = darkBg
        window.navigationBarColor = darkBg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
