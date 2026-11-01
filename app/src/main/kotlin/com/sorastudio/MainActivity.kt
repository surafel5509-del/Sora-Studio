package com.sorastudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.sorastudio.core.designsystem.SoraTheme
import com.sorastudio.core.storage.AppSettings
import com.sorastudio.core.storage.ThemeMode
import com.sorastudio.ui.SoraApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var mainViewModel: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            mainViewModel = viewModel
            val settings by viewModel.settings.collectAsState(initial = AppSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SoraTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SoraApp(viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Start the audio output so pads/keyboards/previews are instantly playable.
        mainViewModel?.controller?.ensureEngineStarted()
    }

    override fun onStop() {
        // If not playing back, silence live previews when backgrounded.
        mainViewModel?.controller?.stopPreviews()
        mainViewModel?.onExit()
        super.onStop()
    }
}
