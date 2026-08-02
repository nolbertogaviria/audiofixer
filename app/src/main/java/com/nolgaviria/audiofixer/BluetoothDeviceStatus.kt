package com.nolgaviria.audiofixer

data class BluetoothDeviceStatus(
    val name: String = "HUAWEI FreeBuds Pro",
    val isConnected: Boolean = false,
    val a2dpConnected: Boolean = false,
    val hfpConnected: Boolean = false,
    val codecType: String = "Desconocido",
    val sampleRate: String = "Desconocido",
    val bitDepth: String = "Desconocido",
    val channelMode: String = "Desconocido",
    val isHighQuality: Boolean = false
)
