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
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Foreground service: owns the relay connection, mic capture and playback.
 *
 * Audio, app v8: AMR-NB 12.2 kbps (8 kHz mono) through Android's built-in
 * MediaCodec — ~20x less audio bandwidth than the pilot's raw PCM 16 kHz
 * mono (256 kbps), which the player's own test proved was lagging the
 * game (app OFF next to the friend = lag gone). One AMR frame (20 ms)
 * per binary WebSocket message, magic-prefixed; the relay forwards them
 * opaquely and needs no change. If the device lacks the AMR codec,
 * capture falls back to legacy raw PCM 16 kHz. No echo canceller
 * beyond what the platform provides (headphones remain the workaround
 * for speaker echo).
 *
 * Audio, app v9 (lag/cutting round 2 — v8 only fixed bandwidth):
 * - Capture thread runs at THREAD_PRIORITY_URGENT_AUDIO and reads in
 *   steady 20 ms chunks, so the network sees one frame per 20 ms
 *   instead of bursts; the game can no longer starve the mic.
 * - Playout moved off the network thread: decoded PCM goes through a
 *   jitter-buffer queue to a dedicated URGENT_AUDIO playout thread.
 *   The queue caps at ~300 ms (oldest dropped, so latency cannot
 *   snowball); on underrun the last frame repeats up to 5x (simple
 *   packet-loss concealment) instead of silence, then silence until
 *   data resumes. After starvation the thread re-primes ~60 ms.
 * - Playback gain ~1.8x with clamping (the second phone's weak sound)
 *   and forced loudspeaker routing (MODE_IN_COMMUNICATION +
 *   speakerphone on: USAGE_VOICE_COMMUNICATION alone routes to the
 *   earpiece on some phones).
 *
 * Audio, app v10 (link-gated mic + cutting round 3):
 * - The mic now starts ONLY when the pack links (onLinked = player
 *   actually in the server). Pressing Connect no longer runs the mic
 *   — or its indicator dot — before server entry, matching the
 *   relay's mic gate at the source instead of only at the relay.
 * - v9's silent re-prime wait is REMOVED: it slept up to 300 ms
 *   without feeding the track, which made gaps longer, not shorter.
 *   The track buffer + repeat-last-frame concealment is the cushion;
 *   playback resumes on the first frame.
 *
 * Audio, app v11 (target playout delay — validated in simulation):
 * - Every received frame waits until it is 250 ms old before playing.
 *   Steady state therefore always holds ~250 ms of audio, so a TCP
 *   stall of up to ~300 ms is covered by the buffer + PLC with
 *   essentially no audible gap (rig: stall phase silence 1 tick,
 *   max gap 20 ms, mean latency 307 ms; jitter phase silence 2 ticks,
 *   max gap 40 ms). Cost: ~300 ms mouth-to-ear delay, far better
 *   than cutting. This is the app-only answer to TCP stalls; UDP
 *   stays the fallback if real-world stalls exceed ~300 ms.
 *
 * Audio, app v12 (game-lag fix + new icon):
 * - Playback moved off the phone-call audio path: USAGE_MEDIA instead
 *   of USAGE_VOICE_COMMUNICATION, and MODE_IN_COMMUNICATION is no
 *   longer set. The call path forced game audio through the call
 *   mixer and is the prime suspect for the game lagging on both
 *   phones while the app ran (charger unplugged: still lagged).
 *   Media usage still plays from the loudspeaker by default, so
 *   the v9 volume fix should survive. Possible cost: the media
 *   path's larger buffers may add ~100 ms of voice delay on top
 *   of v11's 250 ms. If the game still lags on v12, the next
 *   step is the game-sound-off test + profiling, not another
 *   routing guess.
 * - Launcher icon: blocky Minecraft-style player holding a
 *   microphone (was the default Android robot: the manifest set
 *   no android:icon at all).
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
 * - Game absent for 3 consecutive polls (~15 seconds) AFTER gameplay
 *   was seen -> FULL STOP: mic capture stops and the relay
 *   disconnects. The service itself keeps watching, so re-entering
 *   the game auto-connects again within seconds. A brief alt-tab
 *   (under ~15s) does not stop anything.
 * - Absence is only counted after the game was actually foreground
 *   (hadGameForeground), and never while this app itself is
 *   foreground: otherwise merely opening this app — game not
 *   foreground — would count as "leaving" and kill a connection
 *   seconds after pressing Connect (the filmed v6 bug).
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
    // v14 voice filter: platform echo canceller + noise suppressor +
    // gain control, attached to the capture session. Released in
    // stopCapture(). Null when the device does not offer an effect.
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var player: AudioTrack? = null
    private var amrPlayer: AudioTrack? = null
    private var amrEncoder: AmrEncoder? = null
    private var amrDecoder: AmrDecoder? = null
    private var captureThread: Thread? = null
    // v9 playout: (sampleRate, PCM) pairs through a jitter-buffer queue
    // to a dedicated thread. Writing to AudioTrack on the OkHttp reader
    // thread (v8) blocked the socket reader whenever the track filled,
    // which snowballed latency; the queue decouples the two.
    // v11: each queued frame carries its arrival time, so the playout
    // thread can hold it until its target playout time (250 ms).
    private data class AudioChunk(val rate: Int, val pcm: ByteArray, val arrivedAt: Long)
    private val playoutDelayMs = 250L
    private val playoutQueue = LinkedBlockingQueue<AudioChunk>()
    private var playoutThread: Thread? = null
    @Volatile private var playoutRunning = false
    // v15: start SILENT. A fresh install used to play any frame the
    // relay routed to it before the first pack link (global frames
    // reach every named session), so opening the app alone let a
    // friend's voice through. Now nothing plays until onLinked.
    @Volatile private var playoutPaused = true
    private var lastChunk: AudioChunk? = null
    @Volatile private var capturing: Boolean = false
    @Volatile private var micPausedByGame: Boolean = false
    // The linked pack closed (player left the server, game still open):
    // audio is paused but the session stays, so a rejoin re-links.
    private var packGone = false
    // v10: true only while the pack has linked this app session
    // (player actually in the server). The mic gate: capture starts
    // on onLinked, never on hello_ok alone.
    @Volatile private var appLinked = false
    // Debounce for the mic-flicker bug (v5): the old watcher paused the
    // mic on a single 5s poll where the game looked absent (shade
    // pull-down, app switch, usage-stats lag), so the mic indicator
    // flickered and audio cut in and out. Now the mic only pauses after
    // the game has been absent for 3 consecutive polls (~15 seconds).
    private var gameAbsentPolls = 0
    // True once the game has been seen in the foreground during this
    // service lifetime. Auto-stop only counts absence AFTER gameplay:
    // without this, merely opening this app (game not foreground)
    // would count as "leaving" and kill a connection seconds after
    // pressing Connect — the exact bug the player filmed in v6.
    private var hadGameForeground = false
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
        ensurePlayoutThread()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VoiceChat idle"))
        startWatcher()
        // v15: reconnect from saved prefs whenever the service is
        // (re)created — boot, or a START_STICKY restart after the
        // system killed the process. Before this, background voice
        // died with the process and only came back when the app was
        // opened again. Audio and mic stay gated on the pack link,
        // so an idle connection plays and sends nothing.
        if (savedName.isNotBlank() && savedUrl.isNotBlank()) {
            relay.connect(savedUrl, savedName, savedRange)
        }
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
        if (m) stopCapture() else if (micPermissionGranted && !micPausedByGame && appLinked) startCapture()
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
                when {
                    GameWatcher.isGameForeground(this@VoiceService) -> {
                        hadGameForeground = true
                        gameAbsentPolls = 0
                        if (!relay.isConnected) relay.connect(savedUrl, savedName, savedRange)
                        if (micPausedByGame) {
                            micPausedByGame = false
                            if (!muted && appLinked) startCapture()
                        }
                    }
                    GameWatcher.isAppForeground(this@VoiceService) -> {
                        // User is looking at this app (e.g. just pressed
                        // Connect): not leaving the game. Pause the
                        // absence count here — do not disconnect, and
                        // do not reset the count either.
                    }
                    hadGameForeground -> {
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
                            hadGameForeground = false
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
        // v8: try AMR first (8 kHz capture + encode). If the codec is
        // missing on this device, fall back to legacy raw PCM 16 kHz.
        var encoder: AmrEncoder? = null
        var rate = sampleRate
        try {
            encoder = AmrEncoder()
            rate = AmrCodec.SAMPLE_RATE
        } catch (_: Exception) {
            encoder = null
            rate = sampleRate
        }
        val minBuf = AudioRecord.getMinBufferSize(rate, channelIn, encoding)
        if (minBuf <= 0) {
            encoder?.release()
            return
        }
        val rec = AudioRecord(
            // v14: VOICE_COMMUNICATION (not raw MIC) so the platform
            // voice effects below actually attach. Playback is
            // untouched (v12 media path), so no game-lag return.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate, channelIn, encoding, minBuf * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            encoder?.release()
            return
        }
        recorder = rec
        amrEncoder = encoder
        // v14 voice filter: kill the whistle (echo of the other
        // phone's speaker picked up again by the mic), cut background
        // noise, and lift a quiet mic. Human voice passes through;
        // the filter runs on the SENDER's phone, so both phones need
        // v14 for both directions. Every effect is guarded: if the
        // device does not offer it, capture works exactly as before.
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) { echoCanceler = null }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) { noiseSuppressor = null }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) { gainControl = null }
        capturing = true
        rec.startRecording()
        captureThread = Thread {
            // v9: urgent audio priority — the game must not starve mic
            // capture — and steady 20 ms reads (320 bytes at 8 kHz AMR,
            // 640 at 16 kHz PCM) so the network sees one frame per 20 ms
            // instead of a burst every 64-256 ms.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frameBytes = if (amrEncoder != null) 320 else 640
            val buf = ByteArray(frameBytes)
            while (capturing) {
                val read = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (read > 0 && relay.isConnected) {
                    val enc = amrEncoder
                    if (enc != null) {
                        try {
                            for (frame in enc.encode(buf, read)) {
                                val msg = AmrCodec.prefixFrame(frame)
                                relay.sendAudio(msg, msg.size)
                            }
                        } catch (_: Exception) { }
                    } else {
                        relay.sendAudio(buf, read)
                    }
                }
            }
        }.also { it.start() }
        ensurePlayer()
    }

    private fun stopCapture() {
        capturing = false
        try { captureThread?.join(500) } catch (_: Exception) { }
        captureThread = null
        try { recorder?.stop() } catch (_: Exception) { }
        // v14: release the voice-filter effects with the session.
        try { echoCanceler?.release() } catch (_: Exception) { }
        echoCanceler = null
        try { noiseSuppressor?.release() } catch (_: Exception) { }
        noiseSuppressor = null
        try { gainControl?.release() } catch (_: Exception) { }
        gainControl = null
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        amrEncoder?.release()
        amrEncoder = null
    }

    // ---- v9 playout: jitter buffer + concealment + gain + speaker ----

    private fun ensurePlayoutThread() {
        if (playoutRunning) return
        playoutRunning = true
        // v12: NO voice-call audio mode. MODE_IN_COMMUNICATION (v9)
        // forced every stream — game audio included — through the
        // phone-call mixer, and the game lagged on both phones while
        // the app ran (unplugging the charger did not help, so it is
        // not heat). Media usage routes to the loudspeaker by
        // default, so the second phone's volume fix survives without
        // the call path. A connected headset still wins over this flag.
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = true
        } catch (_: Exception) { }
        // Tracks exist before any audio arrives, so the playout thread
        // never has to create them (no race with relay callbacks).
        ensurePlayer()
        ensureAmrPlayer()
        playoutThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var repeatCount = 0
            while (playoutRunning) {
                if (playoutPaused || !appLinked) {
                    // Not linked inside the server yet (fresh start,
                    // socket drop, or left the server): drop incoming
                    // audio, keep polling. The mic is link-gated too,
                    // so voice works ONLY while linked in-game.
                    try { playoutQueue.poll(50, TimeUnit.MILLISECONDS) } catch (_: Exception) { }
                    continue
                }
                // Latency snowball guard: never hold more than ~300 ms.
                while (playoutQueue.size > 15) playoutQueue.poll()
                val head = playoutQueue.peek()
                if (head == null) {
                    // Underrun: repeat the last frame a few times (PLC)
                    // instead of a silence gap, then silence until data.
                    val last = lastChunk
                    if (last != null && repeatCount < 5) {
                        repeatCount++
                        writeChunk(last)
                    } else {
                        val rate = last?.rate ?: AmrCodec.SAMPLE_RATE
                        writeChunk(AudioChunk(rate, ByteArray(if (rate == AmrCodec.SAMPLE_RATE) 320 else 640), System.currentTimeMillis()))
                    }
                } else {
                    val age = System.currentTimeMillis() - head.arrivedAt
                    if (age < playoutDelayMs) {
                        // v11 target delay: the frame is not due yet.
                        // Sleep briefly and re-check — this is intentional
                        // buffering, not a gap: the track keeps playing
                        // previously written audio from its own buffer.
                        try { Thread.sleep(minOf(20L, playoutDelayMs - age)) } catch (_: Exception) { }
                    } else {
                        val chunk = playoutQueue.poll()
                        if (chunk != null) {
                            lastChunk = chunk
                            repeatCount = 0
                            writeChunk(chunk)
                        }
                    }
                }
            }
        }.also { it.start() }
    }

    private fun writeChunk(chunk: AudioChunk) {
        val pcm = chunk.pcm
        // ~1.8x gain with clamping: the second phone's weak playback.
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            var s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            s = (s * 1.8f).toInt().coerceIn(-32768, 32767)
            out[i] = (s and 0xFF).toByte()
            out[i + 1] = (s shr 8).toByte()
            i += 2
        }
        try {
            if (chunk.rate == AmrCodec.SAMPLE_RATE) amrPlayer?.write(out, 0, out.size)
            else player?.write(out, 0, out.size)
        } catch (_: Exception) { }
    }

    private fun ensurePlayer() {
        if (player != null) return
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        if (minBuf <= 0) return
        player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelOut)
                    .setEncoding(encoding)
                    .build()
            )
            // x4 buffer (was x2): headroom against network jitter, so
            // playback gaps — one half of the "cutting" complaint — get rarer.
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try { player?.play() } catch (_: Exception) { }
    }

    private fun ensureAmrPlayer() {
        if (amrPlayer != null) return
        val minBuf = AudioTrack.getMinBufferSize(AmrCodec.SAMPLE_RATE, channelOut, encoding)
        if (minBuf <= 0) return
        amrPlayer = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AmrCodec.SAMPLE_RATE)
                    .setChannelMask(channelOut)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try { amrPlayer?.play() } catch (_: Exception) { }
    }

    private fun stopAll() {
        handler.removeCallbacks(watcherRunnable)
        playoutRunning = false
        try { playoutThread?.join(500) } catch (_: Exception) { }
        playoutThread = null
        playoutQueue.clear()
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) { }
        stopCapture()
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        try { amrPlayer?.stop() } catch (_: Exception) { }
        try { amrPlayer?.release() } catch (_: Exception) { }
        amrPlayer = null
        amrDecoder?.release()
        amrDecoder = null
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
            relay.isConnected -> if (appLinked) "Connected as $savedName" else "Connected — mic starts when you enter the server"
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
            appLinked = false
            stopCapture()
            updateNotification()
            listener?.onDisconnected()
        }

        override fun onHelloOk(name: String, proximity: Int) {
            packGone = false
            // v10: do NOT start the mic here. The mic starts only when
            // the pack links (onLinked = player actually in the server),
            // so pressing Connect never runs the mic — or its indicator
            // dot — before server entry.
            updateNotification()
            listener?.onHelloOk(name, proximity)
        }

        override fun onCode(code: String) = listener?.onCode(code) ?: Unit
        override fun onVerified(player: String) = listener?.onVerified(player) ?: Unit
        override fun onLinked(player: String) {
            // Rejoin: the pack is back, resume audio at once.
            packGone = false
            appLinked = true
            playoutPaused = false
            playoutQueue.clear()
            ensurePlayer()
            try { this@VoiceService.player?.play() } catch (_: Exception) { }
            try { this@VoiceService.amrPlayer?.play() } catch (_: Exception) { }
            if (!muted && micPermissionGranted) startCapture()
            updateNotification()
            listener?.onLinked(player) ?: Unit
        }

        override fun onPackGone() {
            // v13: player left the server — the session ends HERE, in
            // that second (no lingering paused session). It returns on
            // re-enter: the watcher reconnects when the game is
            // foreground, and the pack re-links by name at once.
            packGone = true
            appLinked = false
            playoutPaused = true
            playoutQueue.clear()
            stopCapture()
            relay.disconnect()
            try { player?.pause() } catch (_: Exception) { }
            try { amrPlayer?.pause() } catch (_: Exception) { }
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
            // v9: never write to AudioTrack here — the OkHttp reader
            // thread must keep draining the socket. Decode (AMR) and
            // enqueue PCM; the playout thread owns the track.
            if (AmrCodec.isAmrFrame(bytes)) {
                try {
                    if (amrDecoder == null) amrDecoder = AmrDecoder()
                    val pcm = amrDecoder?.decode(bytes.copyOfRange(4, bytes.size)) ?: ByteArray(0)
                    if (pcm.isNotEmpty()) playoutQueue.offer(AudioChunk(AmrCodec.SAMPLE_RATE, pcm, System.currentTimeMillis()))
                } catch (_: Exception) { }
            } else {
                // Legacy raw PCM 16 kHz from an older app version.
                playoutQueue.offer(AudioChunk(sampleRate, bytes, System.currentTimeMillis()))
            }
        }
    }
}
