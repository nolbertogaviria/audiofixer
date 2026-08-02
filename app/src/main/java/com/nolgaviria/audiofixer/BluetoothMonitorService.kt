package com.nolgaviria.audiofixer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BluetoothMonitorService : Service() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothA2dp: BluetoothA2dp? = null
    private var bluetoothHeadset: BluetoothHeadset? = null

    companion object {
        private const val TAG = "BluetoothMonitor"
        private const val NOTIFICATION_ID = 1
        private var instance: BluetoothMonitorService? = null
        
        private val _deviceStatus = MutableStateFlow(BluetoothDeviceStatus())
        val deviceStatus: StateFlow<BluetoothDeviceStatus> = _deviceStatus
        
        private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
        val logs: StateFlow<List<LogEntry>> = _logs

        fun addLog(message: String, type: LogType = LogType.INFO) {
            val newLog = LogEntry(message = message, type = type)
            _logs.value = (listOf(newLog) + _logs.value).take(50) // Mantener últimos 50
            Log.d(TAG, "[LOG] $message")
            
            // Guardar en archivo persistente
            instance?.saveLogToFile(newLog)
        }

        fun toggleA2dpManual(enable: Boolean) {
            instance?.let { service ->
                service.bluetoothAdapter?.let { adapter ->
                    try {
                        val device = adapter.bondedDevices.find { 
                            it.name?.contains("FreeBuds Pro", ignoreCase = true) == true 
                        }
                        device?.let { 
                            if (enable) service.connectA2dp(it)
                            else service.disconnectA2dp(it)
                        }
                    } catch (e: SecurityException) {
                        addLog("Error de permisos al conmutar A2DP", LogType.ERROR)
                    }
                }
            } ?: addLog("El servicio no está activo", LogType.ERROR)
        }
    }

    private fun saveLogToFile(log: LogEntry) {
        try {
            val logFile = File(getExternalFilesDir(null), "bluetooth_fixer_logs.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
            val logLine = "[$timestamp] [${log.type}] ${log.message}\n"
            logFile.appendText(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "Error al guardar log en archivo", e)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    bluetoothA2dp = proxy as BluetoothA2dp
                    addLog("Perfil Multimedia (A2DP) vinculado", LogType.SUCCESS)
                    updateFullStatus()
                    checkAndFixA2dp()
                }
                BluetoothProfile.HEADSET -> {
                    bluetoothHeadset = proxy as BluetoothHeadset
                    addLog("Perfil Llamadas (HFP) vinculado", LogType.SUCCESS)
                    updateFullStatus()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> bluetoothA2dp = null
                BluetoothProfile.HEADSET -> bluetoothHeadset = null
            }
            updateFullStatus()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            if (action == BluetoothDevice.ACTION_ACL_CONNECTED && isTargetDevice(device)) {
                addLog("Auriculares detectados físicamente", LogType.SUCCESS)
            } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED && isTargetDevice(device)) {
                addLog("Auriculares desconectados físicamente", LogType.WARNING)
            }

            updateFullStatus()
            
            if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                if (state == BluetoothProfile.STATE_DISCONNECTED && isTargetDevice(device)) {
                    addLog("Canal Multimedia caído. Intentando corrección...", LogType.ERROR)
                    checkAndFixA2dp()
                } else if (state == BluetoothProfile.STATE_CONNECTED && isTargetDevice(device)) {
                    addLog("Canal Multimedia recuperado", LogType.SUCCESS)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        addLog("Servicio de Monitoreo Iniciado", LogType.INFO)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            // ACTION_CODEC_CONFIG_CHANGED is hidden in some SDK versions
            addAction("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED")
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        registerReceiver(bluetoothReceiver, filter)

        bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)

        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateFullStatus()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "bluetooth_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Bluetooth Monitor", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Audio Fixer")
            .setContentText("Monitoreando FreeBuds Pro...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun updateFullStatus() {
        val adapter = bluetoothAdapter ?: return
        val bondedDevices = try { adapter.bondedDevices } catch (e: SecurityException) { null } ?: return
        
        val device = bondedDevices.find { it.name?.contains("FreeBuds Pro", ignoreCase = true) == true }
        
        if (device == null) {
            _deviceStatus.value = BluetoothDeviceStatus(isConnected = false)
            return
        }

        val a2dpState = bluetoothA2dp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        val hfpState = bluetoothHeadset?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        
        var codec = "Desconocido"
        var sampleRate = "Desconocido"
        var bitDepth = "Desconocido"
        var hq = false

        if (a2dpState && bluetoothA2dp != null) {
            try {
                val getCodecConfigMethod = bluetoothA2dp!!.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                val codecStatus = getCodecConfigMethod.invoke(bluetoothA2dp, device)
                if (codecStatus != null) {
                    val getCodecConfigMethod2 = codecStatus.javaClass.getMethod("getCodecConfig")
                    val codecConfig = getCodecConfigMethod2.invoke(codecStatus)
                    if (codecConfig != null) {
                        codec = getCodecName(codecConfig.javaClass.getMethod("getCodecType").invoke(codecConfig) as Int)
                        sampleRate = getSampleRateName(codecConfig.javaClass.getMethod("getSampleRate").invoke(codecConfig) as Int)
                        bitDepth = getBitDepthName(codecConfig.javaClass.getMethod("getBitsPerSample").invoke(codecConfig) as Int)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error obteniendo codec info por reflexión", e)
            }
        }

        _deviceStatus.value = BluetoothDeviceStatus(
            name = device.name ?: "HUAWEI FreeBuds Pro",
            isConnected = a2dpState || hfpState,
            a2dpConnected = a2dpState,
            hfpConnected = hfpState,
            codecType = codec,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            isHighQuality = codec != "SBC" && codec != "Desconocido"
        )
    }

    @SuppressLint("MissingPermission")
    private fun isTargetDevice(device: BluetoothDevice?): Boolean {
        return try {
            val name = device?.name ?: ""
            name.contains("FreeBuds Pro", ignoreCase = true)
        } catch (e: SecurityException) {
            false
        }
    }

    private fun getCodecName(type: Int): String = when (type) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        else -> "Otro ($type)"
    }

    private fun getSampleRateName(rate: Int): String = when (rate) {
        1 shl 0 -> "44.1 kHz"
        1 shl 1 -> "48 kHz"
        1 shl 2 -> "88.2 kHz"
        1 shl 3 -> "96 kHz"
        1 shl 4 -> "176.4 kHz"
        1 shl 5 -> "192 kHz"
        else -> "Auto"
    }

    private fun getBitDepthName(depth: Int): String = when (depth) {
        1 shl 0 -> "16 bits"
        1 shl 1 -> "24 bits"
        1 shl 2 -> "32 bits"
        else -> "Auto"
    }

    @SuppressLint("MissingPermission")
    private fun checkAndFixA2dp() {
        bluetoothAdapter?.let { adapter ->
            try {
                val targetDevice = adapter.bondedDevices.find { 
                    it.name?.contains("FreeBuds Pro", ignoreCase = true) == true 
                }
                
                targetDevice?.let { device ->
                    val state = bluetoothA2dp?.getConnectionState(device)
                    val hfpState = bluetoothHeadset?.getConnectionState(device)
                    
                    if (state == BluetoothProfile.STATE_DISCONNECTED && hfpState == BluetoothProfile.STATE_CONNECTED) {
                        addLog("A2DP desconectado pero HFP activo. Forzando...", LogType.WARNING)
                        // Pequeño retraso para dejar que el sistema procese el cambio de estado anterior
                        Handler(Looper.getMainLooper()).postDelayed({
                            connectA2dp(device)
                        }, 1500)
                    }
                }
            } catch (e: SecurityException) {}
        }
    }

    private fun connectA2dp(device: BluetoothDevice) {
        addLog("Forzando prioridad y conexión A2DP...", LogType.WARNING)
        bluetoothA2dp?.let { proxy ->
            try {
                // Primero, nos aseguramos de que la política de conexión sea "Permitida"
                // En Android 10+ se usa setConnectionPolicy, en anteriores setPriority
                val setPolicyMethod = try {
                    proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.java)
                } catch (e: Exception) {
                    proxy.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.java)
                }
                
                // 100 = CONNECTION_POLICY_ALLOWED / PRIORITY_ON
                setPolicyMethod.invoke(proxy, device, 100)
                Log.d(TAG, "Prioridad A2DP establecida a ON")

                // Ahora intentamos la conexión
                val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                val result = connectMethod.invoke(proxy, device) as Boolean
                
                if (result) {
                    addLog("Comando de conexión aceptado por el sistema", LogType.SUCCESS)
                } else {
                    addLog("El sistema rechazó el comando de conexión", LogType.ERROR)
                }
            } catch (e: Exception) {
                addLog("Error crítico al forzar conexión: ${e.message}", LogType.ERROR)
                Log.e(TAG, "Error en connectA2dp", e)
            }
        }
    }

    private fun disconnectA2dp(device: BluetoothDevice) {
        addLog("Desactivando Audio Multimedia manualmente...", LogType.INFO)
        bluetoothA2dp?.let { proxy ->
            try {
                val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                disconnectMethod.invoke(proxy, device)
            } catch (e: Exception) {
                Log.e(TAG, "Error en disconnectA2dp", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        unregisterReceiver(bluetoothReceiver)
        bluetoothA2dp?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        bluetoothHeadset?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
    }
}
