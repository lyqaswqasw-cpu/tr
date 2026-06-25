package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import com.example.ui.MainApp
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent {
      val mainViewModel: MainViewModel = viewModel()
      val selectedTheme by mainViewModel.selectedTheme.collectAsState()
      
      // Handle Deep Link
      LaunchedEffect(intent) {
          handleIntent(intent, mainViewModel)
      }

      MyApplicationTheme(themeName = selectedTheme) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          MainApp(viewModel = mainViewModel)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
      super.onNewIntent(intent)
      // Note: In a real app, you'd pass this to a State or directly to ViewModel
  }

  private fun handleIntent(intent: Intent?, viewModel: MainViewModel) {
      intent?.data?.let { uri ->
          if (uri.host == "25-live.app" && uri.path?.startsWith("/play") == true) {
              val shareId = uri.getQueryParameter("id")
              if (shareId != null) {
                  viewModel.handleSharedLink(shareId) { url, title ->
                      viewModel.startPlaying(url, title, isLive = true)
                  }
              }
          }
      }
  }
}

