package com.m57.hermescontrol.worker

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HermesMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val tickle = WorkerPushProtocol.parseTickle(message.data) ?: return
        InboxSyncWorker.enqueueExpedited(applicationContext)
        // Consume seq without logging any message content or payload.
        tickle.seq
    }

    override fun onNewToken(token: String) {
        val appContext = applicationContext
        serviceScope.launch {
            PushTokenLifecycle.onNewTokenWithContext(appContext, token)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
