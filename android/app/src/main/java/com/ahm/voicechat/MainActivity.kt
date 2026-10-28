package com.ahm.voicechat

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * App v3: one screen, centred.
 * - Big field: "اكتب اسمك" (Arabic), then Connect.
 * - After connecting: "يرجى كتابة اسمك بالشات" + the single in-game
 *   command (/ + name, e.g. /ahmdawd3) + a Copy button, so the player
 *   pastes it into Minecraft chat. No verify code anymore.
 * - Below: range picker — how many blocks others hear you from:
 *   20, 30, 50, 100, 200, 500, 1000, 2000 (sender range, relay v3).
 * - Mic button: tap = mute/unmute (long-press = groups, as before).
 * - Notification actions (Mute / Unmute) work from the shade while
 *   playing, without leaving Minecraft.
 *
 * The player enters ONLY a name. No game login, no password for the
 * player himself. Group passwords exist only for groups, entered when
 * creating or joining a group (long-press on the mic button).
 */
class MainActivity : AppCompatActivity(), RelayClient.Listener {

    private lateinit var prefs: SharedPreferences
    private var service: VoiceService? = null
    private var bound = false

    private lateinit var nameField: EditText
    private lateinit var statusText: TextView
    private lateinit var speakingText: TextView
    private lateinit var commandSection: LinearLayout
    private lateinit var commandText: TextView
    private lateinit var micButton: Button
    private lateinit var overlayButton: Button
    private lateinit var autoSenseSwitch: Switch
    private lateinit var rangeSpinner: Spinner

    private val ranges = listOf(20, 30, 50, 100, 200, 500, 1000, 2000)
    private var currentRange = 50
    private var playerName = ""

