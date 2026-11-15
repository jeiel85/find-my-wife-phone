package io.github.jeiel85.findmywifephone.ui

import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.LocateState
import io.github.jeiel85.findmywifephone.core.Msg
import io.github.jeiel85.findmywifephone.core.Outgoing
import io.github.jeiel85.findmywifephone.core.Registration
import io.github.jeiel85.findmywifephone.core.RequestKind
import io.github.jeiel85.findmywifephone.core.RingState
import io.github.jeiel85.findmywifephone.data.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun HomeScreen(onPair: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    val peers by Hub.store.peers.collectAsStateWithLifecycle()
    val settings by Hub.store.settings.collectAsStateWithLifecycle()
    val outgoing by Hub.outgoing.collectAsStateWithLifecycle()
    val registration by Hub.registration.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var snoozeSheet by remember { mutableStateOf(false) }
    val peer = peers.firstOrNull { it.id == selected } ?: peers.firstOrNull()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("폰 찾기", style = MaterialTheme.typography.headlineMedium, color = P.c.ink)
            }
            IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, "기록", tint = P.c.ink) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "설정", tint = P.c.ink) }
        }
        val now = System.currentTimeMillis()
        Spacer(Modifier.height(4.dp))
        if (settings.snoozeUntil > now) {
            Chip("요청 받지 않는 중 · ${snoozeLabel(settings.snoozeUntil)}", P.c.amber, Icons.Rounded.Bedtime) { snoozeSheet = true }
        } else {
            Chip("내 폰: 요청 받는 중", P.c.sage, Icons.Rounded.CheckCircle) { snoozeSheet = true }
        }

        RegistrationBanner(registration)

        if (peer == null) {
            EmptyState(onPair)
        } else {
            if (peers.size > 1) {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    peers.forEach { p ->
                        Chip(p.name, if (p.id == peer.id) P.c.coral else P.c.inkSoft) { selected = p.id }
                    }
                }
            }
            PeerPanel(peer, outgoing[peer.id])
        }
        Spacer(Modifier.height(32.dp))
    }
    if (snoozeSheet) SnoozeSheet(onDismiss = { snoozeSheet = false })
}

