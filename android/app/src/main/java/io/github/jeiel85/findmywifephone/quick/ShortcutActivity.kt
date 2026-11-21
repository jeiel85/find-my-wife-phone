package io.github.jeiel85.findmywifephone.quick

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.RequestKind
import io.github.jeiel85.findmywifephone.ui.MainActivity

/**
 * Target of the launcher shortcuts and the Quick Settings tile. Not exported: only our own
 * shortcuts (started by the system on our behalf) and our own tile reach it. It sends the request,
 * then shows the home screen with that peer's progress card; it has no screen of its own.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        val peerId = intent.getStringExtra(Shortcuts.EXTRA_PEER)
        val action = intent.action
        if (peerId != null && action != null) act(action, peerId)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HOME)
            .apply { peerId?.let { putExtra(MainActivity.EXTRA_PEER, it) } })
        finish()
    }

    private fun act(action: String, peerId: String) {
        if (!Hub.store.settings.value.onboarded || Hub.store.peer(peerId) == null) return
        Shortcuts.reportUsed(this, action, peerId)
        when (action) {
            // A second tap while the first is still under way would only replace it.
            Shortcuts.ACTION_RING -> if (!Hub.requestActive(peerId, RequestKind.RING)) Hub.ring(peerId)
            Shortcuts.ACTION_LOCATE -> if (!Hub.requestActive(peerId, RequestKind.LOCATE)) Hub.locate(peerId)
        }
    }
}
