package com.remotecontrol.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.remotecontrol.shared.CaptureStatus
import com.remotecontrol.shared.DEFAULT_PORT
import com.remotecontrol.shared.Message
import com.remotecontrol.shared.ScreenFrame

/**
 * The Controller's single Activity. Screens (plain Views swapped with `setContentView`, Back always returns to the
 * connection screen):
 *  - connection screen: status line with the `LAN · TLS` badge, Open screen / Apps / Files / Settings, Wake & unlock,
 *    the paired devices (Connect / Remote... / Forget), the add-device form (PIN pairing) and a log
 *  - [ViewerScreen]: the live view with touch control; the connection stays up when you leave it
 *  - [AppsPanel], [FilesPanel] (with the system file picker for uploads), [SettingsPanel]
 *
 * It reconnects to the last-used Agent on launch and tells the Agent whether the live view is on screen
 * (`ConnectionManager.setViewerVisible`) so the Agent stops streaming while nobody looks.
 */
class MainActivity : Activity(), ConnectionManager.Listener {

    private enum class Screen { CONNECTION, VIEWER, APPS, FILES, SETTINGS }

    private lateinit var store: DeviceStore
    private lateinit var manager: ConnectionManager
    private lateinit var viewer: ViewerScreen
    private lateinit var connectionRoot: View
    private var screen = Screen.CONNECTION
    private var resumed = false
    private var filesPanel: FilesPanel? = null

    private lateinit var statusView: TextView
    private lateinit var openBtn: Button
    private lateinit var appsBtn: Button
    private lateinit var filesBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var devicesBox: LinearLayout
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var logView: TextView
    private lateinit var scanner: DeviceScanner
    private lateinit var nearbyBox: LinearLayout
    private lateinit var scanStatus: TextView
    private val autoTried = HashSet<String>()
    private val logLines = ArrayDeque<String>()

    private val settingsHost = object : SettingsPanel.Host {
        override fun devices() = store.list().map { SettingsPanel.Device(it.key, it.name) }

        override fun forget(key: String) {
            store.byKey(key)?.let { manager.forget(it) }
        }

        override fun repair(key: String) {
            val d = store.byKey(key) ?: return
            if (manager.targets(d)) manager.disconnect()
            store.remove(d.id) // the old token is useless once the Agent has a new PIN
            hostInput.setText(d.host)
            portInput.setText(d.port.toString())
            pinInput.setText("")
            showConnection()
            statusView.text = "Enter the new 6-digit PIN shown on ${d.name}, then tap Pair"
            pinInput.requestFocus()
        }

        override fun close() = showConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        manager = ConnectionManager(this, store, this)
        scanner = DeviceScanner(this)
        viewer = ViewerScreen(
            this,
            onBack = { showConnection() },
            sendConfig = { manager.send(it) },
            send = { manager.send(it) },
            badge = { manager.badge() },
        )
        buildUi()
        showConnection()   // also reports "viewer hidden" to the Agent once
        refreshDevices()

        store.lastUsed()?.let { manager.connect(it) } // auto-reconnect to the remembered Agent
        startScan()                                    // and look for Agents on the Wi-Fi by itself
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        syncViewer()
    }

    override fun onPause() {
        resumed = false
        syncViewer() // stop decoding frames and tell the Agent to pause while backgrounded
        super.onPause()
    }

