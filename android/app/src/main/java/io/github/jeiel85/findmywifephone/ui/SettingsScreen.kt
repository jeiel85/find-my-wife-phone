package io.github.jeiel85.findmywifephone.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.core.Clock
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.LocationPolicy
import io.github.jeiel85.findmywifephone.core.PeerStatus
import io.github.jeiel85.findmywifephone.core.Registration
import io.github.jeiel85.findmywifephone.data.Peer
import io.github.jeiel85.findmywifephone.data.RING_LENGTHS
import io.github.jeiel85.findmywifephone.data.ThemeMode
import io.github.jeiel85.findmywifephone.net.RelayResult
import io.github.jeiel85.findmywifephone.ring.RingService
import kotlinx.coroutines.launch
import java.util.Calendar

private val LOCATION_CHOICES = listOf(
    Triple(LocationPolicy.AUTO, "알리고 10초 뒤 보내기", "그사이 거절할 수 있어요. 폰을 잃어버렸을 때도 찾을 수 있어요."),
    Triple(LocationPolicy.ASK, "내가 허용할 때만 보내기", "1분 안에 허용하지 않으면 보내지 않아요."),
    Triple(LocationPolicy.OFF, "보내지 않기", "위치 요청은 모두 거절돼요."),
)

/** Everything the owner can change, grouped by what it is about. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onPair: () -> Unit, onHistory: () -> Unit) {
    val settings by Hub.store.settings.collectAsStateWithLifecycle()
    val peers by Hub.store.peers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var snoozeSheet by remember { mutableStateOf(false) }
    var locationSheet by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var unpair by remember { mutableStateOf<Peer?>(null) }
    var removeRelay by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val registration by Hub.registration.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = P.c.ink) }
            Text("설정", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
        }
        Spacer(Modifier.height(8.dp))
        ProfileCard(settings.myName, peers.size, settings.snoozeUntil, onEdit = { editName = true })

        Group("받는 요청") {
            ListRow("요청 받지 않기",
                if (settings.snoozeUntil > now) snoozeLabel(settings.snoozeUntil) else "꺼짐 · 요청을 받는 중",
                Icons.Rounded.Bedtime, P.c.amber, onClick = { snoozeSheet = true }) { Chevron() }
            ListRow("위치 요청에 응답하는 방법", LOCATION_CHOICES.first { it.first == settings.locationPolicy }.second,
                Icons.Rounded.LocationOn, P.c.indigo, onClick = { locationSheet = true }) { Chevron() }
        }
        Note("어떤 방법이든 위치를 보낼 때마다 알림이 뜨고 기록에 남아요.")

        Group("울릴 때") {
            ListRow("울리는 시간", "‘찾았어요’를 누르지 않으면 이만큼 울리고 멈춰요", Icons.Rounded.Timer, P.c.coral)
            Segmented(
                RING_LENGTHS.map { it to if (it < 60) "${it}초" else "${it / 60}분" },
                selected = settings.ringSeconds,
                modifier = Modifier.padding(start = 58.dp, end = 4.dp, bottom = 8.dp),
            ) { sec -> Hub.store.updateSettings { it.copy(ringSeconds = sec) } }
            ToggleRow("진동도 함께", "소리와 같이 진동해요", Icons.Rounded.Vibration, P.c.indigo, settings.ringVibrate) { on ->
                Hub.store.updateSettings { it.copy(ringVibrate = on) }
            }
            ToggleRow("플래시 켜기", "울리는 동안 플래시를 켜 둬요. 쿠션 밑에서도 빛이 새어 나와요", Icons.Rounded.FlashlightOn, P.c.amber, settings.ringFlash) { on ->
                Hub.store.updateSettings { it.copy(ringFlash = on) }
            }
            val ringing by RingService.ui.collectAsStateWithLifecycle()
            ListRow("내 폰 울려 보기", "실제 요청처럼 ${RingService.TEST_SEC}초 동안 울려요. 무음·방해 금지에서도 울리는지 확인해 보세요.",
                Icons.Rounded.NotificationsActive, P.c.coral) {
                SmallButton(if (ringing?.test == true) "울리는 중" else "울려 보기", enabled = ringing == null) { RingService.test(context) }
            }
        }

        Group("가족") {
            if (peers.isEmpty()) ListRow("아직 연결된 가족이 없어요", "QR로 한 번만 연결하면 돼요", Icons.Rounded.PhoneAndroid, P.c.inkSoft)
            peers.forEach { p ->
                ListRow(p.name, "${p.model.ifBlank { "기기" }} · ${relativeTime(p.pairedAt)} 연결", Icons.Rounded.PhoneAndroid, P.c.coral) {
                    TextButton(onClick = { unpair = p }) { Text("연결 해제", color = P.c.danger, fontWeight = FontWeight.Bold) }
                }
            }
            ListRow("새 기기 연결", null, Icons.Rounded.Add, P.c.sage, onClick = onPair) { Chevron() }
        }

        Group("확실히 울리게 하는 설정") {
            if (Clock.skewed()) ClockRow(Clock.offsetMs()) {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS)) }
            }
            PermissionList(settings.locationPolicy)
        }

        Group("화면") {
            Text("테마", style = MaterialTheme.typography.titleMedium, color = P.c.ink,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp))
            Segmented(
                listOf(ThemeMode.SYSTEM to "시스템 따라", ThemeMode.LIGHT to "밝게", ThemeMode.DARK to "어둡게"),
                selected = settings.theme,
                modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 8.dp),
            ) { mode -> Hub.store.updateSettings { it.copy(theme = mode) } }
        }

        Group("업데이트") { UpdateSection(settings) }

        Group("기록과 정보") {
            ListRow("기록", "누가 언제 울리고 위치를 확인했는지", Icons.AutoMirrored.Rounded.ReceiptLong, P.c.ink, onClick = onHistory) { Chevron() }
            ListRow("개인정보와 보안", "중계 서버가 알 수 있는 것과 없는 것", Icons.Rounded.Security, P.c.indigo,
                onClick = { open("https://jeiel85.github.io/find-my-wife-phone/#privacy") }) { Chevron() }
            ListRow("오픈소스 · GitHub", "버전 ${BuildConfig.VERSION_NAME} · MIT 라이선스", Icons.Rounded.Code, P.c.ink,
                onClick = { open("https://github.com/jeiel85/find-my-wife-phone") }) { Chevron() }
            val pending = remember(registration, removing) { Hub.removalPending() }
            if (registration == Registration.Removed && pending) {
                // Pairs are gone already; only the relay's copy is left. Tapping retries the delete.
                ListRow("서버에서 아직 지우지 못했어요", removeError ?: "인터넷에 연결되면 앱을 열 때 다시 지워요. 눌러서 지금 다시 시도할 수 있어요",
                    Icons.Rounded.DeleteForever, P.c.danger, onClick = {
                        if (!removing) {
                            removing = true
                            scope.launch {
                                removeError = (Hub.removeFromRelay() as? RelayResult.Fail)?.let { "지우지 못했어요 · ${errorText(it.code)}" }
                                removing = false
                            }
                        }
                    }) { Chevron() }
            } else if (registration == Registration.Removed) {
                ListRow("서버에서 이 폰을 지웠어요", "다시 쓰려면 새 기기 연결로 QR을 주고받으세요", Icons.Rounded.DeleteForever, P.c.inkSoft)
            } else if (registration != Registration.NotConfigured) {
                ListRow("서버에서 이 폰 지우기", removeError ?: "앱을 지우기 전에 누르면 중계 서버에 남은 기기 ID·푸시 토큰·연결이 모두 지워져요",
                    Icons.Rounded.DeleteForever, P.c.danger, onClick = { removeRelay = true }) { Chevron() }
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (snoozeSheet) SnoozeSheet(onDismiss = { snoozeSheet = false })
    if (locationSheet) LocationSheet(settings.locationPolicy, onDismiss = { locationSheet = false })
    if (editName) NameDialog(settings.myName, onDismiss = { editName = false })
    if (removeRelay) {
        AlertDialog(
            onDismissRequest = { if (!removing) removeRelay = false },
            title = { Text("서버에서 이 폰을 지울까요?") },
            text = {
                Text(
                    (if (peers.isEmpty()) "" else "연결된 가족(${peers.joinToString { it.name }})과의 연결도 모두 해제돼요. ") +
                        "중계 서버에서 이 폰의 기기 ID, 푸시 토큰, 연결 정보를 지우고, 다시 연결하기 전까지 서버에 등록하지 않아요. " +
                        "인터넷이 끊겨 서버에서 바로 지우지 못하면 다음에 앱을 열 때 다시 지워요. " +
                        "폰에 남은 기록은 그대로예요.",
                )
            },
            confirmButton = {
                TextButton(enabled = !removing, onClick = {
                    removing = true
                    scope.launch {
                        val r = Hub.removeFromRelay()
                        removing = false
                        removeRelay = false
                        removeError = (r as? RelayResult.Fail)?.let { "지우지 못했어요 · ${errorText(it.code)}" }
                    }
                }) { Text(if (removing) "지우는 중…" else "지우기", color = P.c.danger) }
            },
            dismissButton = { TextButton(enabled = !removing, onClick = { removeRelay = false }) { Text("취소") } },
        )
    }
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

// ---- profile ---------------------------------------------------------------------------------

/** The one decorated piece: this phone, lit by the living-room lamp in the corner. */
@Composable
private fun ProfileCard(name: String, familyCount: Int, snoozeUntil: Long, onEdit: () -> Unit) {
    val registration by Hub.registration.collectAsStateWithLifecycle()
    val glow = P.c.butter
    val snoozed = snoozeUntil > System.currentTimeMillis()
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(P.c.surface)
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), glow.copy(alpha = 0f)),
                    center = Offset(size.width, 0f), radius = size.width * 0.6f),
                    radius = size.width * 0.6f, center = Offset(size.width, 0f))
            }
            .clickable(onClickLabel = "이름 바꾸기", onClick = onEdit)
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, P.c.coral, size = 60.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "이름 없음" }, style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
                    Text("${Hub.deviceLabel()} · v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
                }
                Icon(Icons.Rounded.Edit, "이름 바꾸기", tint = P.c.inkSoft, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snoozed) Chip("받지 않는 중", P.c.amber) else Chip("요청 받는 중", P.c.sage)
                Chip(if (familyCount == 0) "연결 전" else "가족 ${familyCount}명", P.c.indigo)
                when (registration) {
                    Registration.Ready -> Chip("푸시 연결됨", P.c.sage)
                    Registration.Working -> Chip("연결 중", P.c.inkSoft)
                    Registration.NotConfigured -> Chip("푸시 설정 없음", P.c.danger)
                    is Registration.Failed -> Chip("푸시 연결 안 됨", P.c.danger)
                    Registration.Removed -> Chip("서버에서 지움", P.c.inkSoft)
                }
            }
        }
    }
}

