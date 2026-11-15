package io.github.jeiel85.findmywifephone.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.PairOffer

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf(SCREEN_HOME)
    private var pendingOffer by mutableStateOf<PairOffer?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Hub.init(this)
        handle(intent)
        setContent { AppTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_SCREEN)?.let { screen = it }
        PairOffer.parse(intent.dataString)?.let {
            pendingOffer = it
            screen = SCREEN_PAIR
        }
    }

    @Composable
    private fun Root() {
        val settings by Hub.store.settings.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().background(P.c.background).safeDrawingPadding()) {
            if (!settings.onboarded) {
                OnboardingScreen(onDone = { })
                return@Box
            }
            BackHandler(enabled = screen != SCREEN_HOME) { screen = SCREEN_HOME }
            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                when (s) {
                    SCREEN_PAIR -> PairScreen(
                        initialOffer = pendingOffer,
                        onOfferConsumed = { pendingOffer = null },
                        onBack = { screen = SCREEN_HOME },
                    )
                    SCREEN_HISTORY -> HistoryScreen(onBack = { screen = SCREEN_HOME })
                    SCREEN_SETTINGS -> SettingsScreen(onBack = { screen = SCREEN_HOME }, onPair = { screen = SCREEN_PAIR })
                    else -> HomeScreen(
                        onPair = { screen = SCREEN_PAIR },
                        onHistory = { screen = SCREEN_HISTORY },
                        onSettings = { screen = SCREEN_SETTINGS },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_HOME = "home"
        const val SCREEN_PAIR = "pair"
        const val SCREEN_HISTORY = "history"
        const val SCREEN_SETTINGS = "settings"
    }
}
