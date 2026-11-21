package io.github.jeiel85.findmywifephone.ui

import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.versionName
import io.github.jeiel85.findmywifephone.core.LocateState
import io.github.jeiel85.findmywifephone.core.Msg
import io.github.jeiel85.findmywifephone.core.Outgoing
import io.github.jeiel85.findmywifephone.core.Registration
import io.github.jeiel85.findmywifephone.core.RequestKind
import io.github.jeiel85.findmywifephone.core.RingState
import io.github.jeiel85.findmywifephone.core.ShareNote
import io.github.jeiel85.findmywifephone.core.ShareOut
import io.github.jeiel85.findmywifephone.data.Peer
import io.github.jeiel85.findmywifephone.locate.NoFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun HomeScreen(onPair: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit,
               focusPeer: String? = null, onFocusConsumed: () -> Unit = {}) {
    val peers by Hub.store.peers.collectAsStateWithLifecycle()
    val settings by Hub.store.settings.collectAsStateWithLifecycle()
    val outgoing by Hub.outgoing.collectAsStateWithLifecycle()
    val registration by Hub.registration.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var snoozeSheet by remember { mutableStateOf(false) }
    // Opened from a shortcut or the Quick Settings tile: show the peer it acted on.
    LaunchedEffect(focusPeer) {
        if (focusPeer != null) { selected = focusPeer; onFocusConsumed() }
    }
    val peer = peers.firstOrNull { it.id == selected } ?: peers.firstOrNull()
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MyStatusPill(settings.snoozeUntil, now) { snoozeSheet = true }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, "기록", tint = P.c.ink) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "설정", tint = P.c.ink) }
        }

        RegistrationBanner(registration)

        if (peer == null) {
            EmptyState(onPair)
        } else {
            if (peers.size > 1) {
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    peers.forEach { p -> Chip(p.name, if (p.id == peer.id) P.c.coral else P.c.inkSoft) { selected = p.id } }
                }
            }
            PeerPanel(peer, outgoing[peer.id])
        }
        Spacer(Modifier.height(36.dp))
    }
    if (snoozeSheet) SnoozeSheet(onDismiss = { snoozeSheet = false })
}

@Composable
private fun MyStatusPill(snoozeUntil: Long, now: Long, onClick: () -> Unit) {
    val snoozed = snoozeUntil > now
    val color = if (snoozed) P.c.amber else P.c.sage
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(P.c.surface).border(1.dp, P.c.hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (snoozed) Icon(Icons.Rounded.Bedtime, null, tint = color, modifier = Modifier.size(16.dp)) else StatusDot(color, live = true)
        Spacer(Modifier.width(8.dp))
        Text(if (snoozed) "받지 않는 중 · ${snoozeLabel(snoozeUntil)}" else "내 폰은 요청 받는 중",
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = P.c.ink)
    }
}

@Composable
private fun RegistrationBanner(r: Registration) {
    val text = when (r) {
        Registration.NotConfigured -> "이 빌드에는 푸시 설정이 없어 다른 폰과 연결할 수 없어요."
        is Registration.Failed -> "중계 서버에 연결하지 못했어요 · ${errorText(r.code)}"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(18.dp))
            .background(P.c.danger.copy(alpha = 0.1f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = P.c.danger)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = P.c.ink, modifier = Modifier.weight(1f))
        if (r is Registration.Failed) {
            Text("다시 시도", color = P.c.coralDeep, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Hub.onNewFcmToken() }.padding(8.dp))
        }
    }
}

