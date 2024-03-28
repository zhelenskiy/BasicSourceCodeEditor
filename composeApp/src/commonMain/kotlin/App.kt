import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun App() {
    MaterialTheme {
        Box(Modifier.windowInsetsPadding(WindowInsets.ime).fillMaxSize()) {
            CorrectBracketSequence()
        }
    }
}
