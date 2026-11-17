package com.jarvis.edge.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong

class MicUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class MicStream(
    private val sampleRateHz: Int = SAMPLE_RATE,
    private val frameSize: Int = FRAME_SIZE,
    private val softwareGain: Float = 3.0f,
) {
    private val suppressUntilMs = AtomicLong(0L)

    fun suppressFor(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        suppressUntilMs.updateAndGet { prev -> maxOf(prev, until) }
    }

    fun frames(): Flow<FloatArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw MicUnavailableException("AudioRecord.getMinBufferSize failed: $minBuf")
        }
        val bufferBytes = maxOf(minBuf, frameSize * 2 * 4)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            throw MicUnavailableException("Failed to create AudioRecord", t)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicUnavailableException("AudioRecord not initialized (state=${record.state})")
        }

        val ns = runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            } else null
        }.getOrNull()

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MicUnavailableException("AudioRecord failed to start recording")
            }
            val pcm = ShortArray(frameSize)
            while (currentCoroutineContext().isActive) {
                var offset = 0
                while (offset < frameSize) {
                    val read = record.read(pcm, offset, frameSize - offset)
                    if (read < 0) {
                        throw MicUnavailableException("AudioRecord read error: $read")
                    }
                    if (read == 0) {
                        throw MicUnavailableException("AudioRecord returned 0 bytes")
                    }
                    offset += read
                }
                if (System.currentTimeMillis() < suppressUntilMs.get()) {
                    continue
                }
                val floats = FloatArray(frameSize)
                SimdAudio.processPcm(pcm, 0, frameSize, floats, softwareGain)
                emit(floats)
            }
        } finally {
            runCatching { ns?.release() }
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 512
    }
}