    override fun onDestroy() {
        scanner.cancel()
        manager.shutdown()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (screen != Screen.CONNECTION) showConnection() else super.onBackPressed()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE) filesPanel?.onFilePicked(if (resultCode == RESULT_OK) data?.data else null)
    }

    // --- screens ---

    /** Decoding and the Agent's frame stream follow "viewer shown and activity resumed". */
    private fun syncViewer() {
        val on = screen == Screen.VIEWER && resumed
        viewer.setActive(on)
        manager.setViewerVisible(on)
    }

    @Suppress("DEPRECATION")
    private fun showViewer() {
        screen = Screen.VIEWER
        filesPanel = null
        setContentView(viewer.root)
        actionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        syncViewer()
    }

    /** Shows a full-screen panel (or the connection screen) with the normal system bars. */
    @Suppress("DEPRECATION")
    private fun showNormal(which: Screen, content: View) {
        screen = which
        window.decorView.systemUiVisibility = 0
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        actionBar?.show()
        setContentView(content)
        syncViewer()
    }

    private fun showConnection() {
        filesPanel = null
        refreshDevices()
        showNormal(Screen.CONNECTION, connectionRoot)
    }

    private fun showApps() {
        filesPanel = null
        showNormal(
            Screen.APPS,
            AppsPanel(this, send = { manager.send(it) }, onClose = { showConnection() }, onLaunched = { showViewer() }),
        )
    }

    private fun showFiles() {
        val panel = FilesPanel(this, send = { manager.send(it) }, onClose = { showConnection() }, pickFile = { pickFile() })
        filesPanel = panel
        showNormal(Screen.FILES, panel)
    }

    private fun showSettings() {
        filesPanel = null
        showNormal(Screen.SETTINGS, SettingsPanel(this, settingsHost))
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_FILE)
        } catch (e: ActivityNotFoundException) {
            log("No file picker available on this phone")
        }
    }

    // --- ConnectionManager.Listener (main thread) ---

    override fun onState(state: ConnectionManager.State, detail: String) {
        val badge = manager.badge()
        statusView.text = "${state.name}: $detail" + if (badge.isNotEmpty()) "  [$badge]" else ""
        val up = state == ConnectionManager.State.CONNECTED
        appsBtn.isEnabled = up
        filesBtn.isEnabled = up
        log(detail)
        refreshDevices()
        viewer.onConnection(state, detail)
    }

    override fun onMessage(message: Message) {
        if (message is CaptureStatus) {
            log(
                if (message.active) "Agent capture ${message.width}x${message.height} @${message.fps}fps q${message.quality}"
                else "Agent capture off"
            )
            viewer.onCaptureStatus(message)
        }
    }

    /** Network thread: hand the frame straight to the (thread-safe) renderer. */
    override fun onFrame(frame: ScreenFrame) {
        viewer.renderer.submit(frame)
    }

    override fun onPaired(device: PairedDevice) {
        pinInput.setText("")
        log("Pairing saved for ${device.name}")
        refreshDevices()
        startScan()
    }

    override fun onInfo(text: String) = log(text)

    // --- UI ---

    private fun refreshDevices() {
        devicesBox.removeAllViews()
        val devices = store.list()
        if (devices.isEmpty()) {
            devicesBox.addView(TextView(this).apply { text = "No paired devices yet" })
            return
        }
        for (d in devices) {
            val isConnected = manager.connectedDeviceId == d.id
            val remote = manager.routeStore.remoteText(d.key)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            box.addView(TextView(this).apply {
                text = "${d.name}\n${d.host}:${d.port}" +
                    (if (remote.isNotEmpty()) "\nRemote: $remote" else "") +
                    (if (isConnected) "  ● connected" else "")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun b(label: String, action: () -> Unit) = row.addView(
                Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            b("Connect") { manager.connect(d) }
            b("Remote…") { RouteDialog.show(this, manager.routeStore, d.key, d.name) { refreshDevices() } }
            b("Forget") { manager.forget(d); refreshDevices() }
            box.addView(row)
            devicesBox.addView(box)
        }
    }

    // --- automatic discovery ---

    private fun startScan() {
        nearbyBox.removeAllViews()
        scanStatus.text = "Scanning this Wi-Fi for Agents..."
        scanner.scan(DEFAULT_PORT, object : DeviceScanner.Listener {
            override fun onFound(found: DeviceScanner.Found) = addNearby(found)
            override fun onProgress(done: Int, total: Int) { scanStatus.text = "Scanning this Wi-Fi... $done / $total" }
            override fun onFinished(count: Int, message: String) { scanStatus.text = message; log(message) }
        })
    }

    private fun addNearby(f: DeviceScanner.Found) {
        val paired = store.byKey("${f.host}:${f.port}")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        box.addView(TextView(this).apply {
            text = (if (paired != null) "${paired.name} (paired)" else "New device") + "\n${f.host}:${f.port}  " +
                (if (f.tls) "encrypted" else "not encrypted") + "  ${f.connectMs} ms" +
                (f.fingerprint?.let { "\ncertificate ${com.remotecontrol.shared.TlsPolicy.short(it)}" } ?: "")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        box.addView(Button(this).apply {
            text = if (paired != null) "Connect" else "Pair..."
            isAllCaps = false
            setOnClickListener { if (paired != null) manager.connect(paired) else askPin(f.host, f.port) }
        })
        nearbyBox.addView(box)
        // A paired Agent that answers on its stored address and nothing is connected yet: connect to it once.
        if (paired != null && manager.connectedDeviceId == null && autoTried.add(paired.key)) {
            log("Found ${paired.name}, connecting...")
            manager.connect(paired)
        }
    }

    private fun askPin(host: String, port: Int) {
        val input = EditText(this).apply {
            hint = "6-digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with $host")
            .setMessage("Enter the PIN shown on that phone's Agent screen.")
            .setView(input)
            .setPositiveButton("Pair") { _, _ ->
                val pin = input.text.toString().replace(" ", "").trim()
                if (pin.length == 6) manager.pair(host, port, pin) else statusView.text = "Enter the 6-digit PIN shown on the Agent"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pairClicked() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: DEFAULT_PORT
        val pin = pinInput.text.toString().replace(" ", "").trim()
        when {
            host.isEmpty() -> statusView.text = "Enter the Agent's IP address"
            pin.length != 6 -> statusView.text = "Enter the 6-digit PIN shown on the Agent"
            else -> manager.pair(host, port, pin)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusView = TextView(this).apply {
            text = "IDLE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        root.addView(statusView)

        openBtn = Button(this).apply { text = "Open screen"; isAllCaps = false; setOnClickListener { showViewer() } }
        appsBtn = Button(this).apply { text = "Apps"; isAllCaps = false; isEnabled = false; setOnClickListener { showApps() } }
        filesBtn = Button(this).apply { text = "Files"; isAllCaps = false; isEnabled = false; setOnClickListener { showFiles() } }
        settingsBtn = Button(this).apply { text = "Settings"; isAllCaps = false; setOnClickListener { showSettings() } }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in listOf(openBtn, appsBtn, filesBtn, settingsBtn)) {
            buttons.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(buttons)
        root.addView(WakeBar(this) { json -> manager.send(json) })

        root.addView(heading("Paired devices"))
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesBox)
        root.addView(Button(this).apply {
            text = "Disconnect"
            isAllCaps = false
            setOnClickListener { manager.disconnect() }
        })

        root.addView(heading("Nearby devices"))
        scanStatus = TextView(this).apply { text = "Scanning..." }
        root.addView(scanStatus)
        nearbyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(nearbyBox)
        root.addView(Button(this).apply {
            text = "Scan again"
            isAllCaps = false
            setOnClickListener { startScan() }
        })

        root.addView(heading("Add device manually"))
        hostInput = EditText(this).apply {
            hint = "Agent IP (e.g. 192.168.1.20)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        portInput = EditText(this).apply {
            hint = "Port"
            setText(DEFAULT_PORT.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        pinInput = EditText(this).apply {
            hint = "6-digit PIN from the Agent"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(hostInput)
        root.addView(portInput)
        root.addView(pinInput)
        root.addView(Button(this).apply {
            text = "Pair"
            isAllCaps = false
            setOnClickListener { pairClicked() }
        })

        root.addView(heading("Log"))
        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(logView)

        connectionRoot = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun heading(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun log(line: String) {
        logLines.addFirst(line)
        while (logLines.size > 30) logLines.removeLast()
        logView.text = logLines.joinToString("\n")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_PICK_FILE = 4711
    }
}
