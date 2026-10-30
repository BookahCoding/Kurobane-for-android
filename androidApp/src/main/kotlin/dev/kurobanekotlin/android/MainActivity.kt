package dev.kurobanekotlin.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.kurobanekotlin.android.ui.AppRoot
import org.koin.core.component.KoinComponent

class MainActivity : ComponentActivity(), KoinComponent {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            Surface(modifier = Modifier.fillMaxSize()) {
                AppRoot(systemDark = dark)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.resumed.value = true
    }

    override fun onPause() {
        AppVisibility.resumed.value = false
        super.onPause()
    }
}