// ---- building blocks -------------------------------------------------------------------------

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(18.dp))
    SectionTitle(title)
    Card(Modifier.fillMaxWidth(), padding = 8.dp) { Column { content() } }
}

/**
 * This phone's clock is off from the relay's by [offsetMs] (relay minus phone). Requests between two
 * v0.3.6+ apps are judged on corrected time, but an older peer stamps or checks with its own clock.
 */
@Composable
private fun ClockRow(offsetMs: Long, onClick: () -> Unit) {
    val minutes = maxOf(1L, kotlin.math.abs(offsetMs) / 60_000)
    val off = if (offsetMs > 0) "${minutes}분 느려요" else "${minutes}분 빨라요"
    ListRow("이 폰 시계가 $off", "옛 버전 앱과 주고받는 찾기 요청이 늦게 온 것으로 무시될 수 있어요. 눌러서 날짜와 시간을 자동으로 맞추세요",
        Icons.Rounded.Schedule, P.c.danger, onClick = onClick) { Chevron() }
}

@Composable
private fun Note(text: String) =
    Text(text, fontSize = 13.sp, color = P.c.inkSoft, modifier = Modifier.padding(start = 6.dp, top = 8.dp))

@Composable
internal fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = P.c.inkSoft)

@Composable
internal fun ToggleRow(title: String, subtitle: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color,
                      checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(title, subtitle, icon, tint, onClick = { onChange(!checked) }) {
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = P.c.indigo, checkedThumbColor = P.c.surface))
    }
}

