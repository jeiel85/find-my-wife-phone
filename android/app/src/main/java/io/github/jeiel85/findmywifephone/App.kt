package io.github.jeiel85.findmywifephone

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jeiel85.findmywifephone.core.BatteryWatch
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.quick.Shortcuts
import io.github.jeiel85.findmywifephone.ring.SoundState
import io.github.jeiel85.findmywifephone.ui.Notifier
import io.github.jeiel85.findmywifephone.update.SelfUpdate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Hub.init(this)
        // A ring cut short by a crash or reboot must not leave the phone at full alarm volume.
        SoundState.restoreIfPending(this)
        SelfUpdate.onAppStart(this)
        BatteryWatch.schedule(this)
        Hub.scope.launch {
            BatteryWatch.check(this@App) // a phone already low at start is not left waiting 15 minutes
        }
        // Launcher shortcuts follow the pairs (names, unpairing). Peers also change on every status
        // update; Android rate-limits shortcut calls from the background, so only id/name changes count.
        Hub.scope.launch {
            Hub.store.peers.distinctUntilChangedBy { list -> list.map { it.id to it.name } }
                .collect { Shortcuts.sync(this@App, it) }
        }
    }
}

/** After a reboot mid-ring: Application.onCreate runs and restores the sound state. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
