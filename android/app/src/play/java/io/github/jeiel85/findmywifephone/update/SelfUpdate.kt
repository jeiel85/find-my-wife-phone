package io.github.jeiel85.findmywifephone.update

import android.content.Context

/**
 * Play build: Google Play delivers updates, so this build carries no self-updater, no install
 * permission and no download code (Play policy; DECISIONS.md D-10).
 */
object SelfUpdate {
    fun onAppStart(context: Context) = Unit

    fun onAppVisible(visible: Boolean) = Unit
}
