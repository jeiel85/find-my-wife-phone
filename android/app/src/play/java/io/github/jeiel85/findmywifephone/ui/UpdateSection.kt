package io.github.jeiel85.findmywifephone.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import io.github.jeiel85.findmywifephone.BuildConfig

/** Play build: updates come from Google Play (DECISIONS.md D-10). */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun UpdateSection(autoUpdate: Boolean) {
    ListRow("Google Play가 업데이트해요", "Play 스토어 설정을 따라요 · v${BuildConfig.VERSION_NAME}",
        Icons.Rounded.SystemUpdate, P.c.inkSoft)
}
