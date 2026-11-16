/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.media.DecryptableUriMediaInput
import java.io.IOException
import java.nio.ByteOrder

/**
 * Turns a voice message into text on this device, with no network involved.
 *
 * The audio is decoded to the 16 kHz mono the recognizer expects and handed to the Parakeet model that
 * [TranscriptionModel] installs once the user opts in.
 */
object VoiceNoteTranscriber {

  private val TAG = Log.tag(VoiceNoteTranscriber::class)

  private const val OUTPUT_SAMPLE_RATE = 16_000
  private const val FEATURE_DIM = 80
  private const val MODEL_TYPE = "nemo_transducer"
  private const val DECODE_THREADS = 2
  private const val TIMEOUT_US = 5000L
  private const val MAX_IDLE_ITERATIONS = 50
  private const val SHORT_FULL_SCALE = 32768f

  /** True when the user opted in and the model is installed, so a voice message can be transcribed offline. */
  fun isAvailable(context: Context): Boolean {
    return SignalStore.settings.voiceMessageTranscription && TranscriptionModel.isInstalled(context)
  }

  /**
   * Transcribes the audio at [uri].
   *
   * @throws IOException if the audio cannot be decoded or the model cannot be loaded.
   */
  @WorkerThread
  @Throws(IOException::class)
  fun transcribe(context: Context, uri: Uri): String {
    val samples = decodeMono16k(context, uri)

    if (samples.isEmpty()) {
      throw IOException("No audio to transcribe")
    }

    Log.i(TAG, "Transcribing ${samples.size / OUTPUT_SAMPLE_RATE}s of audio")

    val recognizer = try {
      OfflineRecognizer(config = recognizerConfig(context))
    } catch (e: Exception) {
      throw IOException("Could not load the transcription model", e)
    }

    try {
      val stream = recognizer.createStream()
      try {
        stream.acceptWaveform(samples, OUTPUT_SAMPLE_RATE)
        recognizer.decode(stream)

        return recognizer.getResult(stream).text.trim()
      } finally {
        stream.release()
      }
    } finally {
      recognizer.release()
    }
  }

  private fun recognizerConfig(context: Context): OfflineRecognizerConfig {
    return OfflineRecognizerConfig(
      featConfig = FeatureConfig(sampleRate = OUTPUT_SAMPLE_RATE, featureDim = FEATURE_DIM),
      modelConfig = OfflineModelConfig(
        transducer = OfflineTransducerModelConfig(
          encoder = TranscriptionModel.encoder(context).absolutePath,
          decoder = TranscriptionModel.decoder(context).absolutePath,
          joiner = TranscriptionModel.joiner(context).absolutePath
        ),
        tokens = TranscriptionModel.tokens(context).absolutePath,
        modelType = MODEL_TYPE,
        numThreads = DECODE_THREADS
      )
    )
  }

