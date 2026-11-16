package io.github.jeiel85.findmywifephone.update

import android.content.Context
import io.github.jeiel85.findmywifephone.core.Hub

/** GitHub build: schedules the GitHub Releases self-updater (DECISIONS.md D-7, D-10). */
object SelfUpdate {
    fun onAppStart(context: Context) = UpdateWorker.schedule(context, Hub.store.settings.value.autoUpdate)

    fun onAppVisible(visible: Boolean) { Updater.appVisible = visible }
}
