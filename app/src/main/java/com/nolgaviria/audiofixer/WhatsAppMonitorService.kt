package com.nolgaviria.audiofixer

import android.annotation.SuppressLint
import android.content.Context
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WhatsAppMonitorService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var alertRunnable: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        syncWithExistingNotifications()
    }

    companion object {
        private val _lastMessages = MutableStateFlow<List<String>>(emptyList())
        val lastMessages: StateFlow<List<String>> = _lastMessages
        
        private val _unreadCount = MutableStateFlow(0)
        val unreadCount: StateFlow<Int> = _unreadCount
        
        fun clearMessages() {
            _unreadCount.value = 0
            _lastMessages.value = emptyList()
        }
    }

    private fun syncWithExistingNotifications() {
        try {
            val active = activeNotifications ?: return
            val waNotifications = active.filter { it.packageName.contains("whatsapp") }
            updateStateFromNotifications(waNotifications)
        } catch (e: Exception) {
            Log.e("WhatsAppMonitor", "Error al sincronizar inicial", e)
        }
    }

    private fun updateStateFromNotifications(notifications: List<StatusBarNotification>) {
        val messages = mutableListOf<String>()
        notifications.forEach { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            
            if (title.isNotBlank() && title != "WhatsApp" && !title.contains("Checking") && !title.contains("WhatsApp Web")) {
                messages.add("$title: $text")
            }
        }
        
        val newList = messages.distinct().take(10)
        _lastMessages.value = newList
        _unreadCount.value = newList.size
        
        if (newList.isNotEmpty()) {
            startAlertLoop()
        } else {
            stopAlertLoop()
        }
    }

    @SuppressLint("NotificationPermission")
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName.contains("whatsapp")) {
            syncWithExistingNotifications()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName.contains("whatsapp")) {
            // Sincronizar de nuevo para ver cuántas quedan realmente
            handler.postDelayed({
                syncWithExistingNotifications()
            }, 500)
        }
    }

    private fun startAlertLoop() {
        if (alertRunnable != null) return

        alertRunnable = object : Runnable {
            override fun run() {
                if (_unreadCount.value > 0) {
                    triggerAlert()
                    handler.postDelayed(this, 120000)
                } else {
                    stopAlertLoop()
                }
            }
        }
        handler.post(alertRunnable!!)
    }

    private fun stopAlertLoop() {
        alertRunnable?.let { handler.removeCallbacks(it) }
        alertRunnable = null
    }

    private fun triggerAlert() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: Exception) {
            Log.e("WhatsAppMonitor", "Error sonido", e)
        }

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AudioFixer:WhatsAppAlert"
            )
            wakeLock.acquire(3000)
        } catch (e: Exception) {
            Log.e("WhatsAppMonitor", "Error pantalla", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlertLoop()
    }
}
