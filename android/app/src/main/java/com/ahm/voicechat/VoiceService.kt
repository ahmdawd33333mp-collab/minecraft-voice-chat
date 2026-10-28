package com.ahm.voicechat

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat

/**
 * Foreground service: owns the relay connection, mic capture and playback.
 *
 * Audio, pilot v1: raw PCM 16 kHz mono, 16-bit. No Opus, no echo canceller
 * beyond what the platform provides. Frames are sent as binary WebSocket
 * messages; the relay forwards them opaquely.
 *
 * AUTO-SENSE + PAUSE behaviour, exactly as documented in README.md:
 * - Poll every 5 seconds while the service runs.
 * - Game foreground + name + URL saved + mic permission + auto-sense on
 *   -> connect (if needed) and start mic capture.
 * - Game leaves foreground -> PAUSE mic capture immediately, but DO NOT
 *   disconnect the WebSocket. Playback keeps working while paused, so a
 *   player who alt-tabs briefly still hears others.
 * - Game returns -> resume mic capture automatically (unless muted).
 * - Service stopped (app swiped away / Stop pressed) -> disconnect, stop
 *   capture and playback. Reconnecting is a fresh session; the relay
 *   drops group membership on close, so the player must re-join a group.
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

    // ---- floating mute button (overlay over Minecraft) ----
    // A real button, not a pack HUD element: Bedrock HUD buttons cannot
    // run anything or reach this app, so the button lives in this app as
    // a system overlay (TYPE_APPLICATION_OVERLAY) drawn over the game,
    // bottom-centre by default, draggable anywhere. Tap = mute/unmute
    // through the same setMuted() path as the mic button and the
    // notification action, so the relay hears about it too.
    private var windowManager: WindowManager? = null
    private var overlayButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayShown = false
    private var overlayEnabled = false
    private var gameForegroundNow = false
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
        relay = RelayClient(ForwardingListener())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayEnabled = getSharedPreferences("voicechat", Context.MODE_PRIVATE)
            .getBoolean("overlayEnabled", false)
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
        if (!relay.isConnected) relay.connect(url, name, range)
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
        updateOverlayButton()
    }

    // ---- floating overlay button: public control ----

    fun isOverlayEnabled(): Boolean = overlayEnabled

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(this)

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        getSharedPreferences("voicechat", Context.MODE_PRIVATE).edit()
            .putBoolean("overlayEnabled", enabled).apply()
        refreshOverlay()
    }

    fun requestCode() = relay.requestCode()
    fun groupCreate(name: String, password: String) = relay.groupCreate(name, password)
    fun groupList() = relay.groupList()
    fun groupJoin(name: String, password: String) = relay.groupJoin(name, password)
    fun groupLeave() = relay.groupLeave()

    // ---- game watcher ----

    private val handler = Handler(Looper.getMainLooper())
    private val watcherRunnable = object : Runnable {
        override fun run() {
            if (!autoSenseEnabled) {
                // Manual connect: the user chose to be connected, so the
                // overlay shows whenever connected, no game check.
                gameForegroundNow = true
            } else if (savedName.isNotBlank() && savedUrl.isNotBlank() && micPermissionGranted) {
                if (GameWatcher.isGameForeground(this@VoiceService)) {
                    gameAbsentPolls = 0
                    gameForegroundNow = true
                    if (!relay.isConnected) relay.connect(savedUrl, savedName, savedRange)
                    if (micPausedByGame) {
                        micPausedByGame = false
                        if (!muted) startCapture()
                    }
                } else {
                    // Debounced pause (v5): a single absent poll no longer
                    // pauses the mic. Only after 3 consecutive absent
                    // polls (~15s) do we treat the game as really gone.
                    // Pause mic only. Connection stays open (see class comment).
                    gameAbsentPolls++
                    if (gameAbsentPolls >= 3) {
                        gameForegroundNow = false
                        if (capturing) {
                            micPausedByGame = true
                            stopCapture()
                        }
                    }
                }
            }
            refreshOverlay()
            handler.postDelayed(this, 5_000)
        }
    }

    // ---- floating overlay button: implementation ----

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun refreshOverlay() {
        val shouldShow = overlayEnabled && canDrawOverlay() &&
            relay.isConnected && gameForegroundNow
        if (shouldShow) showOverlay() else hideOverlay()
    }

    private fun showOverlay() {
        if (overlayShown) {
            updateOverlayButton()
            return
        }
        val wm = windowManager ?: return
        val size = dp(72)
        val btn = Button(this)
        btn.text = if (muted) "\uD83D\uDD07" else "\uD83C\uDFA4"
        btn.textSize = 26f
        btn.setTextColor(Color.WHITE)
        btn.setBackgroundColor(
            if (muted) Color.argb(200, 200, 60, 60)
            else Color.argb(200, 40, 160, 60)
        )
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val dm = resources.displayMetrics
        params.x = (dm.widthPixels - size) / 2
        params.y = dm.heightPixels - dp(240)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) dragged = true
                    if (dragged) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try { wm.updateViewLayout(btn, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) setMuted(!muted)
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(btn, params)
            overlayButton = btn
            overlayParams = params
            overlayShown = true
        } catch (_: Exception) {
            overlayShown = false
        }
    }

    private fun hideOverlay() {
        if (!overlayShown) return
        try {
            overlayButton?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        overlayButton = null
        overlayParams = null
        overlayShown = false
    }

    private fun updateOverlayButton() {
        val btn = overlayButton ?: return
        btn.text = if (muted) "\uD83D\uDD07" else "\uD83C\uDFA4"
        btn.setBackgroundColor(
            if (muted) Color.argb(200, 200, 60, 60)
            else Color.argb(200, 40, 160, 60)
        )
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
        hideOverlay()
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
            CHANNEL_ID, "VoiceChat", NotificationManager.IMPORTANCE_LOW
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
            .setContentTitle("VoiceChat")
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
            refreshOverlay()
            listener?.onConnected()
        }

        override fun onDisconnected() {
            stopCapture()
            updateNotification()
            refreshOverlay()
            listener?.onDisconnected()
        }

        override fun onHelloOk(name: String, proximity: Int) {
            if (!muted && micPermissionGranted) startCapture()
            updateNotification()
            refreshOverlay()
            listener?.onHelloOk(name, proximity)
        }

        override fun onCode(code: String) = listener?.onCode(code) ?: Unit
        override fun onVerified(player: String) = listener?.onVerified(player) ?: Unit
        override fun onLinked(player: String) {
            updateNotification()
            listener?.onLinked(player) ?: Unit
        }

        override fun onMuteOk(muted: Boolean) {
            this@VoiceService.muted = muted
            updateNotification()
            updateOverlayButton()
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
