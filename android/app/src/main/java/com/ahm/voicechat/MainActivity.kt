package com.ahm.voicechat

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * One app only: voice + microphone together.
 *
 * The player enters ONLY a name. No game login, no password for the
 * player himself. Group passwords exist only for groups, entered when
 * creating or joining a group (long-press on the mic button).
 *
 * Mic button, bottom-centre:
 *   tap        = mute / unmute (icon text + status line show the state)
 *   long-press = groups (see showGroupsDialog)
 *
 * Permissions, explained in-app before any request:
 * - Microphone: to capture your voice. Without it, you can listen only
 *   after connecting; capture stays off.
 * - Usage access: to auto-sense Minecraft in the foreground. The app
 *   cannot grant this itself; it opens the Settings screen for you.
 * - Notifications / foreground service: Android requires a visible
 *   notification while the mic service runs in the background.
 * - Battery optimisation: if the phone kills the service while playing,
 *   exempt the app in Settings > Battery. The button below opens the
 *   battery settings screen; it does not change anything by itself.
 */
class MainActivity : AppCompatActivity(), RelayClient.Listener {

    private lateinit var prefs: SharedPreferences
    private var service: VoiceService? = null
    private var bound = false

    private lateinit var nameField: EditText
    private lateinit var urlField: EditText
    private lateinit var statusText: TextView
    private lateinit var speakingText: TextView
    private lateinit var codeText: TextView
    private lateinit var micButton: Button
    private lateinit var autoSenseSwitch: Switch

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
        urlField = findViewById(R.id.urlField)
        statusText = findViewById(R.id.statusText)
        speakingText = findViewById(R.id.speakingText)
        codeText = findViewById(R.id.codeText)
        micButton = findViewById(R.id.micButton)
        autoSenseSwitch = findViewById(R.id.autoSenseSwitch)

        nameField.setText(prefs.getString("name", ""))
        val savedUrl = prefs.getString("url", "wss://minecraft-voice-relay.onrender.com")
        urlField.setText(if (savedUrl.isNullOrEmpty() || savedUrl == "ws://") "wss://minecraft-voice-relay.onrender.com" else savedUrl)
        autoSenseSwitch.isChecked = prefs.getBoolean("autosense", true)
        VoiceService.autoSenseEnabled = autoSenseSwitch.isChecked
        VoiceService.micPermissionGranted = hasMicPermission()

        findViewById<Button>(R.id.connectButton).setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.codeButton).setOnClickListener { service?.requestCode() }
        findViewById<Button>(R.id.permissionsButton).setOnClickListener { explainAndRequestPermissions() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { openBatterySettings() }
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
        val url = urlField.text.toString().trim()
        if (name.isEmpty() || url.isEmpty() || url == "ws://") {
            Toast.makeText(this, "Enter a name and a relay URL first", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("name", name).putString("url", url).apply()
        VoiceService.savedName = name
        VoiceService.savedUrl = url
        service?.connect(name, url)
        setStatus("Connecting to $url as $name...")
    }

    private fun explainAndRequestPermissions() {
        // Usage access first: it is the one the app cannot grant itself.
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
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        // Note only: exempting the app is the user's choice in Settings.
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    // ---- groups: long-press on the mic button ----

    private fun showGroupsDialog() {
        val svc = service ?: return
        val group = svc.currentGroup
        if (group != null) {
            // In a group: show Exit next to the player's name.
            val name = nameField.text.toString().trim()
            AlertDialog.Builder(this)
                .setTitle("Group: $group")
                .setMessage("Player: $name\n\nYou are in this group. Group members hear you from anywhere, any distance.")
                .setPositiveButton("Exit group") { _, _ -> svc.groupLeave() }
                .setNegativeButton("Stay", null)
                .show()
            return
        }
        // Not in a group: create, or list existing groups to join.
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

    private fun refreshMicButton() {
        runOnUiThread {
            val svc = service
            micButton.text = if (svc?.muted == true) "MUTED\nTap to unmute" else "MIC ON\nTap to mute"
            micButton.visibility = View.VISIBLE
        }
    }

    // ---- RelayClient.Listener (forwarded by VoiceService) ----

    override fun onConnected() = setStatus("Connected. Waiting for hello...")
    override fun onDisconnected() = setStatus("Disconnected")
    override fun onHelloOk(name: String, proximity: Int) {
        setStatus("Connected as $name (proximity ${proximity}m blocks)")
        refreshMicButton()
    }

    override fun onCode(code: String) {
        runOnUiThread {
            codeText.text = "Your verify code: $code\nIn Minecraft, type: /voice $code"
        }
    }

    override fun onVerified(player: String) {
        setStatus("Verified in-game as $player. Position now comes from the pack.")
    }

    override fun onMuteOk(muted: Boolean) {
        setStatus(if (muted) "Muted" else "Unmuted")
        refreshMicButton()
    }

    override fun onGroups(groups: List<String>) {
        runOnUiThread { showJoinGroupDialog(groups) }
    }

    override fun onGroupOk(ok: Boolean, group: String?, reason: String?) {
        if (ok) {
            setStatus(if (group != null) "In group: $group" else "Left group")
        } else {
            Toast.makeText(this, "Group failed: ${reason ?: "unknown"}", Toast.LENGTH_SHORT).show()
        }
        refreshMicButton()
    }

    override fun onSpeaking(from: String) {
        runOnUiThread { speakingText.text = "Speaking: $from" }
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
