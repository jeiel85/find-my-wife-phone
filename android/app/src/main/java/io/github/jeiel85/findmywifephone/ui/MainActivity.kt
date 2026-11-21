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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.PairOffer
import io.github.jeiel85.findmywifephone.locate.LocateService
import io.github.jeiel85.findmywifephone.ring.RingService
import io.github.jeiel85.findmywifephone.update.SelfUpdate

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf(SCREEN_HOME)
    private var pendingOffer by mutableStateOf<PairOffer?>(null)
    private var focusPeer by mutableStateOf<String?>(null)

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

    override fun onStart() {
        super.onStart()
        SelfUpdate.onAppVisible(true)
    }

    override fun onStop() {
        SelfUpdate.onAppVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_SCREEN)?.let { screen = it }
        // Only selects the peer to show; never acts (this activity is exported).
        intent.getStringExtra(EXTRA_PEER)?.let { focusPeer = it }
        PairOffer.parse(intent.dataString)?.let {
            pendingOffer = it
            screen = SCREEN_PAIR
        }
    }

    @Composable
    private fun Root() {
        val settings by Hub.store.settings.collectAsStateWithLifecycle()
        val dark = isAppDark()
        // Status-bar icons must follow the in-app theme, not only the system's dark mode.
        LaunchedEffect(dark) {
            val t = android.graphics.Color.TRANSPARENT
            val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
        // While this app is on screen Android shows the incoming request only as a heads-up
        // notification. Open the full alert (countdown / 찾았어요) ourselves, once per request.
        val ring by RingService.ui.collectAsStateWithLifecycle()
        val locate by LocateService.ui.collectAsStateWithLifecycle()
        val incoming = ring?.reqId ?: locate?.reqId
        LaunchedEffect(incoming) {
            if (incoming != null) startActivity(Intent(this@MainActivity, AlertActivity::class.java))
        }
        fun openAlert() = startActivity(Intent(this@MainActivity, AlertActivity::class.java))
        Box(Modifier.fillMaxSize().background(P.c.wall).safeDrawingPadding()) {
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
                    SCREEN_SETTINGS -> SettingsScreen(onBack = { screen = SCREEN_HOME }, onPair = { screen = SCREEN_PAIR }, onHistory = { screen = SCREEN_HISTORY })
                    else -> HomeScreen(
                        onPair = { screen = SCREEN_PAIR },
                        onHistory = { screen = SCREEN_HISTORY },
                        onSettings = { screen = SCREEN_SETTINGS },
                        focusPeer = focusPeer,
                        onFocusConsumed = { focusPeer = null },
                    )
                }
            }
            // The alert screen opens once per request; after Back closed it, this brings it back.
            val label = ring?.let { if (it.test) "내 폰 울려 보기 중" else "${it.peerName}님이 폰을 찾고 있어요" }
                ?: locate?.let { "${it.peerName}님이 위치를 확인하려고 해요" }
            if (label != null) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(RoundedCornerShape(24.dp))
                        .background(P.c.coral).clickable { openAlert() }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false))
                    Text("  열기", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        /** Peer to show on the home screen (set by quick.ShortcutActivity; only selects, never acts). */
        const val EXTRA_PEER = "peer"
        const val SCREEN_HOME = "home"
        const val SCREEN_PAIR = "pair"
        const val SCREEN_HISTORY = "history"
        const val SCREEN_SETTINGS = "settings"
    }
}
