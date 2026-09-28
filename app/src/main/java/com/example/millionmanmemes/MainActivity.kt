package com.example.millionmanmemes

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.millionmanmemes.ui.theme.MillionManMemesTheme

class MainActivity : ComponentActivity() {

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var receiverRegistered = false

    // Compose watches these and redraws when they change
    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var status by mutableStateOf("Starting...")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startDiscovery()
        } else {
            status = "Permission denied. Allow it in Settings > Apps > this app."
        }
    }

    private val peerListListener = WifiP2pManager.PeerListListener { list ->
        peers = list.deviceList.toList()
        Log.d("WiFiDirect", "Peer count: ${peers.size}")
        peers.forEach { Log.d("WiFiDirect", "Found: ${it.deviceName} ${it.deviceAddress}") }
        status = if (peers.isEmpty()) "Scanning... no devices yet" else "Found ${peers.size} device(s)"
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        status = "Wi-Fi Direct is off. Turn Wi-Fi on."
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel, peerListListener)
                }
            }
        }
    }

    private fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.NEARBY_WIFI_DEVICES
        else
            Manifest.permission.ACCESS_FINE_LOCATION

    private fun hasPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, requiredPermission()) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionThenScan() {
        if (hasPermission()) startDiscovery()
        else permissionLauncher.launch(arrayOf(requiredPermission()))
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!::manager.isInitialized) {
            manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            channel = manager.initialize(this, mainLooper, null)
        }

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WiFiDirect", "Discovery started")
                status = "Scanning..."
                // Grab anything already known right away
                manager.requestPeers(channel, peerListListener)
            }

            override fun onFailure(reason: Int) {
                Log.e("WiFiDirect", "Discovery failed: $reason")
                status = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported on this device"
                    WifiP2pManager.BUSY -> "Busy, try again in a moment"
                    else -> "Discovery failed (is Wi-Fi on?)"
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MillionManMemesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize()
                    ) {
                        Text(status)
                        Button(
                            onClick = { ensurePermissionThenScan() },
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) { Text("Scan again") }

                        LazyColumn {
                            items(peers, key = { it.deviceAddress }) { device ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(device.deviceName.ifBlank { "(unnamed)" })
                                    Text("${device.deviceAddress}  -  ${statusText(device.status)}")
                                }
                            }
                        }
                    }
                }
            }
        }

        ensurePermissionThenScan()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        if (::manager.isInitialized) manager.stopPeerDiscovery(channel, null)
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
    }

    private fun statusText(s: Int) = when (s) {
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }
}