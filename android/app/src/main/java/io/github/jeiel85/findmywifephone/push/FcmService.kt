package io.github.jeiel85.findmywifephone.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.jeiel85.findmywifephone.core.Hub

/** FCM only wakes us; everything meaningful is inside the end-to-end encrypted envelope. */
class FcmService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }

    override fun onNewToken(token: String) = Hub.onNewFcmToken()

    override fun onMessageReceived(message: RemoteMessage) = Hub.onPush(message.data)
}
