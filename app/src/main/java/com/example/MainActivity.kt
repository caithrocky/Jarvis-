package com.example

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.assistant.SessionUiDirective
import com.example.ui.JarvisApp
import com.example.ui.JarvisViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        val app = application as JarvisApplication
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.commandCoordinator.sessionManager.directives.collect { directive ->
                        when (directive) {
                            SessionUiDirective.MinimizeFullAppToBackground -> {
                                moveTaskToBack(true)
                            }
                            SessionUiDirective.BringFullAppToForeground -> {
                                val bringFront = Intent(this@MainActivity, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                startActivity(bringFront)
                            }
                        }
                    }
                }
                launch {
                    combine(
                        app.preferencesRepository.settingsFlow,
                        app.voiceEngine.isMuted,
                        app.commandCoordinator.suppressScreenAwakeTemporarily
                    ) { settings, muted, suppressed ->
                        settings.keepScreenAwakeWhileActive && (settings.wakeWordEnabled || !muted) && !suppressed
                    }.collectLatest { keepAwake ->
                        if (keepAwake) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
            }
        }

        setContent {
            JarvisApp(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST) {
            viewModel.onAssistTriggered()
        }
    }
}
