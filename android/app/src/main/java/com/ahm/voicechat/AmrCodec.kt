package com.ahm.voicechat

import android.media.MediaCodec
import android.media.MediaFormat

/**
 * AMR-NB voice codec through Android's built-in MediaCodec (app v8).
 *
 * WHY: the pilot sent raw PCM 16 kHz mono — 256 kbps per direction — and
 * the player's own test proved the app is what lags the game (app OFF
 * next to the friend = lag gone). AMR-NB at 12.2 kbps cuts the audio
 * payload ~20x (~34 kbps total with framing overhead), which is what
 * fixes the congestion that stutters the game and cuts the voice.
 *
 * No native library, no JNI, no new dependency: AMR-NB encode/decode
 * is part of Android's mandatory media formats, so it exists on every
 * certified device. If a device somehow lacks the codec, construction
 * throws and the caller falls back to the legacy raw-PCM path.
 *
 * Framing: one AMR frame (20 ms) per WebSocket binary message, prefixed
 * with the 4 magic bytes 'A' 'M' 'R' 0x01. The relay forwards binary
 * opaquely, so it needs no change. A receiver checks the magic: magic
 * present -> AMR frame, decode to 8 kHz PCM; magic absent -> legacy raw
 * PCM 16 kHz from an older app version, played as before. (The reverse
 * direction — a NEW app sending AMR to an OLD app — plays as noise on
 * the old app, so both phones must run v8 for the test.)
 */
object AmrCodec {
    const val SAMPLE_RATE = 8000
    const val MAGIC_0: Byte = 0x41 // 'A'
    const val MAGIC_1: Byte = 0x4D // 'M'
    const val MAGIC_2: Byte = 0x52 // 'R'
    const val MAGIC_3: Byte = 0x01

    fun isAmrFrame(bytes: ByteArray): Boolean {
        return bytes.size > 4 &&
            bytes[0] == MAGIC_0 && bytes[1] == MAGIC_1 &&
            bytes[2] == MAGIC_2 && bytes[3] == MAGIC_3
    }

    fun prefixFrame(frame: ByteArray): ByteArray {
        val out = ByteArray(frame.size + 4)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = MAGIC_2
        out[3] = MAGIC_3
        frame.copyInto(out, 4)
        return out
    }
}

class AmrEncoder {
    private val codec: MediaCodec
    private var samplesFed: Long = 0
    private var released = false

    init {
        codec = MediaCodec.createEncoderByType("audio/3gpp")
        val format = MediaFormat.createAudioFormat("audio/3gpp", AmrCodec.SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 12200) // MR122, best AMR-NB mode
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Feed PCM 8 kHz mono 16-bit, return any complete AMR frames produced. */
    fun encode(pcm: ByteArray, length: Int): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < length) {
            val inIndex = try {
                codec.dequeueInputBuffer(10_000)
            } catch (_: Exception) {
                break
            }
            if (inIndex < 0) break
            try {
                val inBuf = codec.getInputBuffer(inIndex) ?: break
                val chunk = minOf(inBuf.remaining(), length - offset)
                if (chunk <= 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    break
                }
                inBuf.put(pcm, offset, chunk)
                val ptsUs = samplesFed * 1_000_000L / AmrCodec.SAMPLE_RATE
                samplesFed += chunk / 2
                codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                offset += chunk
            } catch (_: Exception) {
                break
            }
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (_: Exception) {
                break
            }
            if (outIndex < 0) break
            try {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null) {
                        val frame = ByteArray(info.size)
                        outBuf.get(frame)
                        frames.add(frame)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    codec.releaseOutputBuffer(outIndex, false)
                } catch (_: Exception) {
                }
            }
        }
        return frames
    }

    fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
    }
}

class AmrDecoder {
    private val codec: MediaCodec
    private var samplesFed: Long = 0
    private var released = false

    init {
        codec = MediaCodec.createDecoderByType("audio/3gpp")
        val format = MediaFormat.createAudioFormat("audio/3gpp", AmrCodec.SAMPLE_RATE, 1)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** Decode one AMR frame, return 8 kHz mono PCM 16-bit (empty if none yet). */
    fun decode(frame: ByteArray): ByteArray {
        val pcmOut = mutableListOf<Byte>()
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf = codec.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.put(frame)
                    val ptsUs = samplesFed * 1_000_000L / AmrCodec.SAMPLE_RATE
                    samplesFed += 160 // one AMR frame = 20 ms = 160 samples at 8 kHz
                    codec.queueInputBuffer(inIndex, 0, frame.size, ptsUs, 0)
                }
            }
        } catch (_: Exception) {
            return ByteArray(0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (_: Exception) {
                break
            }
            if (outIndex < 0) break
            try {
                if (info.size > 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null) {
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        for (b in chunk) pcmOut.add(b)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    codec.releaseOutputBuffer(outIndex, false)
                } catch (_: Exception) {
                }
            }
        }
        return pcmOut.toByteArray()
    }

    fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
    }
}