@Composable
private fun RegistrationBanner(r: Registration) {
    val text = when (r) {
        Registration.NotConfigured -> "이 빌드에는 푸시 설정(google-services.json)이 없어 다른 폰과 연결할 수 없어요."
        is Registration.Failed -> "중계 서버에 연결하지 못했어요 (${errorText(r.code)}). 인터넷 연결을 확인해 주세요."
        else -> return
    }
    Card(Modifier.fillMaxWidth().padding(top = 16.dp), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = P.c.danger)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = P.c.ink, modifier = Modifier.weight(1f))
            if (r is Registration.Failed) {
                Text("다시 시도", color = P.c.coral, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { Hub.onNewFcmToken() }.padding(8.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(onPair: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Pulse(P.c.coral, active = true, modifier = Modifier.fillMaxSize())
            Box(Modifier.size(112.dp).clip(CircleShape).background(P.c.coral.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.NotificationsActive, null, tint = P.c.coral, modifier = Modifier.size(48.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("가족 폰과 연결해 보세요", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
        Spacer(Modifier.height(8.dp))
        Text("한쪽 폰에서 QR을 띄우고 다른 폰으로 스캔하면 끝이에요.\n집 안이든 밖이든, 인터넷만 되면 서로 찾을 수 있어요.",
            style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        PillButton("연결하기", Icons.Rounded.QrCodeScanner, P.c.coral, P.c.onCoral, onClick = onPair)
    }
}

@Composable
private fun PeerPanel(peer: Peer, out: Outgoing?) {
    val now = System.currentTimeMillis()
    val st = peer.status
    val snoozed = st?.snoozed(now) == true

    Card(Modifier.fillMaxWidth().padding(top = 16.dp), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(peer.name, P.c.coral, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleLarge, color = P.c.ink)
                Text(listOfNotNull(peer.model.takeIf { it.isNotBlank() }, "마지막 응답 ${relativeTime(peer.seenAt)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            }
            if (st != null && st.battery >= 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (st.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.Battery5Bar, "배터리",
                        tint = if (st.battery <= 15 && !st.charging) P.c.danger else P.c.inkSoft, modifier = Modifier.size(18.dp))
                    Text("${st.battery}%", color = P.c.inkSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (snoozed) {
        Spacer(Modifier.height(10.dp))
        Chip("${peer.name}님은 요청을 받지 않는 중 · ${snoozeLabel(st!!.snoozeUntil)}", P.c.amber, Icons.Rounded.Bedtime)
    }

    val ringActive = out?.kind == RequestKind.RING && out.active
    val ringing = ringActive && out?.ring == RingState.RINGING
    Spacer(Modifier.height(28.dp))
    Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
        Pulse(P.c.coral, active = ringActive, modifier = Modifier.size(280.dp))
        val color by animateColorAsState(if (ringActive) P.c.ink else P.c.coral, label = "btn")
        Column(
            Modifier
                .size(196.dp)
                .shadow(if (ringActive) 0.dp else 18.dp, CircleShape, spotColor = P.c.coral)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.Button) { if (ringActive) Hub.stopRing(peer.id) else Hub.ring(peer.id) }
                .semantics { contentDescription = if (ringActive) "그만 울리기" else "${peer.name} 폰 울리기" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(if (ringActive) Icons.Rounded.Stop else Icons.Rounded.NotificationsActive, null,
                tint = Color.White, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(6.dp))
            Text(if (ringing) "그만 울리기" else if (ringActive) "취소" else "울리기",
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
    }

    val statusText = out?.let { describe(it, peer.name) }
    AnimatedVisibility(statusText != null) {
        Text(statusText.orEmpty(), Modifier.fillMaxWidth().padding(horizontal = 8.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium, color = statusColor(out))
    }
    if (out?.noResponse == true || (out != null && !out.active && out.kind == RequestKind.RING && out.ring != RingState.FOUND)) {
        Spacer(Modifier.height(6.dp))
        Text("닫기", Modifier.fillMaxWidth().clickable { Hub.dismissOutgoing(peer.id) }.padding(8.dp),
            textAlign = TextAlign.Center, color = P.c.inkSoft, fontSize = 14.sp)
    }

    Spacer(Modifier.height(20.dp))
    val locateActive = out?.kind == RequestKind.LOCATE && out.active
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        PillButton(if (locateActive) "위치 확인 중…" else "위치 보기", Icons.Rounded.LocationOn, P.c.indigo, Color.White,
            outlined = true, enabled = !locateActive) { Hub.locate(peer.id) }
    }
    out?.result?.takeIf { out.kind == RequestKind.LOCATE }?.let { LocationCard(peer.name, it) }
}

@Composable
private fun LocationCard(name: String, r: Msg.LocateResult) {
    val context = LocalContext.current
    var address by remember(r.reqId) { mutableStateOf<String?>(null) }
    LaunchedEffect(r.reqId) {
        address = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.KOREA).getFromLocation(r.lat, r.lng, 1)?.firstOrNull()?.getAddressLine(0)
            }.getOrNull()
        }
    }
    Card(Modifier.fillMaxWidth().padding(top = 18.dp), padding = 18.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(P.c.indigo.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.LocationOn, null, tint = P.c.indigo)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${name}님의 위치", style = MaterialTheme.typography.titleMedium, color = P.c.ink)
                    Text("${clockTime(r.fixAt)} 기준 · 오차 약 ${r.accuracyM.toInt()}m", fontSize = 13.sp, color = P.c.inkSoft)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(address ?: String.format(Locale.US, "%.5f, %.5f", r.lat, r.lng),
                style = MaterialTheme.typography.bodyMedium, color = P.c.ink)
            Spacer(Modifier.height(14.dp))
            PillButton("지도에서 보기", Icons.Rounded.Map, P.c.indigo, Color.White, modifier = Modifier.fillMaxWidth()) {
                val uri = Uri.parse("geo:${r.lat},${r.lng}?q=${r.lat},${r.lng}(${Uri.encode(name)})")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://maps.google.com/?q=${r.lat},${r.lng}")))
                    }
            }
        }
    }
}

@Composable
private fun statusColor(out: Outgoing?): Color = when {
    out == null -> P.c.inkSoft
    out.sendError != null || out.noResponse -> P.c.danger
    out.ring == RingState.FOUND -> P.c.sage
    out.ring == RingState.RINGING -> P.c.coralDeep
    out.ring == RingState.SNOOZED || out.locate == LocateState.SNOOZED -> P.c.amber
    else -> P.c.ink
}

fun describe(o: Outgoing, name: String): String {
    o.sendError?.let { return "보내지 못했어요 · ${errorText(it)}" }
    if (!o.sent) return "요청을 보내는 중…"
    if (o.noResponse) return "응답이 없어요. ${name}님 폰이 꺼져 있거나 인터넷에 연결되지 않았을 수 있어요."
    return when (o.kind) {
        RequestKind.RING -> when (o.ring) {
            null -> "요청을 보냈어요 · 응답 기다리는 중…"
            RingState.COUNTDOWN -> "${name}님 폰에 요청이 떴어요. 곧 울려요"
            RingState.RINGING -> "울리는 중이에요 🔔"
            RingState.FOUND -> "찾았대요! 🎉"
            RingState.DECLINED -> "${name}님이 요청을 거절했어요"
            RingState.SNOOZED -> "${name}님은 지금 요청을 받지 않고 있어요"
            RingState.STOPPED -> "울림을 멈췄어요"
            RingState.TIMEOUT -> "2분 동안 울리고 멈췄어요"
            RingState.BUSY -> "이미 다른 요청으로 울리는 중이에요"
            RingState.ERROR -> "${name}님 폰에서 소리를 내지 못했어요"
        }
        RequestKind.LOCATE -> when {
            o.result != null -> "위치를 받았어요"
            else -> when (o.locate) {
                null -> "요청을 보냈어요 · 응답 기다리는 중…"
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
