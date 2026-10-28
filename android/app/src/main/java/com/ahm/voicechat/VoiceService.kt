package com.ahm.voicechat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service: owns the relay connection, mic capture and playback.
 *
 * Audio, pilot v1: raw PCM 16 kHz mono, 16-bit. No Opus, no echo canceller
 * beyond what the platform provides. Frames are sent as binary WebSocket
 * messages; the relay forwards them opaquely.
 *
 * AUTO START / STOP, exactly as the player asked (app v6):
 * - The service loads the saved name, URL and range from preferences on
 *   start, so it works even when it was started by the boot receiver
 *   without the app ever being opened.
 * - Poll every 5 seconds while the service runs (auto-sense on only).
 * - Game foreground + saved name + mic permission -> connect (if needed)
 *   and start mic capture. This is also how a late Connect works: the
 *   player can already be inside a server, press Connect with his name,
 *   and the relay links him to the pack that is already in the world.
 * - Game absent for 3 consecutive polls (~15 seconds) -> FULL STOP:
 *   mic capture stops and the relay disconnects. The service itself
 *   keeps watching, so re-entering the game auto-connects again within
 *   seconds. A brief alt-tab (under ~15s) does not stop anything.
 * - Pack socket closed (player left the server while the game is still
 *   open) -> the relay sends pack_gone: audio stops, the session stays,
 *   so rejoining the server re-links instantly and audio resumes.
 * - Auto-sense OFF (manual mode): none of the above applies; the user
 *   connects and disconnects by hand and the connection stays.
 * - Service stopped (app swiped away / Stop pressed) -> disconnect, stop
 *   capture and playback. Reconnecting is a fresh session; the relay
 *   drops group membership on close, so the player must re-join a group.
 *
 * The floating overlay mute button from v5 is GONE (app v6): voice is
 * now controlled from inside the game (/c, /cc, /s), so no overlay
 * permission is needed anymore.
 */
class VoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "voicechat"
        const val NOTIFICATION_ID = 1

        // MainActivity registers here; a single listener is enough for a pilot.
        var listener: RelayClient.Listener? = null

        var savedName: String = ""
        var savedUrl: String = ""
        var savedRange: Int = 50
        var autoSenseEnabled: Boolean = true
        var micPermissionGranted: Boolean = false
    }

    private val binder = LocalBinder()
    private lateinit var relay: RelayClient
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing: Boolean = false
    @Volatile private var micPausedByGame: Boolean = false
    // The linked pack closed (player left the server, game still open):
    // audio is paused but the session stays, so a rejoin re-links.
    private var packGone = false
    // Debounce for the mic-flicker bug (v5): the old watcher paused the
    // mic on a single 5s poll where the game looked absent (shade
    // pull-down, app switch, usage-stats lag), so the mic indicator
    // flickered and audio cut in and out. Now the mic only pauses after
    // the game has been absent for 3 consecutive polls (~15 seconds).
    private var gameAbsentPolls = 0
    var muted: Boolean = false
        private set
    var currentGroup: String? = null
        private set

    inner class LocalBinder : Binder() {
        fun service(): VoiceService = this@VoiceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // Load the saved session: without this, a service started by the
        // boot receiver (or after a process death) would have an empty
        // name and auto-sense could never connect.
        val prefs = getSharedPreferences("voicechat", Context.MODE_PRIVATE)
        savedName = prefs.getString("name", "") ?: ""
        savedUrl = prefs.getString("url", "") ?: ""
        savedRange = prefs.getInt("range", 50)
        autoSenseEnabled = prefs.getBoolean("autosense", true)
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        relay = RelayClient(ForwardingListener())
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VoiceChat idle"))
        startWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopAll()
                stopSelf()
            }
            "MUTE_TOGGLE" -> {
                setMuted(!muted)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    // ---- public control, called from MainActivity ----

    fun connect(name: String, url: String, range: Int = 50) {
        savedName = name
        savedUrl = url
        savedRange = range
        // Always (re)connect with the typed name. The old guard
        // (connect only when disconnected) meant pressing Connect while
        // an earlier session was alive did nothing: the relay kept the
        // old name, so a player already inside a server never linked.
        // RelayClient.connect() disconnects the old socket first.
        relay.connect(url, name, range)
    }

    fun setRange(range: Int) {
        savedRange = range
        if (relay.isConnected) relay.setRange(range)
    }

    fun disconnect() {
        stopCapture()
        relay.disconnect()
    }

    fun setMuted(m: Boolean) {
        muted = m
        relay.setMuted(m)
        if (m) stopCapture() else if (micPermissionGranted && !micPausedByGame) startCapture()
        updateNotification()
    }

    fun requestCode() = relay.requestCode()
    fun groupCreate(name: String, password: String) = relay.groupCreate(name, password)
    fun groupList() = relay.groupList()
    fun groupJoin(name: String, password: String) = relay.groupJoin(name, password)
    fun groupLeave() = relay.groupLeave()

    // ---- game watcher: auto start / auto stop ----

    private val handler = Handler(Looper.getMainLooper())
    private val watcherRunnable = object : Runnable {
        override fun run() {
            if (autoSenseEnabled && savedName.isNotBlank() && savedUrl.isNotBlank() && micPermissionGranted) {
                if (GameWatcher.isGameForeground(this@VoiceService)) {
                    gameAbsentPolls = 0
                    if (!relay.isConnected) relay.connect(savedUrl, savedName, savedRange)
                    if (micPausedByGame) {
                        micPausedByGame = false
                        if (!muted) startCapture()
                    }
                } else {
                    // Debounced stop: only after 3 consecutive absent
                    // polls (~15s) do we treat the game as really gone.
                    // Full stop (disconnect), not just a mic pause: the
                    // service keeps watching, so re-entering the game
                    // auto-connects again within seconds.
                    gameAbsentPolls++
                    if (gameAbsentPolls >= 3) {
                        if (capturing) {
                            micPausedByGame = true
                            stopCapture()
                        }
                        if (relay.isConnected) {
                            relay.disconnect()
                            updateNotification()
                        }
                    }
                }
            }
            handler.postDelayed(this, 5_000)
        }
    }

    private fun startWatcher() {
        handler.post(watcherRunnable)
    }

    // ---- audio ----

    private val sampleRate = 16000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        if (capturing || muted || !micPermissionGranted) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        if (minBuf <= 0) return
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelIn, encoding, minBuf * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) return
        recorder = rec
        capturing = true
        rec.startRecording()
        captureThread = Thread {
            val buf = ByteArray(minBuf)
            while (capturing) {
                val read = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (read > 0 && relay.isConnected) relay.sendAudio(buf, read)
            }
        }.also { it.start() }
        ensurePlayer()
    }

    private fun stopCapture() {
        capturing = false
        try { captureThread?.join(500) } catch (_: Exception) { }
        captureThread = null
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
    }

    private fun ensurePlayer() {
        if (player != null) return
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        if (minBuf <= 0) return
        player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelOut)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try { player?.play() } catch (_: Exception) { }
    }

    private fun stopAll() {
        handler.removeCallbacks(watcherRunnable)
        stopCapture()
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        relay.disconnect()
    }

    // ---- notification ----

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "ميكروفون ماين كرافت", NotificationManager.IMPORTANCE_LOW
        )
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        // Notification actions: usable from the notification shade WHILE
        // playing, without leaving Minecraft. Tap = mute / unmute.
        val muteIntent = Intent(this, VoiceService::class.java).apply { action = "MUTE_TOGGLE" }
        val mutePending = android.app.PendingIntent.getService(
            this, 1, muteIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, VoiceService::class.java).apply { action = "STOP" }
        val stopPending = android.app.PendingIntent.getService(
            this, 2, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ميكروفون ماين كرافت")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(0, if (muted) "Unmute" else "Mute", mutePending)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val text = when {
            packGone -> "Left server — voice paused"
            muted -> "Muted"
            currentGroup != null -> "In group: $currentGroup"
            relay.isConnected -> "Connected as $savedName"
            else -> "VoiceChat idle"
        }
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---- relay callbacks: forward to UI, track group/mute ----

    private inner class ForwardingListener : RelayClient.Listener {
        override fun onConnected() {
            ensurePlayer()
            updateNotification()
            listener?.onConnected()
        }

        override fun onDisconnected() {
            stopCapture()
            updateNotification()
            listener?.onDisconnected()
        }

        override fun onHelloOk(name: String, proximity: Int) {
            packGone = false
            if (!muted && micPermissionGranted) startCapture()
            updateNotification()
            listener?.onHelloOk(name, proximity)
        }

        override fun onCode(code: String) = listener?.onCode(code) ?: Unit
        override fun onVerified(player: String) = listener?.onVerified(player) ?: Unit
        override fun onLinked(player: String) {
            // Rejoin: the pack is back, resume audio at once.
            packGone = false
            ensurePlayer()
            try { this@VoiceService.player?.play() } catch (_: Exception) { }
            if (!muted && micPermissionGranted) startCapture()
            updateNotification()
            listener?.onLinked(player) ?: Unit
        }

        override fun onPackGone() {
            // Player left the server, game still open: stop audio, keep
            // the session, so a rejoin re-links and audio resumes.
            packGone = true
            stopCapture()
            try { player?.pause() } catch (_: Exception) { }
            updateNotification()
            listener?.onPackGone()
        }

        override fun onMuteOk(muted: Boolean) {
            this@VoiceService.muted = muted
            updateNotification()
            listener?.onMuteOk(muted)
        }

        override fun onGroups(groups: List<String>) = listener?.onGroups(groups) ?: Unit

        override fun onGroupOk(ok: Boolean, group: String?, reason: String?) {
            if (ok) {
                currentGroup = group
                updateNotification()
            }
            listener?.onGroupOk(ok, group, reason)
        }

        override fun onSpeaking(from: String) {
            ensurePlayer()
            listener?.onSpeaking(from)
        }

        override fun onAudio(bytes: ByteArray) {
            ensurePlayer()
            try { player?.write(bytes, 0, bytes.size) } catch (_: Exception) { }
        }
    }
}
