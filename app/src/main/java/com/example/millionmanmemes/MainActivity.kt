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
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WiFiDirect"
        private const val PORT = 8988
        // Fixed test credentials. SSID must start with "DIRECT-xy"; passphrase 8-63 chars.
        private const val TEST_SSID = "DIRECT-mm-Train01"
        private const val TEST_PASS = "test-passphrase-123"
    }

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var receiverRegistered = false
    private val transferRunning = AtomicBoolean(false)

    @Volatile private var pendingUri: Uri? = null
    @Volatile private var hosting = false
    @Volatile private var connecting = false
    @Volatile private var resultShown = false

    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var status by mutableStateOf("Starting...")
    private var pickedName by mutableStateOf<String?>(null)

    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    private fun updateStatus(msg: String) = ui { status = msg }

    private fun setResult(msg: String) = ui { status = msg; resultShown = true }

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
            resultShown = false
            pendingUri = uri
            pickedName = displayName(uri)
            status = "File ready. Now tap Join test group."
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: "file_${System.currentTimeMillis()}"

    // ---------- Setup and discovery ----------

    private fun initManager() {
        if (!::manager.isInitialized) {
            manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            channel = manager.initialize(this, mainLooper, null)
        }
    }

    private val peerListListener = WifiP2pManager.PeerListListener { list ->
        peers = list.deviceList.toList()
        Log.d(TAG, "Peer count: ${peers.size}")
        peers.forEach { Log.d(TAG, "Found: ${it.deviceName} ${it.deviceAddress}") }
        if (!transferRunning.get() && !hosting && !resultShown) {
            status = if (peers.isEmpty()) "Scanning... no devices yet"
            else "Found ${peers.size} device(s)"
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
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                    manager.requestPeers(channel, peerListListener)
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG, "Connection changed")
                    handleConnectionChanged()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        initManager()
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
                if (!transferRunning.get() && !hosting && !resultShown) status = "Scanning..."
                manager.requestPeers(channel, peerListListener)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Discovery failed: $reason")
                if (!resultShown) status = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported on this device"
                    WifiP2pManager.BUSY -> "Busy, try again in a moment"
                    else -> "Discovery failed (is Wi-Fi on?)"
                }
            }
        })
    }

    // ---------- Normal connect (tap a device; shows the invitation prompt) ----------

    @SuppressLint("MissingPermission")
    private fun connectTo(device: WifiP2pDevice, attempt: Int = 1) {
        if (hosting || transferRunning.get()) return
        if (pendingUri == null) {
            status = "Pick a file first"
            return
        }
        if (connecting && attempt == 1) return
        connecting = true
        resultShown = false
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
                        connecting = false
                        status = "Connect failed: $reason"
                    }
                }
            })
        }, 800)
    }

    // ---------- TEST: group with fixed credentials (no invitation) ----------

    private fun testConfig(): WifiP2pConfig? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            status = "Needs Android 10 or newer"
            return null
        }
        return try {
            WifiP2pConfig.Builder()
                .setNetworkName(TEST_SSID)
                .setPassphrase(TEST_PASS)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Config error", e)
            status = "Config error: ${e.message}"
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun hostTestGroup() {
        if (!::manager.isInitialized) {
            ensurePermissionThenScan()
            status = "Grant permission, then tap again"
            return
        }
        if (transferRunning.get()) return
        val cfg = testConfig() ?: return
        resultShown = false
        hosting = true                       // set BEFORE anything can race with it
        status = "Starting group..."
        manager.stopPeerDiscovery(channel, null)
        manager.removeGroup(channel, null)

        Handler(Looper.getMainLooper()).postDelayed({
            manager.createGroup(channel, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Test group created")
                    startHostListener()
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "createGroup failed: $reason")
                    hosting = false
                    status = "Host failed: $reason"
                }
            })
        }, 800)
    }

    @SuppressLint("MissingPermission")
    private fun joinTestGroup() {
        if (!::manager.isInitialized) {
            ensurePermissionThenScan()
            status = "Grant permission, then tap again"
            return
        }
        if (hosting || transferRunning.get()) return
        val cfg = testConfig() ?: return
        resultShown = false
        status = if (pendingUri == null) "Joining $TEST_SSID (no file picked, so receiving)..."
        else "Joining $TEST_SSID..."
        manager.stopPeerDiscovery(channel, null)
        manager.cancelConnect(channel, null)

        Handler(Looper.getMainLooper()).postDelayed({
            manager.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "Join request sent") }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Join failed: $reason")
                    status = "Join failed: $reason"
                }
            })
        }, 800)
    }

    // ---------- Connection formed ----------

    @SuppressLint("MissingPermission")
    private fun handleConnectionChanged() {
        if (hosting) return                  // the host listener is already running
        manager.requestConnectionInfo(channel) { info ->
            if (info == null || !info.groupFormed) return@requestConnectionInfo
            Log.d(TAG, "Group formed. Owner=${info.isGroupOwner} addr=${info.groupOwnerAddress}")
            if (info.isGroupOwner) startHostListener() else startClientTransfer(info)
        }
    }

    // Group owner: open the port and wait for the other phone
    private fun startHostListener() {
        if (!transferRunning.compareAndSet(false, true)) return
        thread {
            var server: ServerSocket? = null
            var socket: Socket? = null
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                    soTimeout = 300_000
                }
                Log.d(TAG, "Host listening on $PORT")
                updateStatus("Hosting. Waiting for the other phone...")
                socket = server.accept()
                socket.soTimeout = 30_000
                Log.d(TAG, "Host accepted ${socket.inetAddress}")
                exchange(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Host error", e)
                setResult("Host failed: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: IOException) {}
                try { server?.close() } catch (_: IOException) {}
                finishTransfer()
            }
        }
    }

    // Group member: connect to the owner and exchange
    private fun startClientTransfer(info: WifiP2pInfo) {
        if (!transferRunning.compareAndSet(false, true)) return
        thread {
            var socket: Socket? = null
            try {
                val host = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                Log.d(TAG, "Connecting to $host:$PORT")
                updateStatus("Joined group. Connecting to $host...")
                socket = connectWithRetry(host)
                socket.soTimeout = 30_000
                exchange(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Client error", e)
                setResult("Transfer failed: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: IOException) {}
                finishTransfer()
            }
        }
    }

    private fun exchange(socket: Socket) {
        val uri = pendingUri
        if (uri != null) {
            updateStatus("Sending...")
            sendFile(socket, uri)
            ui { pickedName = null }
            setResult("File sent")
        } else {
            updateStatus("Receiving...")
            val saved = receiveFile(socket)
            setResult("File received: $saved")
        }
    }

    private fun finishTransfer() {
        pendingUri = null
        hosting = false
        connecting = false
        transferRunning.set(false)
        resetAfterTransfer()
    }

    private fun connectWithRetry(host: String): Socket {
        var last: Exception? = null
        repeat(20) { n ->
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, PORT), 3000)
                return s
            } catch (e: IOException) {
                last = e
                Log.d(TAG, "Socket connect try ${n + 1} failed: ${e.message}")
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
        socket.getInputStream().read()
    }

    private fun receiveFile(socket: Socket): String {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        var name = File(input.readUTF()).name
        if (name.isBlank() || name == "." || name == "..") {
            name = "received_${System.currentTimeMillis()}"
        }
        val dir = getExternalFilesDir(null) ?: filesDir
        var dest = File(dir, name)
        if (dest.exists()) dest = File(dir, "${System.currentTimeMillis()}_$name")
        FileOutputStream(dest).use { input.copyTo(it) }
        socket.getOutputStream().apply { write(1); flush() }
        Log.d(TAG, "Received ${dest.absolutePath}")
        return dest.name
    }

    @SuppressLint("MissingPermission")
    private fun resetAfterTransfer() {
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
                        Text("Build v5")
                        Text(status)

                        Button(
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text(pickedName?.let { "File: $it" } ?: "Pick a file to send") }

                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = { hostTestGroup() }) { Text("Host test group") }
                            Button(
                                onClick = { joinTestGroup() },
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text("Join test group") }
                        }

                        Button(
                            onClick = { resultShown = false; ensurePermissionThenScan() },
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) { Text("Scan again") }

                        Text("Devices (normal connect, shows a prompt):")

                        LazyColumn {
                            items(peers, key = { it.deviceAddress }) { device ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { connectTo(device) }
                                        .padding(vertical = 10.dp)
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
        hosting = false
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