    // Relay address is embedded in the app: the player enters ONLY a name.
    private val embeddedRelayUrl = "wss://minecraft-voice-relay.onrender.com"

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VoiceService.LocalBinder).service()
            bound = true
            refreshMicButton()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("voicechat", Context.MODE_PRIVATE)

        nameField = findViewById(R.id.nameField)
        statusText = findViewById(R.id.statusText)
        speakingText = findViewById(R.id.speakingText)
        commandSection = findViewById(R.id.commandSection)
        commandText = findViewById(R.id.commandText)
        micButton = findViewById(R.id.micButton)
        overlayButton = findViewById(R.id.overlayButton)
        autoSenseSwitch = findViewById(R.id.autoSenseSwitch)
        rangeSpinner = findViewById(R.id.rangeSpinner)

        nameField.setText(prefs.getString("name", ""))
        currentRange = prefs.getInt("range", 50)
        if (currentRange !in ranges) currentRange = 50

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            ranges.map { "$it بلوك" })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        rangeSpinner.adapter = adapter
        rangeSpinner.setSelection(ranges.indexOf(currentRange))
        rangeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                currentRange = ranges[pos]
                prefs.edit().putInt("range", currentRange).apply()
                service?.setRange(currentRange)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        autoSenseSwitch.isChecked = prefs.getBoolean("autosense", true)
        VoiceService.autoSenseEnabled = autoSenseSwitch.isChecked
        VoiceService.micPermissionGranted = hasMicPermission()

        findViewById<Button>(R.id.connectButton).setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.copyButton).visibility = View.GONE
        findViewById<Button>(R.id.permissionsButton).setOnClickListener { explainAndRequestPermissions() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { openBatterySettings() }
        overlayButton.setOnClickListener { onOverlayClicked() }
        autoSenseSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceService.autoSenseEnabled = checked
            prefs.edit().putBoolean("autosense", checked).apply()
        }

        micButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            svc.setMuted(!svc.muted)
            refreshMicButton()
        }
        micButton.setOnLongClickListener {
            showGroupsDialog()
            true
        }

        val serviceIntent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        VoiceService.listener = this
        VoiceService.micPermissionGranted = hasMicPermission()
        refreshMicButton()
        refreshOverlayButton()
    }

    override fun onPause() {
        super.onPause()
        if (VoiceService.listener === this) VoiceService.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(connection)
    }

    // ---- actions ----

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun onConnectClicked() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "اكتب اسمك أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        playerName = name
        prefs.edit().putString("name", name).putString("url", embeddedRelayUrl).putInt("range", currentRange).apply()
        VoiceService.savedName = name
        VoiceService.savedUrl = embeddedRelayUrl
        VoiceService.savedRange = currentRange
        service?.connect(name, embeddedRelayUrl, currentRange)
        setStatus("جارٍ الاتصال باسم $name...")
    }

    private fun copyCommand() {
        val cmd = commandText.text.toString()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("voice command", cmd))
        Toast.makeText(this, "تم نسخ الأمر: $cmd", Toast.LENGTH_SHORT).show()
    }

    private fun explainAndRequestPermissions() {
        if (!GameWatcher.hasUsageAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle("Usage access (for auto-sense)")
                .setMessage(
                    "Auto-sense needs to know when Minecraft is in the foreground. " +
                        "Android only allows that if YOU grant Usage access in Settings. " +
                        "This app cannot grant it itself and never sees other apps' content, " +
                        "only which app is foreground. Press OK to open that Settings screen, " +
                        "find VoiceChat, and turn it on."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        val needed = mutableListOf<String>()
        if (!hasMicPermission()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        } else {
            Toast.makeText(this, "كل الأذونات ممنوحة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    // ---- floating mute button over Minecraft ----
    // Bedrock HUD buttons cannot run anything or reach this app, so the
    // real button is this app's system overlay: it appears over the game
    // (bottom-centre, draggable) while connected, and a tap toggles the
    // mic through the service, exactly like the mic button here.

    private fun onOverlayClicked() {
        val svc = service
        if (svc == null) {
            Toast.makeText(this, "الخدمة لسا ما اشتغلت — انتظر ثانية وحاول", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "افتح إذن «العرض فوق التطبيقات الأخرى» لـ VoiceChat، وبعدها ارجع وكبس الزر من جديد",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val newState = !svc.isOverlayEnabled()
        svc.setOverlayEnabled(newState)
        Toast.makeText(
            this,
            if (newState) "تم تفعيل الزر العائم — بيظهر فوق اللعبة لما تتصل"
            else "تم إيقاف الزر العائم",
            Toast.LENGTH_SHORT
        ).show()
        refreshOverlayButton()
    }

    private fun refreshOverlayButton() {
        runOnUiThread {
            val svc = service
            overlayButton.text = if (svc?.isOverlayEnabled() == true)
                "زر الكتم العائم: شغال (اضغط للإيقاف)"
            else
                "زر الكتم العائم فوق اللعبة"
        }
    }

    // ---- groups: long-press on the mic button ----

    private fun showGroupsDialog() {
        val svc = service ?: return
        val group = svc.currentGroup
        if (group != null) {
            val name = nameField.text.toString().trim()
            AlertDialog.Builder(this)
                .setTitle("Group: $group")
                .setMessage("Player: $name\n\nYou are in this group. Group members hear you from anywhere, any distance.")
                .setPositiveButton("Exit group") { _, _ -> svc.groupLeave() }
                .setNegativeButton("Stay", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Groups")
            .setMessage("Create a new group, or join an existing one. Group members hear each other from anywhere, even far apart.")
            .setPositiveButton("Create group") { _, _ -> showCreateGroupDialog() }
            .setNeutralButton("Join a group") { _, _ ->
                svc.groupList()
                Toast.makeText(this, "Loading groups...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateGroupDialog() {
        val svc = service ?: return
        val view = layoutInflater.inflate(R.layout.dialog_group, null)
        val nameInput = view.findViewById<EditText>(R.id.groupNameField)
        val passInput = view.findViewById<EditText>(R.id.groupPasswordField)
        AlertDialog.Builder(this)
            .setTitle("Create group")
            .setView(view)
            .setPositiveButton("Create") { _, _ ->
                val gname = nameInput.text.toString().trim()
                val gpass = passInput.text.toString()
                if (gname.isNotEmpty()) svc.groupCreate(gname, gpass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showJoinGroupDialog(groups: List<String>) {
        val svc = service ?: return
        if (groups.isEmpty()) {
            Toast.makeText(this, "No groups exist yet. Create one.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Join a group")
            .setItems(groups.toTypedArray()) { _, which ->
                val gname = groups[which]
                val passInput = EditText(this)
                passInput.hint = "Group password"
                AlertDialog.Builder(this)
                    .setTitle("Password for $gname")
                    .setView(passInput)
                    .setPositiveButton("Join") { _, _ ->
                        svc.groupJoin(gname, passInput.text.toString())
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- UI state ----

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun showCommandSection() {
        runOnUiThread {
            commandText.text = playerName
            commandSection.visibility = View.VISIBLE
        }
    }

    private fun refreshMicButton() {
        runOnUiThread {
            val svc = service
            micButton.text = if (svc?.muted == true) "MUTED\nاضغط لإلغاء الكتم" else "MIC ON\nاضغط للكتم"
            micButton.visibility = View.VISIBLE
        }
    }

    // ---- RelayClient.Listener (forwarded by VoiceService) ----

    override fun onConnected() = setStatus("متصل... بانتظار التأكيد")
    override fun onDisconnected() = setStatus("غير متصل")
    override fun onHelloOk(name: String, proximity: Int) {
        playerName = name
        setStatus("متصل باسم $name (المسافة $proximity بلوك)")
        showCommandSection()
        refreshMicButton()
    }

    override fun onCode(code: String) {}
    override fun onVerified(player: String) = setStatus("تم الربط داخل اللعبة باسم $player")

    override fun onLinked(player: String) {
        setStatus("تم ربط الصوت داخل اللعبة باسم $player — تكلّم الآن")
    }

    // Copy button is kept for the name only; there is no chat command
    // anymore on 1.26.52 (chatSend was removed by Mojang).

    override fun onMuteOk(muted: Boolean) {
        setStatus(if (muted) "مكتوم" else "غير مكتوم")
        refreshMicButton()
    }

    override fun onGroups(groups: List<String>) {
        runOnUiThread { showJoinGroupDialog(groups) }
    }

    override fun onGroupOk(ok: Boolean, group: String?, reason: String?) {
        if (ok) {
            setStatus(if (group != null) "في المجموعة: $group" else "خرجت من المجموعة")
        } else {
            Toast.makeText(this, "Group failed: ${reason ?: "unknown"}", Toast.LENGTH_SHORT).show()
        }
        refreshMicButton()
    }

    override fun onSpeaking(from: String) {
        runOnUiThread { speakingText.text = "يتكلم الآن: $from" }
    }

    override fun onAudio(bytes: ByteArray) {
        // Playback is handled in VoiceService; nothing to do here.
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        VoiceService.micPermissionGranted = hasMicPermission()
    }
}
