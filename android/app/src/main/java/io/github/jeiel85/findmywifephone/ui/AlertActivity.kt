package io.github.jeiel85.findmywifephone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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
                    r != null && r.ringing -> Ringing(r)
                    r != null -> Countdown(
                        accent = Coral, icon = Icons.Rounded.NotificationsActive,
                        eyebrow = "폰 찾기 요청", title = "${r.peerName}님이\n폰을 찾고 있어요",
                        body = "곧 소리가 크게 울려요.\n원하지 않으면 거절할 수 있어요.",
                        seconds = r.secondsLeft, total = RingService.COUNTDOWN_SEC,
                    ) { GhostButton("거절하기") { RingService.decline() } }
                    l != null -> LocateAlert(l)
                }
            }
        }
    }
}

private val Night = Color(0xFF12142B)
private val NightDeep = Color(0xFF08091A)
private val Coral = Color(0xFFFF6B5B)
private val CoralDeep = Color(0xFFE04A3A)
private val Indigo = Color(0xFF7C88FF)

@Composable
private fun GlowBackdrop(accent: Color, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night, NightDeep)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.42f), Color.Transparent),
                Offset(size.width / 2, size.height * 0.5f), size.width * 0.85f), size.width * 0.85f, Offset(size.width / 2, size.height * 0.5f))
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) { content() }
    }
}

@Composable
private fun Header(eyebrow: String, title: String, body: String, light: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 36.dp)) {
        Text(eyebrow, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
            lineHeight = 40.sp, letterSpacing = (-0.8).sp)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Color.White.copy(alpha = if (light) 0.78f else 0.9f), fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
    }
}

@Composable
private fun Countdown(
    accent: Color, icon: ImageVector, eyebrow: String, title: String, body: String,
    seconds: Int, total: Int, actions: @Composable () -> Unit,
) = GlowBackdrop(accent) {
    Header(eyebrow, title, body)
    val fraction by animateFloatAsState(seconds / total.toFloat(), tween(950, easing = LinearEasing), label = "cd")
    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.1f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(Brush.sweepGradient(listOf(accent.copy(alpha = 0.5f), accent, accent.copy(alpha = 0.5f))), -90f, 360f * fraction,
                false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(30.dp))
            Text("$seconds", color = Color.White, fontSize = 76.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
            Text("초 남음", color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
        }
    }
    Column(Modifier.fillMaxWidth()) { actions() }
}

@Composable
private fun Ringing(ui: RingUi) {
    val t = rememberInfiniteTransition(label = "ring")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "wave")
    val shake by t.animateFloat(-14f, 14f, infiniteRepeatable(tween(110), RepeatMode.Reverse), label = "shake")
    var bell by remember { mutableStateOf(Offset.Unspecified) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Coral, CoralDeep)))) {
        Canvas(Modifier.fillMaxSize()) {
            val c = if (bell.isSpecified) bell else Offset(size.width / 2, size.height * 0.5f)
            val maxR = size.height * 0.62f
            for (i in 0..3) {
                val p = (wave + i / 4f) % 1f
                drawCircle(Color.White.copy(alpha = (1f - p) * 0.35f), 90.dp.toPx() + maxR * p, c, style = Stroke(2.dp.toPx() + 6.dp.toPx() * (1 - p)))
            }
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (ui.test) Header("울림 테스트", "이렇게 울려요", "실제 요청도 같은 소리·진동·플래시로 울려요.\n끝나면 소리 설정은 원래대로 돌아가요.", light = false)
            else Header("${ui.peerName}님이 찾는 중", "여기 있어요!", "찾았으면 아래 버튼을 눌러 주세요.\n소리와 방해 금지 설정은 원래대로 돌아가요.", light = false)
            Box(Modifier.size(180.dp).onGloballyPositioned { bell = it.boundsInRoot().center }
                .clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(132.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = CoralDeep, modifier = Modifier.size(68.dp).rotate(shake))
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PillButton("찾았어요", null, Color.White, CoralDeep, Modifier.fillMaxWidth()) { RingService.found() }
                Spacer(Modifier.height(12.dp))
                Text("${ui.secondsLeft}초 뒤 저절로 멈춰요", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LocateAlert(ui: LocateUi) {
    when {
        ui.locating -> GlowBackdrop(Indigo) {
            Header("위치 요청", "위치를 보내는 중", "${ui.peerName}님에게 현재 위치를 한 번 보내요.\n보낸 기록은 기록 화면에 남아요.")
            Box(Modifier.size(160.dp).clip(CircleShape).background(Indigo.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.LocationOn, null, tint = Color.White, modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(1.dp))
        }
        ui.ask -> Countdown(
            accent = Indigo, icon = Icons.Rounded.LocationOn, eyebrow = "위치 요청",
            title = "${ui.peerName}님이\n내 위치를 확인하려고 해요", body = "허용하면 지금 위치를 한 번 보내요.\n보낸 기록은 기록 화면에 남아요.",
            seconds = ui.secondsLeft, total = LocateService.ASK_SEC,
        ) {
            PillButton("허용", null, Color.White, Night, Modifier.fillMaxWidth()) { LocateService.allow() }
            Spacer(Modifier.height(10.dp))
            GhostButton("거절") { LocateService.decline() }
        }
        else -> Countdown(
            accent = Indigo, icon = Icons.Rounded.LocationOn, eyebrow = "위치 요청",
            title = "${ui.peerName}님이\n내 위치를 확인하려고 해요", body = "잠시 뒤 현재 위치를 한 번 보내요.\n원하지 않으면 거절할 수 있어요.",
            seconds = ui.secondsLeft, total = LocateService.COUNTDOWN_SEC,
        ) { GhostButton("거절하기") { LocateService.decline() } }
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) =
    PillButton(text, null, Color.White, Color.White, Modifier.fillMaxWidth(), outlined = true, onClick = onClick)
