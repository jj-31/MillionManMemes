package com.example.millionmanmemes

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import android.os.Handler
import android.os.Looper

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WiFiDirect"
        private const val PORT = 8988
    }

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var receiverRegistered = false
    private val transferRunning = AtomicBoolean(false);

    // The file waiting to be sent. If null when a connection forms, this phone acts as the receiver.
    @Volatile private var pendingUri: Uri? = null

    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var status by mutableStateOf("Starting...")
    private var pickedName by mutableStateOf<String?>(null)

    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    // ---------- Permissions ----------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startDiscovery()
        else status = "Permission denied. Allow it in Settings > Apps > this app."
    }

    private fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.NEARBY_WIFI_DEVICES
        else
            Manifest.permission.ACCESS_FINE_LOCATION

    private fun hasPermission() =
        ActivityCompat.checkSelfPermission(this, requiredPermission()) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionThenScan() {
        if (hasPermission()) startDiscovery()
        else permissionLauncher.launch(arrayOf(requiredPermission()))
    }

    // ---------- File picking ----------

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            pickedName = displayName(uri)
            status = "File ready. Tap a device to send it."
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: "file_${System.currentTimeMillis()}"

    // ---------- Discovery ----------

    private val peerListListener = WifiP2pManager.PeerListListener { list ->
        peers = list.deviceList.toList()
        Log.d(TAG, "Peer count: ${peers.size}")
        peers.forEach { Log.d(TAG, "Found: ${it.deviceName} ${it.deviceAddress}") }

        if (!transferRunning.get()) {
            status = if (peers.isEmpty()) "Scanning... no devices yet" else "Found ${peers.size} device(s)"
        }
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
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager.requestPeers(channel, peerListListener)
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> handleConnectionChanged()
            }
        }
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
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery started")
                if (!transferRunning.get()) status = "Scanning..."
                manager.requestPeers(channel, peerListListener)
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Discovery failed: $reason")
                status = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported on this device"
                    WifiP2pManager.BUSY -> "Busy, try again in a moment"
                    else -> "Discovery failed (is Wi-Fi on?)"
                }
            }
        })
    }

    // ---------- Connecting (sender side) ----------

    @SuppressLint("MissingPermission")
    private fun connectTo(device: WifiP2pDevice, attempt: Int = 1) {
        if (pendingUri == null) {
            status = "Pick a file first"
            return
        }
        status = "Connecting to ${device.deviceName}..."

        manager.cancelConnect(channel, null)
        manager.removeGroup(channel, null)

        Handler(Looper.getMainLooper()).postDelayed({
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
            }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "Connect request sent") }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Connect failed: $reason (attempt $attempt)")
                    if (reason == WifiP2pManager.BUSY && attempt < 3) {
                        connectTo(device, attempt + 1)
                    } else {
                        status = "Connect failed: $reason"
                    }
                }
            })
        }, 800)
    }

    // ---------- Connection formed (runs on BOTH phones) ----------
    private fun handleConnectionChanged() {
        manager.requestConnectionInfo(channel) { info ->
            if (info == null || !info.groupFormed) return@requestConnectionInfo
            if (!transferRunning.compareAndSet(false, true)) return@requestConnectionInfo
            thread { runTransfer(info) }
        }
    }

    private fun runTransfer(info: WifiP2pInfo) {
        var socket: Socket? = null
        var server: ServerSocket? = null
        try {
            if (info.isGroupOwner) {
                // Group owner waits for the other phone to connect
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                    soTimeout = 30_000
                }
                socket = server.accept()
            } else {
                socket = connectWithRetry(info.groupOwnerAddress.hostAddress!!)
            }
            socket.soTimeout = 30_000

            // Whoever has a file queued sends. The other side just receives.
            val uri = pendingUri
            if (uri != null) {
                ui { status = "Sending..." }
                sendFile(socket, uri)
                ui { status = "File sent"; pickedName = null }
            } else {
                receiveFile(socket)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transfer error", e)
            ui { status = "Transfer failed: ${e.message}" }
        } finally {
            try { socket?.close() } catch (_: IOException) {}
            try { server?.close() } catch (_: IOException) {}
            pendingUri = null
            transferRunning.set(false)
            resetAfterTransfer()
        }
    }

    private fun connectWithRetry(host: String): Socket {
        var last: Exception? = null
        repeat(10) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, PORT), 3000)
                return s
            } catch (e: IOException) {
                last = e
                Thread.sleep(700)
            }
        }
        throw last ?: IOException("Could not connect")
    }

    private fun sendFile(socket: Socket, uri: Uri) {
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        out.writeUTF(displayName(uri))
        contentResolver.openInputStream(uri)!!.use { it.copyTo(out) }
        out.flush()
        socket.shutdownOutput()
        // Wait for the receiver's confirmation before the connection is torn down
        socket.getInputStream().read()
    }

    private fun receiveFile(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        var name = File(input.readUTF()).name   // strips any folder parts
        if (name.isBlank() || name == "." || name == "..") {
            name = "received_${System.currentTimeMillis()}"
        }
        val dir = getExternalFilesDir(null) ?: filesDir
        var dest = File(dir, name)
        if (dest.exists()) dest = File(dir, "${System.currentTimeMillis()}_$name")
        FileOutputStream(dest).use { input.copyTo(it) }
        socket.getOutputStream().apply { write(1); flush() }   // confirmation
        Log.d(TAG, "Received ${dest.absolutePath}")
    }

    @SuppressLint("MissingPermission")
    private fun resetAfterTransfer() {
        // Drop the group and start scanning again so both phones are ready for the next file
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { manager.discoverPeers(channel, null) }
            override fun onFailure(reason: Int) { manager.discoverPeers(channel, null) }
        })
    }

    // ---------- UI ----------
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
                        Text("Build v3")
                        Text(status)

                        Button(
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) { Text(pickedName?.let { "File: $it" } ?: "Pick a file to send") }

                        Button(
                            onClick = { ensurePermissionThenScan() },
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) { Text("Scan again") }

                        Text("Tap a device to send the picked file:")

                        LazyColumn {
                            items(peers, key = { it.deviceAddress }) { device ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { connectTo(device) }
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
        if (::manager.isInitialized) {
            manager.stopPeerDiscovery(channel, null)
            manager.removeGroup(channel, null)
        }
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