@Composable
private fun EmptyState(onPair: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SofaScene(Modifier.fillMaxWidth().aspectRatio(560f / 380f))
        Spacer(Modifier.height(8.dp))
        Text("“여보, 내 폰 어디 있어?”", color = P.c.sofa, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Text("가족 폰과 연결해 두면\n버튼 한 번으로 울릴 수 있어요", style = MaterialTheme.typography.headlineMedium,
            color = P.c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("한쪽 폰에서 QR을 띄우고 다른 폰으로 스캔하면 끝이에요.\n집 안이든 밖이든, 인터넷만 되면 돼요.",
            style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        PillButton("가족 폰 연결하기", Icons.Rounded.QrCodeScanner, P.c.coral, P.c.onCoral, Modifier.fillMaxWidth(), onClick = onPair)
    }
}

@Composable
private fun PeerAvatar(name: String, online: Boolean) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(P.c.coral.lighten(0.15f), P.c.coralDeep))),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.trim().take(1).ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp)
        }
        Box(Modifier.size(18.dp).clip(CircleShape).background(P.c.wallTop).padding(3.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(if (online) P.c.sage else P.c.inkSoft.copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun PeerPanel(peer: Peer, out: Outgoing?) {
    val now = System.currentTimeMillis()
    val sharedAll by Hub.store.shared.collectAsStateWithLifecycle()
    val shareOutAll by Hub.shareOut.collectAsStateWithLifecycle()
    var shareSheet by remember { mutableStateOf(false) }
    val st = peer.status
    val snoozed = st?.snoozed(now) == true
    val recent = peer.seenAt > 0 && now - peer.seenAt < 15 * 60_000

    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        PeerAvatar(peer.name, recent)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.headlineLarge, color = P.c.ink, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("응답 ${relativeTime(peer.seenAt)}", fontSize = 13.sp, color = P.c.inkSoft)
                if (st != null && st.battery >= 0) {
                    Text("  ·  ", fontSize = 13.sp, color = P.c.inkSoft)
                    Icon(if (st.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, "배터리",
                        tint = if (st.battery <= 15 && !st.charging) P.c.danger else P.c.inkSoft, modifier = Modifier.size(15.dp))
                    Text("${st.battery}%", fontSize = 13.sp, color = P.c.inkSoft, fontWeight = FontWeight.SemiBold)
                }
            }
            if (peer.model.isNotBlank()) Text(peer.model, fontSize = 12.sp, color = P.c.inkSoft.copy(alpha = 0.8f))
            if (st != null && st.appVersion < BuildConfig.VERSION_CODE) {
                // Older phones miss newer fixes (e.g. 0.3.1 and below ring on when the stop overtakes the ring).
                Text("${peer.name}님 폰의 포너디가 옛 버전(${if (st.appVersion > 0) versionName(st.appVersion) else "0.3.4 이하"})이에요 · 업데이트를 권해요",
                    fontSize = 12.sp, color = P.c.amber, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (snoozed) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(16.dp))
                .background(P.c.butter.copy(alpha = if (P.c.dark) 0.16f else 0.35f)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Bedtime, null, tint = P.c.amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("${peer.name}님은 지금 요청을 받지 않아요 · ${snoozeLabel(st!!.snoozeUntil)}", fontSize = 14.sp,
                color = P.c.ink, fontWeight = FontWeight.SemiBold)
        }
    }

    val ringOut = out?.takeIf { it.kind == RequestKind.RING }
    val ringActive = ringOut?.active == true
    val ringing = ringActive && ringOut?.ring == RingState.RINGING
    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Orb(
            label = when { ringOut?.stopping == true -> "멈추는 중"; ringing -> "그만 울리기"; ringActive -> "취소"; else -> "울리기" },
            icon = if (ringActive) Icons.Rounded.Stop else Icons.Rounded.NotificationsActive,
            mode = when { ringing -> OrbMode.RINGING; ringActive -> OrbMode.WAITING; else -> OrbMode.IDLE },
            color = if (ringActive) (if (P.c.dark) P.c.sofaSoft else P.c.ink) else P.c.coral,
            deep = if (ringActive) Color(0xFF0E1024) else P.c.coralDeep,
            ringColor = P.c.coral,
            description = if (ringActive) "울리기 멈추기" else "${peer.name} 폰 울리기",
        ) { if (ringActive) Hub.stopRing(peer.id) else Hub.ring(peer.id) }
    }

    AnimatedVisibility(out != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        out?.let { RequestCard(peer, it) }
    }
    AnimatedVisibility(ringing, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        NearbyCard(peer)
    }
    val asked = out?.result?.takeIf { out.kind == RequestKind.LOCATE }
    val sent = sharedAll[peer.id]
    // The newer of "what I asked for" and "what they sent on their own" is the one worth showing.
    when {
        asked != null && (sent == null || asked.fixAt >= sent.fixAt) ->
            LocationCard(asked.reqId, asked.lat, asked.lng, asked.accuracyM, asked.fixAt, "${peer.name}님은 여기 있어요", peer.name)
        sent != null -> LocationCard("shared:${sent.receivedAt}", sent.lat, sent.lng, sent.accuracyM, sent.fixAt,
            "${peer.name}님: ${sent.note.label}", peer.name, onClose = { Hub.store.dismissShared(peer.id) })
    }

    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val locateActive = out?.kind == RequestKind.LOCATE && out.active
        Tile("위치 보기", if (locateActive) "확인하는 중…" else "한 번만 요청해요", Icons.Rounded.LocationOn, P.c.sofa,
            Modifier.weight(1f), enabled = !locateActive) { Hub.locate(peer.id) }
        val sharing = shareOutAll[peer.id]?.busy == true
        Tile("내 위치 보내기", if (sharing) "보내는 중…" else "도착했어요 · 출발해요", Icons.Rounded.MyLocation, P.c.sage,
            Modifier.weight(1f), enabled = !sharing) { shareSheet = true }
    }
    shareOutAll[peer.id]?.let { ShareOutCard(peer, it) }
    if (shareSheet) ShareSheet(peer.name, onDismiss = { shareSheet = false }) { note ->
        shareSheet = false
        Hub.shareLocation(peer.id, note)
    }
}

/** Where "내 위치 보내기" is, under the tiles; closable once it is done. */
@Composable
private fun ShareOutCard(peer: Peer, s: ShareOut) {
    val (text, color) = when (s) {
        ShareOut.Locating -> "내 위치를 찾는 중…" to P.c.ink
        ShareOut.Sending -> "보내는 중…" to P.c.ink
        is ShareOut.Sent -> "${peer.name}님에게 \u2018${s.note.label}\u2019를 위치와 함께 보냈어요" to P.c.sage
        is ShareOut.NoLocation -> when (s.reason) {
            NoFix.PERMISSION -> "위치 권한이 없어요. 설정 → 권한 점검에서 허용해 주세요"
            NoFix.LOCATION_OFF -> "폰의 위치(GPS)가 꺼져 있어요. 켜고 다시 해 주세요"
            NoFix.NO_FIX -> "지금 위치를 잡지 못했어요. 창가나 밖에서 다시 해 주세요"
        } to P.c.danger
        is ShareOut.Failed -> "보내지 못했어요 · ${errorText(s.code)}" to P.c.danger
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp)).background(P.c.surface)
            .border(1.dp, P.c.hairline, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (!s.busy) {
            Icon(Icons.Rounded.Close, "닫기", tint = P.c.inkSoft,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { Hub.dismissShareOut(peer.id) }.padding(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(name: String, onDismiss: () -> Unit, onPick: (ShareNote) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = P.c.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("내 위치 보내기", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
            Spacer(Modifier.height(6.dp))
            Text("지금 위치를 한 번 잡아 ${name}님에게 한마디와 함께 보내요. ${name}님 폰 화면과 기록에 남아요.",
                style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            Spacer(Modifier.height(12.dp))
            ShareNote.entries.forEach { note -> ListRow(note.label, onClick = { onPick(note) }) }
        }
    }
}

/** Where a request is: the real step sequence, a plain sentence, and how to dismiss it. */
@Composable
private fun RequestCard(peer: Peer, o: Outgoing) {
    val (steps, current, failedAt, done) = progress(o)
    val accent = if (o.kind == RequestKind.RING) P.c.coral else P.c.sofa
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(24.dp)).background(P.c.surface)
            .border(1.dp, P.c.hairline, RoundedCornerShape(24.dp)).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (o.kind == RequestKind.RING) "울리기 요청" else "위치 요청", style = MaterialTheme.typography.labelSmall,
                color = accent, modifier = Modifier.weight(1f))
            if (!o.active) {
                Icon(Icons.Rounded.Close, "닫기", tint = P.c.inkSoft,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { Hub.dismissOutgoing(peer.id) }.padding(4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        StepTracker(steps, current, accent, failedAt, done)
        Spacer(Modifier.height(14.dp))
        Text(describe(o, peer.name), style = MaterialTheme.typography.titleMedium, color = statusColor(o))
    }
}

private data class Progress(val steps: List<String>, val current: Int, val failedAt: Int?, val done: Boolean)

private fun progress(o: Outgoing): Progress {
    if (o.kind == RequestKind.RING) {
        val steps = listOf("보냄", "확인 10초", "울리는 중", "찾음")
        return when {
            o.sendError != null -> Progress(steps, 0, 0, false)
            o.noResponse -> Progress(steps, 1, 1, false)
            else -> when (o.ring) {
                null -> Progress(steps, 0, null, false)
                RingState.COUNTDOWN -> Progress(steps, 1, null, false)
                RingState.RINGING -> Progress(steps, 2, null, false)
                RingState.FOUND -> Progress(steps, 3, null, true)
                RingState.TIMEOUT, RingState.STOPPED -> Progress(steps, 2, null, true)
                RingState.DECLINED, RingState.SNOOZED, RingState.BUSY, RingState.ERROR -> Progress(steps, 1, 1, false)
            }
        }
    }
    val steps = listOf("보냄", "확인", "찾는 중", "받음")
    return when {
        o.result != null -> Progress(steps, 3, null, true)
        o.sendError != null -> Progress(steps, 0, 0, false)
        o.noResponse -> Progress(steps, 1, 1, false)
        else -> when (o.locate) {
            null -> Progress(steps, 0, null, false)
            LocateState.COUNTDOWN, LocateState.ASKING -> Progress(steps, 1, null, false)
            LocateState.LOCATING -> Progress(steps, 2, null, false)
            LocateState.UNAVAILABLE, LocateState.ERROR -> Progress(steps, 2, 2, false)
            else -> Progress(steps, 1, 1, false)
        }
    }
}

@Composable
private fun LocationCard(key: String, lat: Double, lng: Double, accuracyM: Float, fixAt: Long, title: String, name: String,
                         onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    var address by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        address = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.KOREA).getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
            }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(24.dp)).background(P.c.surface)
            .border(1.dp, P.c.hairline, RoundedCornerShape(24.dp)).padding(10.dp),
    ) {
        MiniMap(lat, lng, accuracyM, Modifier.fillMaxWidth().height(190.dp))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = P.c.ink, modifier = Modifier.weight(1f))
                if (onClose != null) {
                    Icon(Icons.Rounded.Close, "닫기", tint = P.c.inkSoft,
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(address ?: String.format(Locale.US, "%.5f, %.5f", lat, lng), style = MaterialTheme.typography.bodyMedium, color = P.c.ink)
            Text("${clockTime(fixAt)} 기준 · 오차 약 ${accuracyM.toInt()}m", fontSize = 13.sp, color = P.c.inkSoft)
            Spacer(Modifier.height(14.dp))
            PillButton("지도 앱에서 길찾기", Icons.Rounded.Directions, P.c.sofa, Color.White, Modifier.fillMaxWidth()) {
                val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng"))) }
            }
        }
    }
}

@Composable
private fun statusColor(o: Outgoing): Color = when {
    o.sendError != null || o.noResponse -> P.c.danger
    o.stopError != null && o.active -> P.c.danger
    o.ring == RingState.FOUND || o.result != null -> P.c.sage
    o.ring == RingState.RINGING -> P.c.coralDeep
    o.ring == RingState.SNOOZED || o.locate == LocateState.SNOOZED -> P.c.amber
    o.ring == RingState.DECLINED || o.locate == LocateState.DECLINED -> P.c.danger
    else -> P.c.ink
}

fun describe(o: Outgoing, name: String): String {
    o.sendError?.let { return "보내지 못했어요 · ${errorText(it)}" }
    if (!o.sent) return "요청을 보내는 중…"
    if (o.noResponse) return if (o.uncertain) "전해졌는지 확인하지 못했어요. 인터넷 연결을 확인하고 다시 해 주세요"
        else "응답이 없어요. ${name}님 폰이 꺼져 있거나 인터넷에 연결되지 않았을 수 있어요."
    if (o.kind == RequestKind.RING && o.active) {
        if (o.stopping) return "멈추는 중…"
        o.stopError?.let { return "멈추지 못했어요 · ${errorText(it)}. 다시 눌러 주세요" }
    }
    return when (o.kind) {
        RequestKind.RING -> when (o.ring) {
            // The send's answer was lost, but the relay may have passed it on: it may still ring.
            null -> if (o.uncertain) "연결이 끊겨 전해졌는지 몰라요 · 울릴 수도 있어요" else "요청을 보냈어요 · 응답 기다리는 중…"
            RingState.COUNTDOWN -> "${name}님 폰에 요청이 떴어요. 곧 울려요"
            RingState.RINGING -> "울리는 중이에요 🔔 소리를 따라가 보세요"
            RingState.FOUND -> "찾았대요! 🎉"
            RingState.DECLINED -> "${name}님이 요청을 거절했어요"
            RingState.SNOOZED -> "${name}님은 지금 요청을 받지 않고 있어요"
            RingState.STOPPED -> "울림을 멈췄어요"
            RingState.TIMEOUT -> "울리는 시간이 다 돼서 멈췄어요"
            RingState.BUSY -> "이미 다른 요청으로 울리는 중이에요"
            RingState.ERROR -> "${name}님 폰에서 소리를 내지 못했어요"
        }
        RequestKind.LOCATE -> when {
            o.result != null -> "위치가 도착했어요"
            else -> when (o.locate) {
                null -> if (o.uncertain) "연결이 끊겨 전해졌는지 몰라요 · 응답 기다리는 중…" else "요청을 보냈어요 · 응답 기다리는 중…"
                LocateState.COUNTDOWN -> "${name}님에게 알림이 갔어요. 곧 위치가 도착해요"
                LocateState.ASKING -> "${name}님이 허용하면 위치가 도착해요"
                LocateState.LOCATING -> "위치를 찾는 중…"
                LocateState.DECLINED -> "${name}님이 위치 공유를 거절했어요"
                LocateState.SNOOZED -> "${name}님은 지금 요청을 받지 않고 있어요"
                LocateState.OFF -> "${name}님이 위치 공유를 꺼 두었어요"
                LocateState.UNAVAILABLE -> "${name}님 폰의 위치를 가져올 수 없어요 (권한 또는 위치 꺼짐)"
                LocateState.ERROR -> "위치를 받지 못했어요"
            }
        }
    }
}

fun errorText(code: String): String = when (code) {
    "network" -> "인터넷 연결을 확인해 주세요"
    "peer_unreachable" -> "상대 폰이 알림을 받을 수 없는 상태예요"
    "not_paired" -> "연결이 끊겼어요. 다시 연결해 주세요"
    "rate_limited" -> "요청이 너무 잦아요. 잠시 뒤에 해 주세요"
    "not_registered", "unauthorized" -> "중계 서버 등록이 안 됐어요"
    "invite_not_found" -> "QR이 이미 쓰였거나 없어요. 새 QR로 다시 해 주세요"
    "invite_expired" -> "QR 유효 시간이 지났어요. 새 QR로 다시 해 주세요"
    "self_pairing" -> "같은 폰끼리는 연결할 수 없어요"
    "push_failed" -> "푸시 전송에 실패했어요. 잠시 뒤 다시 해 주세요"
    "fcm" -> "Google Play 서비스 연결을 확인해 주세요"
    else -> code
}
