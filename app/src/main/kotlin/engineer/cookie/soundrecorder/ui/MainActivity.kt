package engineer.cookie.soundrecorder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import engineer.cookie.soundrecorder.ui.theme.SoundRecorderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SoundRecorderTheme {
                val viewModel: RecorderViewModel = viewModel()
                RecorderScreen(viewModel)
            }
        }
    }
}
