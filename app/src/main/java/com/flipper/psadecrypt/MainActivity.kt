package com.flipper.psadecrypt

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.flipper.psadecrypt.filemanager.FileManagerFragment
import com.flipper.psadecrypt.remotecontrol.RemoteControlFragment
import com.flipper.psadecrypt.subghz.SubGhzSettingsFragment
import com.flipper.psadecrypt.rpc.FlipperRpcClient
import com.flipper.psadecrypt.storage.FlipperStorageApi
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), FlipperBleClient.Listener {
    companion object {
        private const val TAG = "Companion"
    }

    lateinit var bleClient: FlipperBleClient
        private set
    private val handler = Handler(Looper.getMainLooper())

    var rpcClient: FlipperRpcClient? = null
    var storageApi: FlipperStorageApi? = null
    private var rpcScope: CoroutineScope? = null

    // UI
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var bleStatusText: TextView
    private lateinit var bleButton: Button
    private lateinit var logToggle: View
    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var logToggleArrow: TextView
    private lateinit var bleDot: View

    private val foundDevices = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var logExpanded = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logLineCount = 0

    private var psaFragment: PsaDecryptFragment? = null
    private var keeloqFragment: KeeloqDecryptFragment? = null
    private var fileManagerFragment: FileManagerFragment? = null
    private var remoteControlFragment: RemoteControlFragment? = null
    private var subGhzSettingsFragment: SubGhzSettingsFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bottom nav — 5 items max
        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_psa_decrypt     -> { showFragment("psa"); true }
                R.id.nav_keeloq_decrypt  -> { showFragment("keeloq"); true }
                R.id.nav_file_manager    -> { showFragment("files"); true }
                R.id.nav_subghz_settings -> { showFragment("subghz"); true }
                R.id.nav_remote_control  -> { showFragment("remote"); true }
                else -> false
            }
        }

        // About button in header
        findViewById<View>(R.id.about_button)?.setOnClickListener {
            showFragment("about")
            bottomNav.selectedItemId = -1
        }

        // BLE
        bleStatusText = findViewById(R.id.ble_status_text)
        bleButton = findViewById(R.id.ble_button)
        bleDot = findViewById(R.id.ble_dot)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        bleButton.setOnClickListener { requestPermissionsAndScan() }

        // Log
        logToggle = findViewById(R.id.log_toggle)
        logToggleArrow = findViewById(R.id.log_toggle_arrow)
        logScroll = findViewById(R.id.log_scroll)
        logText = findViewById(R.id.log_text)
        logToggle.setOnClickListener { toggleLog() }

        bleClient = FlipperBleClient(this)
        bleClient.listener = this

        // Animate header on start
        animateHeaderIn()

        // Default fragment
        if (savedInstanceState == null) {
            showFragment("psa")
            bottomNav.selectedItemId = R.id.nav_psa_decrypt
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val cpuCount = Runtime.getRuntime().availableProcessors()
        appendLog("App started, $cpuCount CPU cores, SDK ${Build.VERSION.SDK_INT}")
    }

    // --- Fragment navigation ---

    private fun clearFragmentRefs() {
        psaFragment = null
        keeloqFragment = null
        fileManagerFragment = null
        remoteControlFragment = null
        subGhzSettingsFragment = null
    }

    private fun showPsaDecrypt() {
        clearFragmentRefs()
        val frag = PsaDecryptFragment()
        psaFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
        supportActionBar?.title = "PSA Decrypt"
    }

    private fun showKeeloqDecrypt() {
        clearFragmentRefs()
        val frag = KeeloqDecryptFragment()
        keeloqFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
        supportActionBar?.title = "KeeLoq Decrypt"
    }

    private fun showFileManager() {
        clearFragmentRefs()
        val frag = FileManagerFragment()
        fileManagerFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
        supportActionBar?.title = "File Manager"
    }

    private fun showRemoteControl() {
        clearFragmentRefs()
        val frag = RemoteControlFragment()
        remoteControlFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
        supportActionBar?.title = "Remote Control"
    }

    private fun animateHeaderIn() {
        val header = findViewById<View>(R.id.header_bar) ?: return
        header.alpha = 0f
        header.animate().alpha(1f).setDuration(300).start()
    }

    private fun showFragment(tag: String) {
        val ft = supportFragmentManager.beginTransaction()
        ft.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        when (tag) {
            "psa" -> {
                if (psaFragment == null) psaFragment = PsaDecryptFragment()
                ft.replace(R.id.fragment_container, psaFragment!!, tag)
            }
            "keeloq" -> {
                if (keeloqFragment == null) keeloqFragment = KeeloqDecryptFragment()
                ft.replace(R.id.fragment_container, keeloqFragment!!, tag)
            }
            "files" -> {
                if (fileManagerFragment == null) fileManagerFragment = FileManagerFragment()
                ft.replace(R.id.fragment_container, fileManagerFragment!!, tag)
            }
            "subghz" -> {
                if (subGhzSettingsFragment == null) subGhzSettingsFragment = SubGhzSettingsFragment()
                ft.replace(R.id.fragment_container, subGhzSettingsFragment!!, tag)
            }
            "remote" -> {
                if (remoteControlFragment == null) remoteControlFragment = RemoteControlFragment()
                ft.replace(R.id.fragment_container, remoteControlFragment!!, tag)
            }
            "about" -> {
                ft.replace(R.id.fragment_container, AboutFragment(), tag)
            }
        }
        ft.commit()
    }

    private fun toggleLog() {
        logExpanded = !logExpanded
        if (logExpanded) {
            logScroll.visibility = View.VISIBLE
            logScroll.alpha = 0f
            logScroll.animate().alpha(1f).setDuration(200).start()
            logToggleArrow.text = "▼"
        } else {
            logScroll.animate().alpha(0f).setDuration(150).withEndAction {
                logScroll.visibility = View.GONE
            }.start()
            logToggleArrow.text = "▶"
        }
    }

    fun appendLog(msg: String) {
        val ts = timeFmt.format(Date())
        runOnUiThread {
            logLineCount++
            val current = logText.text.toString()
            val newText = if (current.isEmpty()) "[$ts] $msg" else "$current\n[$ts] $msg"
            logText.text = newText
            findViewById<TextView>(R.id.log_count)?.text = "$logLineCount lines"
            handler.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // --- BLE Scan ---

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startBleScan()
        else appendLog("BLE permissions denied")
    ) { results ->
        appendLog("Permission results: $results")
        val bleGranted = results.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it }
        if (bleGranted) startBleScan()
        else {
            bleStatusText.text = "BLE permissions denied"
            appendLog("BLE permissions denied")
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        appendLog("POST_NOTIFICATIONS permission: $granted")
    }

    private fun requestPermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isEmpty()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                val locationEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                if (!locationEnabled) {
                    appendLog("ERREUR: Activez la localisation pour scanner BLE")
                    bleStatusText.text = "Activez la localisation !"
                    return
                }
            }
            startBleScan()
        } else {
            permLauncher.launch(needed.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startBleScan() {
        val btMgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btMgr.adapter ?: run { appendLog("No BT adapter"); return }
        foundDevices.clear(); deviceNames.clear(); deviceAdapter.notifyDataSetChanged()
        bleStatusText.text = "Scanning…"
        bleButton.text = "STOP"
        bleButton.setOnClickListener { adapter.bluetoothLeScanner?.stopScan(scanCallback) }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        appendLog("BLE scan started")

        handler.postDelayed({
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
            if (foundDevices.isNotEmpty()) {
                bleStatusText.text = "${foundDevices.size} device(s) found"
                bleButton.text = "CONNECT"
                bleButton.setOnClickListener { connectToSelected() }
            } else {
                bleStatusText.text = "No device found"
                bleButton.text = "SCAN"
                bleButton.setOnClickListener { requestPermissionsAndScan() }
            }
        }, 4000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            if (name.startsWith("Flipper") && !foundDevices.any { it.address == device.address }) {
                foundDevices.add(device)
                deviceNames.add("$name (${device.address})")
                runOnUiThread { deviceAdapter.notifyDataSetChanged() }
                appendLog("Found: $name (${device.address}) rssi=${result.rssi}")
            }
        }
        override fun onScanFailed(errorCode: Int) {
            appendLog("Scan failed: $errorCode")
            runOnUiThread { bleStatusText.text = "Scan failed ($errorCode)" }
        }
    }

    private fun connectToSelected() {
        if (foundDevices.isEmpty()) { bleStatusText.text = "No device"; return }
        val device = foundDevices[0]
        appendLog("Connecting to ${device.name}…")
        bleStatusText.text = "Connecting…"
        bleClient.connect(device)
    }

    // --- FlipperBleClient.Listener ---

    override fun onLog(msg: String) { appendLog("[BLE] $msg") }

    override fun onConnected() {
        appendLog("Connected to Flipper")
        runOnUiThread {
            bleStatusText.text = "Connected"
            bleButton.text = "DISC."
            bleButton.setOnClickListener { bleClient.disconnect() }
            bleDot.setBackgroundResource(R.drawable.dot_connected)
            val scaleX = ObjectAnimator.ofFloat(bleDot, "scaleX", 1f, 1.4f, 1f)
            val scaleY = ObjectAnimator.ofFloat(bleDot, "scaleY", 1f, 1.4f, 1f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 400
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
        BleKeepAliveService.start(this)
        startRpcClient()
        fileManagerFragment?.onConnectionChanged(true)
        remoteControlFragment?.onConnectionChanged(true)
        subGhzSettingsFragment?.onConnectionChanged(true)
    }

    override fun onDisconnected() {
        appendLog("Disconnected")
        runOnUiThread {
            bleStatusText.text = "No BLE"
            bleButton.text = "SCAN"
            bleButton.setOnClickListener { requestPermissionsAndScan() }
            bleDot.setBackgroundResource(R.drawable.dot_disconnected)
        }
        psaFragment?.bfExecutor?.cancel()
        keeloqFragment?.bfExecutor?.cancel()
        BleKeepAliveService.stop(this)
        stopRpcClient()
        fileManagerFragment?.onConnectionChanged(false)
        remoteControlFragment?.onConnectionChanged(false)
        subGhzSettingsFragment?.onConnectionChanged(false)
    }

    override fun onDataReceived(data: ByteArray) {
        appendLog("BLE data: ${data.size}B type=0x${String.format("%02X", data[0])}")
        if (KeeloqBleProtocol.isKeeloqMessage(data)) {
            if (keeloqFragment == null) {
                runOnUiThread {
                    showFragment("keeloq")
                    bottomNav.selectedItemId = R.id.nav_keeloq_decrypt
                }
            }
            handler.postDelayed({ keeloqFragment?.handleBleData(data) }, 100)
        } else {
            psaFragment?.handleBleData(data)
        }
    }

    private fun startRpcClient() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        rpcScope = scope
        val rpc = FlipperRpcClient(bleClient, scope)
        rpcClient = rpc
        rpc.start()
        storageApi = FlipperStorageApi(rpc)
        appendLog("RPC client started")
    }

    private fun stopRpcClient() {
        rpcScope?.cancel()
        rpcScope = null
        rpcClient = null
        storageApi = null
    }

    fun sendBleData(data: ByteArray) {
        bleClient.send(data)
    }

    override fun onDestroy() {
        super.onDestroy()
        psaFragment?.bfExecutor?.cancel()
        keeloqFragment?.bfExecutor?.cancel()
        stopRpcClient()
        bleClient.disconnect()
        BleKeepAliveService.stop(this)
    }
}
