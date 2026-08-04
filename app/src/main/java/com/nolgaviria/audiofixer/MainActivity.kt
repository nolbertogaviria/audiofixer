package com.nolgaviria.audiofixer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.ui.draw.scale
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nolgaviria.audiofixer.ui.theme.AudioFixerTheme

import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioFixerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PermissionScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val deviceStatus by BluetoothMonitorService.deviceStatus.collectAsState()
    val logs by BluetoothMonitorService.logs.collectAsState()
    val waMessages by WhatsAppMonitorService.lastMessages.collectAsState()
    val waCount by WhatsAppMonitorService.unreadCount.collectAsState()
    
    val permissionsToRequest = mutableListOf<String>().apply {
// ... (omitted for brevity in thinking but I will include everything in the tool call)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            startMonitorService(context)
        }
    }

    LaunchedEffect(Unit) {
        val needsRequest = permissionsToRequest.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!needsRequest) {
            startMonitorService(context)
        } else {
            launcher.launch(permissionsToRequest.toTypedArray())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "AudioFixer Pro",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        // WhatsApp Watcher Card
        WhatsAppCard(waMessages, waCount) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Card de Información del Dispositivo
        DeviceStatusCard(deviceStatus)

        Spacer(modifier = Modifier.height(12.dp))

        // Card de Detalles Técnicos
        if (deviceStatus.isConnected) {
            TechnicalDetailsCard(deviceStatus)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Historial de Eventos
        EventLogCard(logs)

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { startMonitorService(context) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Reiniciar Monitoreo", fontSize = 16.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun WhatsAppCard(messages: List<String>, count: Int, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (count > 0) Color(0xFF1A1C1E).copy(alpha = 0.8f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        text = "WhatsApp Watcher",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (count > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                    )
                    if (count > 0) {
                        Text(
                            text = "$count mensajes pendientes",
                            color = Color(0xFF4CAF50),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(24.dp)) {
                    Text("⚙️", fontSize = 16.sp)
                }
            }
            
            if (messages.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = Color(0xFF4CAF50).copy(alpha = 0.1f)
                )
                messages.forEach { msg ->
                    Text(
                        text = "• $msg",
                        fontSize = 12.sp,
                        color = Color(0xFF81C784),
                        modifier = Modifier.padding(vertical = 1.dp),
                        maxLines = 1
                    )
                }
                TextButton(
                    onClick = { WhatsAppMonitorService.clearMessages() },
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Limpiar", fontSize = 11.sp, color = Color(0xFF4CAF50))
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text("No hay mensajes pendientes", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun DeviceStatusCard(status: BluetoothDeviceStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Column {
                Text(
                    text = status.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (status.isConnected) "CONECTADO" else "DESCONECTADO",
                    color = if (status.isConnected) Color(0xFF4CAF50) else Color.Red,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            StatusRow(
                label = "Audio Multimedia (A2DP)",
                isActive = status.a2dpConnected,
                onToggle = { BluetoothMonitorService.toggleA2dpManual(it) }
            )
            StatusRow(
                label = "Llamadas y Voz (HFP)",
                isActive = status.hfpConnected,
                onToggle = { BluetoothMonitorService.toggleHfpManual(it) }
            )
        }
    }
}

@Composable
fun TechnicalDetailsCard(status: BluetoothDeviceStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Detalles de Audio",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            DetailItem("Codec", status.codecType)
            DetailItem("Frecuencia", status.sampleRate)
            DetailItem("Profundidad", status.bitDepth)
            
            if (status.isHighQuality || status.isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = if (status.isHighQuality) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (status.isHighQuality) "Audio de Alta Calidad Activo" else "Audio Estándar (SBC/AAC)",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = if (status.isHighQuality) Color(0xFF2E7D32) else Color(0xFFE65100),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun EventLogCard(logs: List<LogEntry>) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Historial de Eventos",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { exportLogs(context) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Exportar", fontSize = 10.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay eventos registrados", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs.size) { index ->
                        val log = logs[index]
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = log.formattedTime,
                                fontSize = 10.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = log.message,
                                fontSize = 12.sp,
                                color = when (log.type) {
                                    LogType.SUCCESS -> Color(0xFF2E7D32)
                                    LogType.ERROR -> Color.Red
                                    LogType.WARNING -> Color(0xFFE65100)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, isActive: Boolean, onToggle: ((Boolean) -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isActive) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    shape = RoundedCornerShape(4.dp)
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp)
        Spacer(modifier = Modifier.weight(1f))
        
        if (onToggle != null) {
            Switch(
                checked = isActive,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.55f)
            )
        } else {
            Text(
                text = if (isActive) "Activo" else "Inactivo",
                fontWeight = FontWeight.Bold,
                color = if (isActive) Color(0xFF4CAF50) else Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, fontWeight = FontWeight.Medium)
    }
}

private fun exportLogs(context: Context) {
    try {
        val logFile = File(context.getExternalFilesDir(null), "bluetooth_fixer_logs.txt")
        if (!logFile.exists()) {
            Toast.makeText(context, "No hay logs para exportar", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir Historial de AudioFixer"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error al exportar: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun startMonitorService(context: Context) {
    val intent = Intent(context, BluetoothMonitorService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}
