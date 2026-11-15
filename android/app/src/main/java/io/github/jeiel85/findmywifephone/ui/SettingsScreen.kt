package io.github.jeiel85.findmywifephone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.LocationPolicy
import io.github.jeiel85.findmywifephone.core.PeerStatus
import io.github.jeiel85.findmywifephone.data.Peer
import java.util.Calendar

@Composable
fun SettingsScreen(onBack: () -> Unit, onPair: () -> Unit) {
    val settings by Hub.store.settings.collectAsStateWithLifecycle()
    val peers by Hub.store.peers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var snoozeSheet by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var unpair by remember { mutableStateOf<Peer?>(null) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = P.c.ink) }
            Text("설정", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
        }

        SectionTitle("내 폰")
        Card(Modifier.fillMaxWidth(), padding = 8.dp) {
            Column {
                ListRow("가족에게 보이는 이름", settings.myName, Icons.Rounded.Edit, P.c.ink, onClick = { editName = true })
                ListRow("요청 받지 않기",
                    if (settings.snoozeUntil > now) snoozeLabel(settings.snoozeUntil) else "꺼짐 · 요청을 받는 중",
                    Icons.Rounded.Bedtime, P.c.amber, onClick = { snoozeSheet = true })
            }
        }

        SectionTitle("위치 요청에 응답하는 방법")
        Card(Modifier.fillMaxWidth(), padding = 8.dp) {
            Column {
                listOf(
                    Triple(LocationPolicy.AUTO, "알리고 10초 뒤 보내기", "그사이 거절할 수 있어요. 폰을 잃어버렸을 때도 찾을 수 있어요."),
                    Triple(LocationPolicy.ASK, "내가 허용할 때만 보내기", "1분 안에 허용하지 않으면 보내지 않아요."),
                    Triple(LocationPolicy.OFF, "보내지 않기", "위치 요청은 모두 거절돼요."),
                ).forEach { (policy, title, sub) ->
                    ListRow(title, sub, onClick = {
                        Hub.store.updateSettings { it.copy(locationPolicy = policy) }
                        Hub.broadcastStatus()
                    }) {
                        RadioButton(selected = settings.locationPolicy == policy, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = P.c.indigo))
                    }
                }
            }
        }
        Text("어떤 방법이든 위치를 보낼 때마다 알림이 뜨고 기록에 남아요.", fontSize = 13.sp, color = P.c.inkSoft,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp))

        SectionTitle("확실히 울리게 하는 설정")
        Card(Modifier.fillMaxWidth(), padding = 8.dp) { PermissionList(settings.locationPolicy) }

        SectionTitle("연결된 가족")
        Card(Modifier.fillMaxWidth(), padding = 8.dp) {
            Column {
                peers.forEach { p ->
                    ListRow(p.name, "${p.model.ifBlank { "기기" }} · ${relativeTime(p.pairedAt)} 연결", Icons.Rounded.PhoneAndroid, P.c.coral) {
                        TextButton(onClick = { unpair = p }) { Text("연결 해제", color = P.c.danger, fontWeight = FontWeight.Bold) }
                    }
                }
                ListRow("새 기기 연결", null, Icons.Rounded.Add, P.c.sage, onClick = onPair)
            }
        }

        SectionTitle("정보")
        Card(Modifier.fillMaxWidth(), padding = 8.dp) {
            Column {
                ListRow("오픈소스 · GitHub", "버전 ${BuildConfig.VERSION_NAME}", Icons.Rounded.Code, P.c.ink, onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jeiel85/find-my-wife-phone")))
                })
                ListRow("개인정보와 보안", "중계 서버가 알 수 있는 것과 없는 것", Icons.Rounded.LocationOn, P.c.indigo, onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://jeiel85.github.io/find-my-wife-phone/#privacy")))
                })
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (snoozeSheet) SnoozeSheet(onDismiss = { snoozeSheet = false })
    if (editName) NameDialog(settings.myName, onDismiss = { editName = false })
    unpair?.let { p ->
        AlertDialog(
            onDismissRequest = { unpair = null },
            title = { Text("${p.name}님과 연결을 해제할까요?") },
            text = { Text("서로 폰을 울리거나 위치를 확인할 수 없게 돼요. 다시 쓰려면 QR로 다시 연결해야 해요.") },
            confirmButton = { TextButton(onClick = { Hub.unpair(p.id); unpair = null }) { Text("해제", color = P.c.danger) } },
            dismissButton = { TextButton(onClick = { unpair = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("가족에게 보이는 이름") },
        text = {
            OutlinedTextField(name, { name = it.take(20) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                placeholder = { Text("예: 아내, 남편, 엄마") })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                Hub.store.updateSettings { it.copy(myName = name.trim()) }
                onDismiss()
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSheet(onDismiss: () -> Unit) {
    val settings by Hub.store.settings.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    fun set(until: Long) {
        Hub.store.updateSettings { it.copy(snoozeUntil = until) }
        Hub.broadcastStatus()
        onDismiss()
    }
    val tomorrow8 = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, if (get(Calendar.HOUR_OF_DAY) < 8) 0 else 1)
        set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = P.c.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("요청 받지 않기", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
            Spacer(Modifier.height(6.dp))
            Text("이 시간 동안은 가족이 '울리기'나 '위치 보기'를 눌러도 소리를 내거나 위치를 보내지 않아요. 상대 화면에도 받지 않는 중이라고 보여요.",
                style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            Spacer(Modifier.height(12.dp))
            listOf(
                "30분" to now + 30 * 60_000L,
                "1시간" to now + 3_600_000L,
                "3시간" to now + 3 * 3_600_000L,
                "내일 아침 8시까지" to tomorrow8,
                "직접 끌 때까지" to PeerStatus.FOREVER,
            ).forEach { (label, until) -> ListRow(label, onClick = { set(until) }) }
            if (settings.snoozeUntil > now) {
                Spacer(Modifier.height(8.dp))
                PillButton("다시 요청 받기", null, P.c.sage, P.c.surface, Modifier.fillMaxWidth()) { set(0) }
            }
        }
    }
}
