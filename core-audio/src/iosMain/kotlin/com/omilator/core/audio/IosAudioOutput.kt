@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.omilator.core.audio

import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.NSLog
import platform.Foundation.NSLock
import platform.Foundation.NSError

class IosAudioOutput : AudioOutput {
    override var sampleRate: Double = 0.0
        private set
    override var channels: Int = 2
        private set

    private var engine: AVAudioEngine? = null
    private var playerNode: AVAudioPlayerNode? = null
    private var srcFormat: AVAudioFormat? = null
    private var started = false
    private var pendingBuffers: Int = 0

    /**
     * pendingBuffers is touched from the core thread (write), the audio
     * completion callbacks, and flush() — a plain Int is a data race, and a
     * stale count after flush() left the cap permanently saturated. The lock
     * plus generation invalidates completion callbacks from before a flush.
     */
    private val pendingLock = NSLock()
    private var generation = 0L
    private var firstBufferLogged = false

    override fun configure(sampleRate: Double, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        stop()

        // AVAudioSession MUST be active before AVAudioEngine produces sound.
        // Without .playback + setActive(true), scheduleBuffer silently no-ops
        // (engine appears to run but produces no output).
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val catOk = session.setCategory(
                AVAudioSessionCategoryPlayback,
                AVAudioSessionModeDefault,
                0u,
                err.ptr,
            )
            if (!catOk) NSLog("[Audio] setCategory failed: ${err.value?.localizedDescription ?: "(nil)"}")
            val actOk = session.setActive(true, 0u, err.ptr)
            if (!actOk) NSLog("[Audio] setActive failed: ${err.value?.localizedDescription ?: "(nil)"}")
        }

        // Float32 non-interleaved: canonical iOS path. AVAudioEngine converts
        // to the HW format at the mixer node automatically. Avoids the
        // int16ChannelData AudioBufferList quirks.
        val fmt = AVAudioFormat(
            commonFormat = AVAudioPCMFormatFloat32,
            sampleRate = sampleRate,
            channels = channels.toUInt(),
            interleaved = false,
        ) ?: run {
            NSLog("[Audio] failed to build source format")
            return
        }
        srcFormat = fmt

        val eng = AVAudioEngine()
        val node = AVAudioPlayerNode()
        eng.attachNode(node)
        eng.connect(node, eng.mainMixerNode, fmt)
        engine = eng
        playerNode = node
        eng.prepare()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val ok = eng.startAndReturnError(err.ptr)
            if (!ok) NSLog("[Audio] engine start failed: ${err.value?.localizedDescription ?: "(nil)"}")
        }
        node.play()
        started = true
        NSLog("[Audio] Started: ${sampleRate}Hz ${channels}ch -> HW ${eng.outputNode.outputFormatForBus(0u).sampleRate}Hz")
    }

    override fun write(samples: ShortArray): Int {
        if (!started || samples.isEmpty()) return samples.size
        val node = playerNode ?: return samples.size
        val fmt = srcFormat ?: return samples.size

        // Backpressure: cap queued buffers so a stalled engine can't grow
        // memory unbounded. Drop new input until something drains. Torn read
        // is harmless — at worst we drop or queue one extra batch.
        // Reserve LAST: every failure between the old reservation and the
        // schedule permanently consumed a backpressure slot.
        val frames = samples.size / channels
        if (frames == 0) return samples.size
        val buffer = AVAudioPCMBuffer(pCMFormat = fmt, frameCapacity = frames.toUInt())
            ?: return samples.size
        buffer.frameLength = frames.toUInt()
        val channelsPtr = buffer.floatChannelData ?: run {
            // buffer leaked below is fine (ARC); no reservation was taken yet
            return samples.size
        }
        val scale = 1f / 32768f
        if (channels == 2) {
            val left = channelsPtr[0]!!
            val right = channelsPtr[1]!!
            var i = 0
            for (f in 0 until frames) {
                left[f] = samples[i].toFloat() * scale
                right[f] = samples[i + 1].toFloat() * scale
                i += 2
            }
        } else {
            val ch = channelsPtr[0]!!
            for (f in 0 until frames) {
                ch[f] = samples[f].toFloat() * scale
            }
        }
        val myGeneration = pendingLock.withLock {
            if (pendingBuffers >= MAX_PENDING) return samples.size
            pendingBuffers++
            generation
        }

        if (!firstBufferLogged) {
            firstBufferLogged = true
            NSLog("[Audio] first buffer scheduled: $frames frames")
        }
        node.scheduleBuffer(buffer) {
            pendingLock.withLock {
                if (myGeneration == generation) {
                    pendingBuffers = (pendingBuffers - 1).coerceAtLeast(0)
                }
            }
        }
        return samples.size
    }

    override fun flush() {
        // Drop queued buffers (used by rewind / run-ahead). Bumping the
        // generation first means completion callbacks already in flight
        // decrement nothing; the counter is reset here, not by them.
        pendingLock.withLock {
            generation++
            pendingBuffers = 0
        }
        playerNode?.reset()
    }

    override fun release() {
        stop()
    }

    private fun stop() {
        // Invalidate before tearing the node down: callbacks already queued
        // by the old node must not decrement a future session's counter.
        pendingLock.withLock {
            generation++
            pendingBuffers = 0
        }
        if (started) {
            playerNode?.stop()
            engine?.stop()
            // Tell iOS we're done so background audio apps can resume.
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                AVAudioSession.sharedInstance().setActive(
                    false,
                    AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    err.ptr,
                )
            }
            started = false
            pendingBuffers = 0
        }
        engine = null
        playerNode = null
        srcFormat = null
    }

    private companion object {
        // ~8 batches at 60fps = ~130ms of buffer. Enough jitter slack without
        // being a latency sink.
        const val MAX_PENDING = 8
    }
}

private inline fun <T> platform.Foundation.NSLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
