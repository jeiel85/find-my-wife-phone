package io.github.jeiel85.findmywifephone.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.update.SelfUpdate
import io.github.jeiel85.findmywifephone.update.StoreUpdateWorker
import io.github.jeiel85.findmywifephone.update.UpdateState
import io.github.jeiel85.findmywifephone.data.Settings
import kotlinx.coroutines.launch

/** Play build: asks Play whether a newer version exists; Google Play installs it (DECISIONS.md D-10, D-11). */
@Composable
internal fun UpdateSection(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by SelfUpdate.state.collectAsStateWithLifecycle()

    ToggleRow("새 버전 알림", "하루 두 번 Play 스토어에 새 버전이 있는지 보고 알려 줘요",
        Icons.Rounded.SystemUpdate, P.c.sage, settings.updateNotice) { on ->
        Hub.store.updateSettings { it.copy(updateNotice = on) }
        StoreUpdateWorker.schedule(context, on)
    }
    val (title, sub) = when (val s = state) {
        is UpdateState.StoreUpdate -> "새 버전 ${s.version}이 있어요" to "지금 ${BuildConfig.VERSION_NAME} · Play 스토어에서 업데이트해요"
        UpdateState.Checking -> "확인하는 중…" to "Play 스토어에 새 버전이 있는지 보고 있어요"
        is UpdateState.Failed -> s.reason to "지금 ${BuildConfig.VERSION_NAME}"
        UpdateState.UpToDate -> "최신 버전이에요" to "${BuildConfig.VERSION_NAME} · 업데이트는 Play 스토어가 해요"
        UpdateState.Idle -> "현재 버전 ${BuildConfig.VERSION_NAME}" to "업데이트는 Play 스토어가 해요"
    }
    val available = state is UpdateState.StoreUpdate
    ListRow(title, sub, Icons.Rounded.InstallMobile,
        when {
            state is UpdateState.Failed -> P.c.danger
            available -> P.c.coral
            else -> P.c.indigo
        }) {
        if (available) SmallButton("업데이트") { SelfUpdate.openStore(context) }
        else SmallButton("확인", enabled = state != UpdateState.Checking) {
            scope.launch { SelfUpdate.check(context, userInitiated = true) }
        }
    }
}
