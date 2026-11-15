package io.github.jeiel85.findmywifephone.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.jeiel85.findmywifephone.R
import io.github.jeiel85.findmywifephone.core.LocateState
import io.github.jeiel85.findmywifephone.locate.LocateService
import io.github.jeiel85.findmywifephone.locate.LocateUi
import io.github.jeiel85.findmywifephone.ring.RingService
import io.github.jeiel85.findmywifephone.ring.RingUi

object Notifier {
    const val CH_ALERT = "alert"       // ring / location request in progress (full-screen)
    const val CH_INFO = "info"         // paired, location shared, request refused…
    const val ID_RING = 1001
    const val ID_LOCATE = 1002
    private const val ID_INFO_BASE = 2000
    private const val GROUP_ALERT = "fmwp.alert"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "찾기 요청", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "가족이 폰을 찾거나 위치를 확인할 때 화면에 표시해요. 소리는 앱이 따로 울려요."
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "연결 완료, 위치 공유 기록 같은 소식"
        })
    }

    private fun openApp(context: Context, screen: String? = null): PendingIntent =
        PendingIntent.getActivity(context, screen.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { screen?.let { putExtra(MainActivity.EXTRA_SCREEN, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun alertScreen(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 7,
            Intent(context, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun service(context: Context, intent: Intent, code: Int): PendingIntent =
        PendingIntent.getService(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun alertBuilder(context: Context) = NotificationCompat.Builder(context, CH_ALERT)
        .setSmallIcon(R.drawable.ic_stat)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        // Own group: otherwise Android bundles it with our other notifications ("연결됐어요" etc.),
        // and SystemUI then treats the bundled child as silent and drops the full-screen intent.
        .setGroup(GROUP_ALERT)
        .setContentIntent(alertScreen(context))
        .setFullScreenIntent(alertScreen(context), true)

    fun ringCountdown(context: Context, ui: RingUi): Notification = alertBuilder(context)
        .setContentTitle("${ui.peerName}님이 폰을 찾고 있어요")
        .setContentText("${ui.secondsLeft}초 뒤에 소리가 울려요. 원치 않으면 거절하세요.")
        .addAction(0, "거절", service(context, RingService.intent(context, RingService.ACTION_DECLINE), 11))
        .build()

    fun ringing(context: Context, ui: RingUi): Notification = alertBuilder(context)
        .setContentTitle(if (ui.test) "울림 테스트 🔔" else "여기 있어요! 🔔")
        .setContentText(if (ui.test) "${RingService.TEST_SEC}초 동안 실제와 똑같이 울려요" else "${ui.peerName}님의 요청으로 울리는 중이에요")
        .setColor(0xFFFF6B5B.toInt())
        .setColorized(true)
        .addAction(0, "찾았어요", service(context, RingService.intent(context, RingService.ACTION_FOUND), 12))
        .build()

    fun locateRequest(context: Context, ui: LocateUi): Notification {
        val b = alertBuilder(context).setContentTitle("${ui.peerName}님이 내 위치를 확인하려고 해요")
        when {
            ui.locating -> b.setContentText("현재 위치를 찾는 중이에요")
            ui.ask -> b.setContentText("허용하면 지금 위치를 한 번 보내요")
                .addAction(0, "허용", service(context, LocateService.intent(context, LocateService.ACTION_ALLOW), 21))
                .addAction(0, "거절", service(context, LocateService.intent(context, LocateService.ACTION_DECLINE), 22))
            else -> b.setContentText("${ui.secondsLeft}초 뒤에 위치를 보내요. 원치 않으면 거절하세요.")
                .addAction(0, "거절", service(context, LocateService.intent(context, LocateService.ACTION_DECLINE), 22))
        }
        return b.build()
    }

    fun update(context: Context, id: Int, n: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun info(context: Context, id: Int, title: String, text: String, screen: String? = null) =
        update(context, id, NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, screen))
            .build())

    fun paired(context: Context, name: String) =
        info(context, ID_INFO_BASE + 1, "${name}님과 연결됐어요", "이제 서로의 폰을 찾을 수 있어요.")

    fun locationShared(context: Context, name: String) =
        info(context, ID_INFO_BASE + 2, "${name}님에게 내 위치를 보냈어요", "누가 언제 확인했는지는 기록에서 볼 수 있어요.", MainActivity.SCREEN_HISTORY)

    fun locateRefused(context: Context, name: String, state: LocateState) = info(
        context, ID_INFO_BASE + 3, "${name}님의 위치 확인 요청",
        when (state) {
            LocateState.SNOOZED -> "요청 받지 않기 중이라 위치를 보내지 않았어요."
            LocateState.OFF -> "위치 공유를 꺼 두어서 보내지 않았어요."
            else -> "위치 권한이나 위치 서비스가 꺼져 있어 보내지 못했어요. 앱에서 설정을 확인해 주세요."
        },
        MainActivity.SCREEN_HISTORY)

    fun blockedWhileSnoozed(context: Context, name: String, ring: Boolean) = info(
        context, ID_INFO_BASE + 4, "${name}님이 폰을 찾았어요",
        "요청 받지 않기 중이라 소리를 내지 않았어요.", MainActivity.SCREEN_HISTORY)

    /** The system installer needs one tap; [confirm] is its own screen. */
    fun updateReady(context: Context, version: String, confirm: Intent) = update(context, ID_INFO_BASE + 5,
        NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("새 버전 $version 준비됐어요")
            .setContentText("탭해서 설치하세요. 연결과 기록은 그대로 남아요.")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 5, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build())

    fun updated(context: Context, version: String) =
        info(context, ID_INFO_BASE + 5, "$version 버전으로 업데이트했어요", "연결과 기록, 설정은 그대로예요.", MainActivity.SCREEN_SETTINGS)
}
