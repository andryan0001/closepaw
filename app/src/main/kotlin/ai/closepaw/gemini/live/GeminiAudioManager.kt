package ai.closepaw.gemini.live

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Full-duplex audio for Gemini Live.
 *
 * - Input: device microphone at 16 kHz 16-bit mono PCM. Each captured chunk
 *   is Base64-encoded and emitted via [onAudioChunkReady] for
 *   `audio/pcm;rate=16000` realtime input.
 * - Output: model-returned 24 kHz 16-bit mono PCM queued via
 *   [enqueuePlayback] and drained to an [AudioTrack] stream.
 *
 * Requires `RECORD_AUDIO` (granted by the caller before [startRecording]).
 * All work runs on [Dispatchers.IO]; call [stop] to release hardware.
 */
class GeminiAudioManager(
    private val onAudioChunkReady: (base64Pcm: String) -> Unit,
) {
    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()

    @SuppressLint("MissingPermission")
    fun startRecording(scope: CoroutineScope) {
        if (audioRecord != null) return
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_BYTES).let { size ->
            if (size == AudioRecord.ERROR || size == AudioRecord.ERROR_BAD_VALUE) {
                MIN_BUFFER_BYTES
            } else {
                size
            }
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }
        audioRecord = record
        record.startRecording()

        recordJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onAudioChunkReady(
                        Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP),
                    )
                }
            }
        }
    }

    fun startPlayback(scope: CoroutineScope) {
        if (audioTrack != null) return
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_BYTES)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_OUT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        playJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = playbackQueue.poll()
                if (chunk != null) {
                    var offset = 0
                    while (offset < chunk.size) {
                        val written = track.write(chunk, offset, chunk.size - offset)
                        if (written < 0) break
                        offset += written
                    }
                } else {
                    delay(10)
                }
            }
        }
    }

    /** Queue model-returned 24 kHz PCM (Base64) for speaker playback. */
    fun enqueuePlayback(base64Pcm: String) {
        try {
            playbackQueue.offer(Base64.decode(base64Pcm, Base64.NO_WRAP))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dropping malformed playback chunk: ${e.message}")
        }
    }

    fun stop() {
        recordJob?.cancel()
        playJob?.cancel()
        recordJob = null
        playJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        } finally {
            audioRecord?.release()
            audioRecord = null
        }
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        } finally {
            audioTrack?.release()
            audioTrack = null
        }
        playbackQueue.clear()
    }

    companion object {
        private const val TAG = "GeminiAudioManager"
        const val SAMPLE_RATE_IN = 16000
        const val SAMPLE_RATE_OUT = 24000
        private const val MIN_BUFFER_BYTES = 4096
    }
}
