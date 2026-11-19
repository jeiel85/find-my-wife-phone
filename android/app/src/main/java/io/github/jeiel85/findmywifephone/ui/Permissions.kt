package io.github.jeiel85.findmywifephone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.DoNotDisturbOff
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.jeiel85.findmywifephone.core.LocationPolicy
import io.github.jeiel85.findmywifephone.nearby.NearbyPermissions

data class Check(
    val key: String,
    val title: String,
    val why: String,
    val icon: ImageVector,
    val granted: Boolean,
    val required: Boolean,
)

object Checks {
    private fun has(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun all(context: Context, policy: LocationPolicy): List<Check> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val list = mutableListOf(
            Check("notify", "알림", "요청이 왔을 때 화면에 띄우려면 필요해요", Icons.Rounded.Notifications,
                Build.VERSION.SDK_INT < 33 || has(context, Manifest.permission.POST_NOTIFICATIONS), true),
            Check("fullscreen", "잠금 화면 위에 표시", "잠긴 폰에서도 거절·찾았어요 화면을 띄워요", Icons.Rounded.Fullscreen,
                Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(), true),
            Check("dnd", "방해 금지 모드에서도 울리기", "방해 금지 중에도 소리를 내고, 끝나면 원래대로 돌려놔요", Icons.Rounded.DoNotDisturbOff,
                nm.isNotificationPolicyAccessGranted, true),
            Check("nearby", "근처 기기 (블루투스)", "울릴 때 서로 얼마나 가까운지 보여 줘요 (권장)", Icons.Rounded.BluetoothSearching,
                Build.VERSION.SDK_INT < 31 || NearbyPermissions.granted(context), false),
            Check("battery", "배터리 최적화 제외", "절전 중에도 요청을 놓치지 않게 해요 (권장)", Icons.Rounded.BatteryAlert,
                pm.isIgnoringBatteryOptimizations(context.packageName), false),
        )
        if (policy != LocationPolicy.OFF) {
            list += Check("location", "위치", "가족이 내 위치를 요청했을 때 보내려면 필요해요", Icons.Rounded.MyLocation,
                has(context, Manifest.permission.ACCESS_FINE_LOCATION), true)
            list += Check("bglocation", "위치 · 항상 허용", "앱을 안 켜 둔 상태에서도 위치를 보내려면 '항상 허용'이 필요해요", Icons.Rounded.TravelExplore,
                has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION), true)
        }
        return list
    }
}

/** Re-reads permission state every time the screen resumes (users come back from Settings). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@SuppressLint("BatteryLife") // the whole point of the app is reacting to pushes while idle
@Composable
fun PermissionList(policy: LocationPolicy) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    // Refused twice ("허용 안 함" again): Android stops showing its prompt, only app settings can grant it.
    var blocked by remember { mutableStateOf(setOf<String>()) }
    var showBlocked by remember { mutableStateOf<String?>(null) }
    fun noteDenied(key: String, permission: String) {
        val activity = context.findActivity() ?: return
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED &&
            !activity.shouldShowRequestPermissionRationale(permission)) blocked = blocked + key
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        if (!ok && Build.VERSION.SDK_INT >= 33) noteDenied("notify", Manifest.permission.POST_NOTIFICATIONS)
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        refresh++
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] != true) noteDenied("location", Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val bgLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        if (!ok) noteDenied("bglocation", Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        refresh++
        res.entries.firstOrNull { !it.value }?.let { noteDenied("nearby", it.key) }
    }
    val checks = remember(tick, refresh, policy) { Checks.all(context, policy) }
    var disclose by remember { mutableStateOf<String?>(null) }

    disclose?.let { key ->
        LocationDisclosure(background = key == "bglocation", onDismiss = { disclose = null }) {
            disclose = null
            if (key == "bglocation" && checks.first { it.key == "location" }.granted) {
                bgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    showBlocked?.let { title ->
        AlertDialog(
            onDismissRequest = { showBlocked = null },
            title = { Text("‘$title’ 권한이 막혀 있어요") },
            text = {
                Text("‘허용 안 함’을 두 번 고르면 앱에서 다시 물어볼 수 없어요. 앱 설정의 ‘권한’에서 직접 허용해 주세요." +
                    if (title.startsWith("위치")) " 위치는 ‘항상 허용’을 골라 주세요." else "")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlocked = null
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("앱 설정 열기") }
            },
            dismissButton = { TextButton(onClick = { showBlocked = null }) { Text("닫기") } },
        )
    }

    Column {
        checks.forEach { c ->
            ListRow(
                title = c.title,
                subtitle = c.why,
                icon = c.icon,
                iconTint = if (c.granted) P.c.sage else if (c.required) P.c.coral else P.c.amber,
                onClick = if (c.granted) null else {
                    {
                        if (c.key in blocked) showBlocked = c.title
                        else when (c.key) {
                            "notify" -> if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}")))
                            "nearby" -> nearby.launch(NearbyPermissions.required)
                            "dnd" -> open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            "battery" -> open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")))
                            // Play's prominent-disclosure rule: explain location use before the system prompt.
                            "location", "bglocation" -> disclose = c.key
                        }
                    }
                },
            ) {
                Text(if (c.granted) "켜짐" else "설정", color = if (c.granted) P.c.sage else P.c.coral,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Shown right before the system location prompt (Google Play prominent disclosure for background location). */
@Composable
private fun LocationDisclosure(background: Boolean, onDismiss: () -> Unit, onAgree: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("위치 정보 사용 안내") },
        text = {
            Text(
                "포너디는 연결한 가족이 ‘위치 보기’를 요청하면, 앱이 닫혀 있거나 사용 중이 아닐 때에도 이 폰의 위치 정보를 " +
                    "수집해 그 가족에게만 한 번 보내요.\n\n" +
                    "• 보낼 때마다 알림이 뜨고, 기본 설정에서는 10초 안에 거절할 수 있어요.\n" +
                    "• 위치는 두 폰만 아는 키로 암호화되어, 중계 서버와 개발자도 볼 수 없어요.\n" +
                    "• 위치를 계속 추적하지 않아요. 보낸 위치는 두 폰의 기록에만 남아 지도로 다시 볼 수 있어요.\n" +
                    "• 설정의 ‘위치 요청’에서 ‘보내지 않기’로 언제든 끌 수 있어요." +
                    if (background) "\n\n다음 화면에서 ‘항상 허용’을 골라 주세요." else "",
            )
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("동의하고 계속") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("나중에") } },
    )
}
