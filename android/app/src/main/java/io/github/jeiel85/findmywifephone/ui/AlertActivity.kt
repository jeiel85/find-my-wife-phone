package io.github.jeiel85.findmywifephone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.locate.LocateService
import io.github.jeiel85.findmywifephone.locate.LocateUi
import io.github.jeiel85.findmywifephone.ring.RingService
import io.github.jeiel85.findmywifephone.ring.RingUi

/** Shown over the lock screen while a ring or location request is in progress. */
class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Hub.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            AppTheme {
                val ring by RingService.ui.collectAsStateWithLifecycle()
                val locate by LocateService.ui.collectAsStateWithLifecycle()
                LaunchedEffect(ring == null && locate == null) { if (ring == null && locate == null) finish() }
                val r = ring
                val l = locate
                when {
                    r != null -> RingAlert(r)
                    l != null -> LocateAlert(l)
                }
            }
        }
    }
}

private val Night = Color(0xFF15121A)
private val Coral = Color(0xFFFF6B5B)
private val Indigo = Color(0xFF6E7BFF)

@Composable
private fun Backdrop(accent: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), Night, Night))),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) { content() }
    }
}

@Composable
private fun CountdownRing(fraction: Float, seconds: Int, accent: Color, icon: ImageVector) {
    val animated by animateFloatAsState(fraction, tween(950), label = "cd")
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset),
                Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            drawArc(accent, -90f, 360f * animated, false, Offset(inset, inset),
                Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text("$seconds", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
            Text("초", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        }
    }
}

@Composable
private fun Headline(title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 32.dp)) {
        Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 36.sp)
        Spacer(Modifier.height(10.dp))
        Text(body, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 23.sp)
    }
}

@Composable
private fun RingAlert(ui: RingUi) = Backdrop(Coral) {
    if (!ui.ringing) {
        Headline("${ui.peerName}님이\n폰을 찾고 있어요", "잠시 뒤 소리가 크게 울려요.\n원하지 않으면 거절할 수 있어요.")
        CountdownRing(ui.secondsLeft / RingService.COUNTDOWN_SEC.toFloat(), ui.secondsLeft, Coral, Icons.Rounded.NotificationsActive)
        Column(Modifier.fillMaxWidth()) {
            PillButton("거절하기", null, Color.White, Color.White, Modifier.fillMaxWidth(), outlined = true) { RingService.decline() }
        }
    } else {
        Headline("여기 있어요!", "${ui.peerName}님의 요청으로 울리는 중이에요.\n찾았으면 아래 버튼을 눌러 주세요.")
        val t = rememberInfiniteTransition(label = "bell")
        val s by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "s")
        Box(Modifier.size(200.dp).scale(s).clip(CircleShape).background(Coral), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.NotificationsActive, null, tint = Color.White, modifier = Modifier.size(88.dp))
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PillButton("찾았어요", null, Color.White, Night, Modifier.fillMaxWidth()) { RingService.found() }
            Spacer(Modifier.height(10.dp))
            Text("소리와 방해 금지 설정은 원래대로 돌아가요 · ${ui.secondsLeft}초 뒤 자동으로 멈춰요",
                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LocateAlert(ui: LocateUi) = Backdrop(Indigo) {
    when {
        ui.locating -> {
            Headline("위치를 보내는 중", "${ui.peerName}님에게 현재 위치를 한 번 보내요.")
            Box(Modifier.size(200.dp).clip(CircleShape).background(Indigo), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.LocationOn, null, tint = Color.White, modifier = Modifier.size(80.dp))
            }
            Spacer(Modifier.height(1.dp))
        }
        ui.ask -> {
            Headline("${ui.peerName}님이\n내 위치를 확인하려고 해요", "허용하면 지금 위치를 한 번 보내요.\n보낸 기록은 기록 화면에 남아요.")
            CountdownRing(ui.secondsLeft / LocateService.ASK_SEC.toFloat(), ui.secondsLeft, Indigo, Icons.Rounded.LocationOn)
            Column(Modifier.fillMaxWidth()) {
                PillButton("허용", null, Indigo, Color.White, Modifier.fillMaxWidth()) { LocateService.allow() }
                Spacer(Modifier.height(10.dp))
                PillButton("거절", null, Color.White, Color.White, Modifier.fillMaxWidth(), outlined = true) { LocateService.decline() }
            }
        }
        else -> {
            Headline("${ui.peerName}님이\n내 위치를 확인하려고 해요", "잠시 뒤 현재 위치를 한 번 보내요.\n원하지 않으면 거절할 수 있어요.")
            CountdownRing(ui.secondsLeft / LocateService.COUNTDOWN_SEC.toFloat(), ui.secondsLeft, Indigo, Icons.Rounded.LocationOn)
            Column(Modifier.fillMaxWidth()) {
                PillButton("거절하기", null, Color.White, Color.White, Modifier.fillMaxWidth(), outlined = true) { LocateService.decline() }
            }
        }
    }
}
