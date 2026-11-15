package io.github.jeiel85.findmywifephone.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.data.Peer
import io.github.jeiel85.findmywifephone.nearby.Closeness
import io.github.jeiel85.findmywifephone.nearby.NearbyPermissions
import io.github.jeiel85.findmywifephone.nearby.NearbyReading
import io.github.jeiel85.findmywifephone.nearby.NearbyScanner
import io.github.jeiel85.findmywifephone.nearby.Trend

/**
 * "따뜻해요 / 차가워요" while the other phone rings: listens for its Bluetooth beacon and shows a
 * closeness band, a cold-to-hot bar and whether walking around is getting warmer.
 */
@Composable
fun NearbyCard(peer: Peer) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val permitted = remember(tick, refresh) { NearbyPermissions.granted(context) }
    val btOn = remember(tick, refresh) { NearbyPermissions.bluetoothOn(context) }
    val theirs = peer.status?.beacon

    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(24.dp)).background(P.c.surface)
            .border(1.dp, P.c.hairline, RoundedCornerShape(24.dp)).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BluetoothSearching, null, tint = P.c.sofa, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("가까이 찾기", style = MaterialTheme.typography.labelSmall, color = P.c.sofa)
        }
        Spacer(Modifier.height(10.dp))
        val preview = NearbyDebug.reading.takeIf { BuildConfig.DEBUG }
        when {
            preview != null -> Gauge(preview)
            theirs == false -> Note("${peer.name} 폰의 블루투스가 꺼져 있어 거리는 알 수 없어요", "소리를 따라가 보세요.")
            !permitted -> Note("근처 기기 권한을 허용하면 얼마나 가까운지 보여 드려요", null) {
                PillButton("허용하기", null, P.c.sofa, P.c.surface, Modifier.padding(top = 12.dp)) { ask.launch(NearbyPermissions.required) }
            }
            !btOn -> Note("블루투스를 켜면 얼마나 가까운지 보여 드려요", null) {
                PillButton("블루투스 설정", null, P.c.sofa, P.c.surface, Modifier.padding(top = 12.dp)) {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }
            else -> Live(peer)
        }
    }
}

/** Debug builds only: DemoActivity shows the gauge with a fixed reading (no Bluetooth needed). */
object NearbyDebug { var reading: NearbyReading? = null }

@Composable
private fun Live(peer: Peer) {
    val context = LocalContext.current
    val scanner = remember(peer.id) { NearbyScanner(context.applicationContext, peer.pairKey) }
    // Scan only while the gauge is actually visible: Home or screen-off keeps the composition
    // alive, so disposal alone would leave Bluetooth scanning in the background (D-8).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(scanner, owner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> scanner.start()
                Lifecycle.Event.ON_STOP -> scanner.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer) // replays ON_START when already visible
        onDispose {
            owner.lifecycle.removeObserver(observer)
            scanner.stop()
        }
    }
    val r by scanner.reading.collectAsStateWithLifecycle()
    Gauge(r)
}

@Composable
private fun Gauge(r: NearbyReading) {
    val searching = !r.heard
    Text(if (searching) "신호를 찾는 중…" else r.closeness.label,
        style = MaterialTheme.typography.headlineMedium, color = if (r.closeness == Closeness.RIGHT_HERE) P.c.coralDeep else P.c.ink)
    Text(if (searching) "몇 초 걸릴 수 있어요" else r.closeness.hint, style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
    Spacer(Modifier.height(14.dp))

    // Cold (sofa indigo) → warm (butter) → hot (coral); the dot slides as the signal changes.
    val warmth by animateFloatAsState(r.warmth, tween(600), label = "warmth")
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)
        .semantics { contentDescription = "가까운 정도 ${(r.warmth * 100).toInt()}%" }) {
        Box(Modifier.fillMaxWidth().height(10.dp).align(Alignment.Center).clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(P.c.sofa, P.c.butter, P.c.coral))))
        val dot = 22.dp
        Box(Modifier.offset(x = (maxWidth - dot) * warmth).size(dot).clip(CircleShape).background(P.c.surface)
            .border(4.dp, if (r.heard) P.c.ink else P.c.hairline, CircleShape))
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("멀어요", fontSize = 12.sp, color = P.c.inkSoft, modifier = Modifier.weight(1f))
        Text("가까워요", fontSize = 12.sp, color = P.c.inkSoft)
    }
    Spacer(Modifier.height(10.dp))
    val (text, color, icon) = when {
        !r.heard || r.closeness == Closeness.NONE -> Triple("폰을 들고 천천히 방을 옮겨 다녀 보세요", P.c.inkSoft, null)
        r.trend == Trend.WARMER -> Triple("가까워지고 있어요", P.c.coralDeep, Icons.Rounded.NorthEast)
        r.trend == Trend.COLDER -> Triple("멀어지고 있어요", P.c.sofa, Icons.Rounded.SouthEast)
        else -> Triple("그대로예요 · 다른 방향으로 움직여 보세요", P.c.inkSoft, null)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
    Text("블루투스 세기로 어림잡은 거리라 벽이나 쿠션에 따라 달라질 수 있어요.", fontSize = 12.sp, color = P.c.inkSoft,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Note(title: String, body: String?, action: @Composable () -> Unit = {}) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = P.c.ink)
    if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
    action()
}
