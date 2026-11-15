package io.github.jeiel85.findmywifephone

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.ring.SoundState
import io.github.jeiel85.findmywifephone.ui.Notifier
import io.github.jeiel85.findmywifephone.update.UpdateWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Hub.init(this)
        // A ring cut short by a crash or reboot must not leave the phone at full alarm volume.
        SoundState.restoreIfPending(this)
        UpdateWorker.schedule(this, Hub.store.settings.value.autoUpdate)
    }
}

/** After a reboot mid-ring: Application.onCreate runs and restores the sound state. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
