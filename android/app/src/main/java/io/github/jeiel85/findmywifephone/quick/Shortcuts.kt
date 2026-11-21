package io.github.jeiel85.findmywifephone.quick

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.jeiel85.findmywifephone.R
import io.github.jeiel85.findmywifephone.data.Peer

/**
 * Launcher shortcuts (long-press the icon, or pinned to the home screen): "○○ 폰 울리기" and
 * "○○ 위치 보기" without opening the app first. They start [ShortcutActivity], which is not exported,
 * so no other app can fire a ring through them.
 */
object Shortcuts {
    private const val TAG = "Shortcuts"
    const val ACTION_RING = "io.github.jeiel85.findmywifephone.action.RING"
    const val ACTION_LOCATE = "io.github.jeiel85.findmywifephone.action.LOCATE"
    const val EXTRA_PEER = "peer"

    private fun ringId(peerId: String) = "ring:$peerId"
    private fun locateId(peerId: String) = "locate:$peerId"

    private fun intent(context: Context, action: String, peerId: String) =
        Intent(context, ShortcutActivity::class.java).setAction(action).putExtra(EXTRA_PEER, peerId)

    private fun ring(context: Context, peer: Peer): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, ringId(peer.id))
            .setShortLabel("${peer.name} 폰 울리기".take(25))
            .setLongLabel("${peer.name}님 폰 울리기")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_ring))
            .setIntent(intent(context, ACTION_RING, peer.id))
            .build()

    private fun locate(context: Context, peer: Peer): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, locateId(peer.id))
            .setShortLabel("${peer.name} 위치 보기".take(25))
            .setLongLabel("${peer.name}님 위치 보기")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_locate))
            .setIntent(intent(context, ACTION_LOCATE, peer.id))
            .build()

    /**
     * Keeps the launcher in step with the pairs: one ring and one locate shortcut per peer, renamed
     * when a peer renames, and pinned ones of an unpaired peer greyed out instead of left working.
     */
    fun sync(context: Context, peers: List<Peer>) {
        runCatching {
            val all = peers.flatMap { listOf(ring(context, it), locate(context, it)) }
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(2)
            ShortcutManagerCompat.setDynamicShortcuts(context, all.take(max))
            // Pinned shortcuts outlive the dynamic list: keep their labels current, disable the orphans.
            val current = all.map { it.id }.toSet()
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }
            val orphans = pinned.filter { it !in current }
            if (orphans.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, orphans, "연결이 끊긴 폰이에요")
            val live = all.filter { it.id in pinned }
            if (live.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, live)
        }.onFailure { Log.w(TAG, "shortcut sync failed", it) }
    }

    fun canPin(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to put a "○○ 폰 울리기" icon on the home screen (the launcher confirms). */
    fun pinRing(context: Context, peer: Peer): Boolean =
        runCatching { ShortcutManagerCompat.requestPinShortcut(context, ring(context, peer), null) }.getOrDefault(false)

    /** A shortcut was used: lets the launcher rank it (Android's usage hint). */
    fun reportUsed(context: Context, action: String, peerId: String) {
        val id = if (action == ACTION_RING) ringId(peerId) else locateId(peerId)
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, id) }
    }
}