@Composable
internal fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Spacer(Modifier.width(8.dp))
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (enabled) P.c.coral else P.c.surfaceSoft)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, color = if (enabled) P.c.onCoral else P.c.inkSoft, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** A row of mutually exclusive pills; the chosen one sits on the sofa colour. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(P.c.surfaceSoft).padding(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val bg by animateColorAsState(if (on) P.c.indigo else P.c.surfaceSoft, label = "seg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(bg)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) P.c.surface else P.c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

// ---- sheets and dialogs ------------------------------------------------------------------------

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
                Hub.broadcastStatus()
                onDismiss()
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSheet(current: LocationPolicy, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = P.c.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("위치 요청에 응답하는 방법", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
            Spacer(Modifier.height(6.dp))
            Text("가족이 ‘위치 보기’를 눌렀을 때 이 폰이 어떻게 할지 정해요.", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
            Spacer(Modifier.height(12.dp))
            LOCATION_CHOICES.forEach { (policy, title, sub) ->
                ListRow(title, sub, onClick = {
                    Hub.store.updateSettings { it.copy(locationPolicy = policy) }
                    Hub.broadcastStatus()
                    onDismiss()
                }) {
                    RadioButton(selected = current == policy, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = P.c.indigo))
                }
            }
        }
    }
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