  /**
   * Decodes the audio at [uri] into 16 kHz mono samples in the [-1, 1] range the recognizer wants.
   *
   * Follows the decode loop of [AudioWaveFormGenerator], minus the ADTS batching that only pays off there.
   */
  @WorkerThread
  @Throws(IOException::class)
  private fun decodeMono16k(context: Context, uri: Uri): FloatArray {
    DecryptableUriMediaInput.createForUri(context, uri).use { dataSource ->
      val extractor = dataSource.createExtractor()

      if (extractor.trackCount == 0) {
        throw IOException("No audio track")
      }

      val format = extractor.getTrackFormat(0)
      val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("No mime type")

      if (!mime.startsWith("audio/")) {
        throw IOException("Mime not audio")
      }

      val codec = MediaCodec.createDecoderByType(mime)
      codec.configure(format, null, null, 0)
      codec.start()

      extractor.selectTrack(0)

      var resampler = Resampler(format.readSampleRate(), format.readChannelCount())
      val chunks = mutableListOf<ShortArray>()
      val info = MediaCodec.BufferInfo()
      var scratch = ShortArray(0)
      var extractorDone = false
      var inputEOSQueued = false
      var sawOutputEOS = false
      var idleCounter = 0

      try {
        while (!sawOutputEOS && idleCounter < MAX_IDLE_ITERATIONS) {
          var fedInput = false

          while (!inputEOSQueued) {
            val inputBufferIndex = codec.dequeueInputBuffer(0)
            if (inputBufferIndex < 0) {
              break
            }

            fedInput = true

            val sampleSize = if (extractorDone) -1 else extractor.readSampleData(codec.getInputBuffer(inputBufferIndex)!!, 0)

            if (sampleSize < 0) {
              codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputEOSQueued = true
            } else {
              codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
              extractorDone = !extractor.advance()
            }
          }

          var gotOutput = false
          var outputBufferIndex: Int
          do {
            // Only block when the decoder has nothing new to chew on, otherwise poll so input keeps flowing
            outputBufferIndex = codec.dequeueOutputBuffer(info, if (gotOutput || fedInput) 0 else TIMEOUT_US)

            if (outputBufferIndex >= 0) {
              gotOutput = true

              val buffer = codec.getOutputBuffer(outputBufferIndex)
              if (info.size > 0 && buffer != null) {
                val sampleCount = info.size / 2

                if (scratch.size < sampleCount) {
                  scratch = ShortArray(sampleCount)
                }

                val pcm = buffer.duplicate()
                pcm.order(ByteOrder.nativeOrder())
                pcm.limit(info.offset + info.size)
                pcm.position(info.offset)
                pcm.asShortBuffer().get(scratch, 0, sampleCount)

                chunks += resampler.resample(scratch, sampleCount)
              }

              codec.releaseOutputBuffer(outputBufferIndex, false)

              if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                sawOutputEOS = true
              }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
              resampler = Resampler(codec.outputFormat.readSampleRate(format), codec.outputFormat.readChannelCount(format))
            }
          } while (outputBufferIndex >= 0 && !sawOutputEOS)

          idleCounter = if (fedInput || gotOutput) 0 else idleCounter + 1
        }

        chunks += resampler.drain()
      } finally {
        codec.stop()
        codec.release()
        extractor.release()
      }

      if (!sawOutputEOS) {
        Log.w(TAG, "Decoder went idle before the end of the audio")
      }

      return chunks.toFloatArray()
    }
  }

  /** The recognizer takes one array of samples, so the decoded chunks are flattened and normalized here. */
  private fun List<ShortArray>.toFloatArray(): FloatArray {
    val samples = FloatArray(sumOf { it.size })
    var index = 0

    for (chunk in this) {
      for (sample in chunk) {
        samples[index++] = sample / SHORT_FULL_SCALE
      }
    }

    return samples
  }

  private fun MediaFormat.readSampleRate(fallback: MediaFormat? = null): Int {
    return getPositiveIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: fallback?.getPositiveIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
  }

  private fun MediaFormat.readChannelCount(fallback: MediaFormat? = null): Int {
    return getPositiveIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: fallback?.getPositiveIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
  }

  private fun MediaFormat.getPositiveIntegerOrNull(key: String): Int? {
    return if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null
  }

  /**
   * Mixes interleaved PCM 16 down to mono and converts it to [OUTPUT_SAMPLE_RATE].
   *
   * Downsampling averages the input frames that fall into an output sample, which doubles as a crude low pass filter;
   * upsampling repeats the last value. State is carried across buffers so the output stays in step with the input.
   */
  @VisibleForTesting
  internal class Resampler(private val inputSampleRate: Int, private val channelCount: Int) {

    private val framesPerOutputSample = inputSampleRate.toDouble() / OUTPUT_SAMPLE_RATE

    private var inputFrames = 0L
    private var outputSamples = 0L
    private var sum = 0L
    private var count = 0
    private var last: Short = 0

    fun resample(samples: ShortArray, sampleCount: Int): ShortArray {
      val frameCount = sampleCount / channelCount
      val output = ShortArray((frameCount / framesPerOutputSample).toInt() + 2)
      var written = 0

      for (frame in 0 until frameCount) {
        var frameSum = 0
        for (channel in 0 until channelCount) {
          frameSum += samples[frame * channelCount + channel]
        }

        sum += frameSum / channelCount
        count++
        inputFrames++

        val expectedOutputSamples = (inputFrames / framesPerOutputSample).toLong()
        while (outputSamples < expectedOutputSamples) {
          output[written++] = average()
          outputSamples++
          sum = 0
          count = 0
        }
      }

      return output.copyOf(written)
    }

    /** Flushes the samples left over at the end of the audio. */
    fun drain(): ShortArray {
      return if (count > 0) {
        shortArrayOf(average()).also {
          sum = 0
          count = 0
        }
      } else {
        ShortArray(0)
      }
    }

    private fun average(): Short {
      if (count > 0) {
        last = (sum / count).toShort()
      }

      return last
    }
  }
}
