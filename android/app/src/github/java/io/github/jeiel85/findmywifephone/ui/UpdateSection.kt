package io.github.jeiel85.findmywifephone.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.update.UpdateState
import io.github.jeiel85.findmywifephone.update.UpdateWorker
import io.github.jeiel85.findmywifephone.update.Updater
import kotlinx.coroutines.launch

/** GitHub build: the app updates itself from GitHub Releases (DECISIONS.md D-7). */
@Composable
internal fun UpdateSection(autoUpdate: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val canInstall = remember(tick) { context.packageManager.canRequestPackageInstalls() }
    val checkedAt = remember(tick, state) { Updater.lastCheckedAt(context) }

    if (!Updater.supported) {
        ListRow("이 빌드는 직접 업데이트해요", "디버그 빌드는 자동 업데이트를 하지 않아요 · v${BuildConfig.VERSION_NAME}",
            Icons.Rounded.SystemUpdate, P.c.inkSoft)
        return
    }
    ToggleRow("자동 업데이트", "하루 두 번 새 버전을 확인하고 설치해요. 울리는 중에는 기다려요.",
        Icons.Rounded.SystemUpdate, P.c.sage, autoUpdate) { on ->
        Hub.store.updateSettings { it.copy(autoUpdate = on) }
        UpdateWorker.schedule(context, on)
    }
    val (title, sub) = when (val s = state) {
        UpdateState.Idle -> "현재 버전 ${BuildConfig.VERSION_NAME}" to "마지막 확인: ${relativeTime(checkedAt)}"
        UpdateState.Checking -> "확인하는 중…" to "GitHub에서 최신 릴리스를 보고 있어요"
        UpdateState.UpToDate -> "최신 버전이에요" to "${BuildConfig.VERSION_NAME} · 마지막 확인 ${relativeTime(checkedAt)}"
        is UpdateState.Available -> "새 버전 ${s.release.version}이 있어요" to "지금 ${BuildConfig.VERSION_NAME}"
        is UpdateState.Downloading -> "내려받는 중 ${s.percent}%" to "새 버전 ${s.release.version}"
        is UpdateState.Installing -> "설치하는 중…" to "잠시 뒤 앱이 다시 시작돼요"
        is UpdateState.NeedsConfirm -> "설치를 허락해 주세요" to "Android가 한 번 확인을 요청해요"
        is UpdateState.Failed -> s.reason to "현재 ${BuildConfig.VERSION_NAME} · 다시 시도해 주세요"
    }
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    ListRow(title, sub, Icons.Rounded.InstallMobile, if (state is UpdateState.Failed) P.c.danger else P.c.indigo) {
        when (val s = state) {
            is UpdateState.Available -> SmallButton("설치") { scope.launch { Updater.run(context, userInitiated = true) } }
            is UpdateState.NeedsConfirm -> SmallButton("설치") {
                runCatching { context.startActivity(s.confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            else -> SmallButton("확인", enabled = !busy) { scope.launch { Updater.run(context, userInitiated = true) } }
        }
    }
    val notes = when (val s = state) {
        is UpdateState.Available -> s.release.notes
        is UpdateState.Downloading -> s.release.notes
        is UpdateState.NeedsConfirm -> s.release.notes
        else -> ""
    }
    if (notes.isNotBlank()) {
        Text(notes.take(600), style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft,
            modifier = Modifier.padding(start = 58.dp, end = 8.dp, bottom = 10.dp))
    }
    if (!canInstall) {
        ListRow("업데이트 설치 허용", "처음 한 번, 이 앱이 새 버전을 설치할 수 있게 해 주세요",
            Icons.Rounded.Security, P.c.amber, onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")))
            }) {
            Text("설정", color = P.c.coral, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

