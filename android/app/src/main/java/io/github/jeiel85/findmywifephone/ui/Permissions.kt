package io.github.jeiel85.findmywifephone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
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
import androidx.compose.material.icons.rounded.DoNotDisturbOff
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val checks = remember(tick, refresh, policy) { Checks.all(context, policy) }

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
                        when (c.key) {
                            "notify" -> if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}")))
                            "dnd" -> open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            "battery" -> open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")))
                            "location" -> location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            "bglocation" -> if (checks.first { it.key == "location" }.granted) {
                                bgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            } else {
                                location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
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
