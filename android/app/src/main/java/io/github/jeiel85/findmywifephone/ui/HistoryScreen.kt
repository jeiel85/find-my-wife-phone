package io.github.jeiel85.findmywifephone.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.data.Direction
import io.github.jeiel85.findmywifephone.data.HistoryEntry
import io.github.jeiel85.findmywifephone.data.Kind

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val entries by Hub.history.entries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shown = when (filter) {
        1 -> entries.filter { it.direction == Direction.IN }
        2 -> entries.filter { it.direction == Direction.OUT }
        else -> entries
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = P.c.ink) }
                Text("기록", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
            }
            Card(Modifier.fillMaxWidth().padding(top = 8.dp), padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = P.c.sage)
                    Spacer(Modifier.width(12.dp))
                    Text("누가 내 폰을 울리거나 위치를 확인했는지 모두 여기 남아요. 받은 요청 기록은 지울 수 없어요.",
                        style = MaterialTheme.typography.bodyMedium, color = P.c.ink)
                }
            }
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("전체", "받은 요청", "보낸 요청").forEachIndexed { i, t ->
                    Chip(t, if (filter == i) P.c.coral else P.c.inkSoft) { filter = i }
                }
            }
        }
        if (shown.isEmpty()) item { CenterNote("아직 기록이 없어요", "폰을 울리거나 위치를 확인하면 여기에 남아요.") }
        items(shown, key = { it.reqId + it.direction }) { HistoryRow(it) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry) {
    val context = LocalContext.current
    val (icon, tint) = when (e.kind) {
        Kind.RING -> Icons.Rounded.NotificationsActive to P.c.coral
        Kind.LOCATE -> Icons.Rounded.LocationOn to P.c.indigo
        Kind.PAIR -> Icons.Rounded.Link to P.c.sage
        Kind.UNPAIR -> Icons.Rounded.LinkOff to P.c.inkSoft
        Kind.SHARE -> Icons.Rounded.MyLocation to P.c.sage
        Kind.BATTERY -> Icons.Rounded.BatteryAlert to P.c.danger
    }
    Row(
        Modifier.fillMaxWidth()
            .then(if (e.lat != null && e.lng != null) Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${e.lat},${e.lng}?q=${e.lat},${e.lng}"))) }
            } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(headline(e), style = MaterialTheme.typography.titleMedium, color = P.c.ink)
            Text("${clockTime(e.at)} · ${outcomeText(e)}", fontSize = 13.sp, color = outcomeColor(e))
        }
        Text(if (e.direction == Direction.IN) "받음" else "보냄", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = P.c.inkSoft)
    }
}

private fun headline(e: HistoryEntry): String = when (e.kind) {
    Kind.RING -> if (e.direction == Direction.IN) "${e.peerName}님이 내 폰을 울렸어요" else "${e.peerName}님 폰을 울렸어요"
    Kind.LOCATE -> if (e.direction == Direction.IN) "${e.peerName}님이 내 위치를 요청했어요" else "${e.peerName}님 위치를 요청했어요"
    Kind.PAIR -> "${e.peerName}님과 연결"
    Kind.UNPAIR -> "${e.peerName}님과 연결 해제"
    Kind.SHARE -> if (e.direction == Direction.IN) "${e.peerName}님이 위치를 보냈어요" else "${e.peerName}님에게 내 위치를 보냈어요"
    Kind.BATTERY -> if (e.direction == Direction.IN) "${e.peerName}님 폰 배터리가 부족해요" else "${e.peerName}님에게 배터리 부족을 알렸어요"
}

private fun outcomeText(e: HistoryEntry): String = when {
    e.outcome == "shared" && e.kind == Kind.BATTERY ->
        if (e.direction == Direction.IN) "마지막 위치를 받았어요 (눌러서 보기)" else "마지막 위치를 함께 보냈어요 (눌러서 보기)"
    e.outcome == "shared" && e.kind == Kind.SHARE ->
        if (e.direction == Direction.IN) "위치를 받았어요 (눌러서 보기)" else "보냈어요 (눌러서 보기)"
    e.outcome == "shared" -> if (e.direction == Direction.IN) "위치를 보냈어요 (눌러서 보기)" else "위치를 받았어요 (눌러서 보기)"
    e.outcome.startsWith("failed:") -> "보내지 못함 · ${errorText(e.outcome.removePrefix("failed:"))}"
    else -> when (e.outcome) {
        "sent" -> "보냄"
        "received" -> "받음"
        "late" -> "늦게 도착해서 무시했어요"
        "no_response" -> "응답 없음"
        "countdown" -> "확인 대기"
        "asking" -> "허용 대기"
        "ringing" -> "울렸어요"
        "found" -> "찾았어요"
        "declined" -> "거절"
        "snoozed" -> "요청 받지 않기 중"
        "stopped" -> "중간에 멈춤"
        "timeout" -> "울리는 시간이 다 돼서 멈춤" // the length set then (1, 2 or 5 min) is not recorded
        "busy" -> "다른 요청 처리 중"
        "off" -> "위치 공유 꺼짐"
        "unavailable" -> "위치를 가져올 수 없음"
        "paired" -> "연결됨"
        "unpaired" -> "해제됨"
        else -> "오류"
    }
}

@Composable
private fun outcomeColor(e: HistoryEntry): Color = when (e.outcome) {
    "found", "shared", "paired" -> P.c.sage
    "declined", "no_response", "error", "unavailable" -> P.c.danger
    "snoozed" -> P.c.amber
    else -> if (e.outcome.startsWith("failed:")) P.c.danger else P.c.inkSoft
}
