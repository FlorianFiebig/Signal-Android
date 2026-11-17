/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.sink
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The speech recognition model that transcribes voice messages, and the one-time download that installs it.
 *
 * The model is Parakeet TDT 0.6b v3 in sherpa-onnx form, which covers 25 European languages including German. It is
 * pinned to one revision and every file is checked against its SHA-256 before it is used, because this downloads
 * hundreds of megabytes of code input over the plain internet.
 *
 * The download is the only network traffic this feature ever makes, it happens only after the user opts in, and it
 * tells huggingface.co that someone enabled transcription. Transcribing itself never touches the network.
 */
object TranscriptionModel {

  private val TAG = Log.tag(TranscriptionModel::class)

  private const val REVISION = "ed5d745623472f97cfc2e6c2916ee248798fc5a8"
  private const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/$REVISION"

  private const val DIRECTORY = "transcription-model"
  private const val PARTIAL_SUFFIX = ".partial"
  private const val BUFFER_SIZE = 256 * 1024L

  @VisibleForTesting
  val files = listOf(
    ModelFile("encoder.int8.onnx", size = 652_282_294L, sha256 = "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
    ModelFile("decoder.int8.onnx", size = 11_845_274L, sha256 = "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
    ModelFile("joiner.int8.onnx", size = 6_355_277L, sha256 = "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
    ModelFile("tokens.txt", size = 93_939L, sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
  )

  /** How much has to be downloaded, so the user can be told before they agree to it. */
  val downloadSize: Long = files.sumOf { it.size }

  private val scope = CoroutineScope(SupervisorJob() + SignalDispatchers.IO)
  private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

  /** What the install is doing, for the settings screen to show. Survives leaving that screen, but not process death. */
  val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

  fun isInstalled(context: Context): Boolean {
    return files.all { it.isComplete(context) }
  }

  /** Starts, or resumes, the one-time install. Does nothing if it is already running. */
  fun startDownload(context: Context) {
    if (_downloadState.value is DownloadState.Running) {
      return
    }

    _downloadState.value = DownloadState.Running(0, downloadSize)

    scope.launch {
      try {
        download(context) { downloaded, total ->
          // The download blocks, so this is where a cancel gets noticed: at most one buffer later.
          ensureActive()
          _downloadState.value = DownloadState.Running(downloaded, total)
        }
        _downloadState.value = DownloadState.Idle
      } catch (e: CancellationException) {
        // A progress update can slip in after cancelDownload() reset the state, so reset it again on the way out.
        _downloadState.value = DownloadState.Idle
        throw e
      } catch (e: Exception) {
        // Opting out deletes the files under a download that is hashing or moving one, which is no failure to show.
        if (isActive) {
          Log.w(TAG, "Model download failed", e)
          _downloadState.value = DownloadState.Failed
        }
      }
    }
  }

  fun cancelDownload() {
    scope.coroutineContext.cancelChildren()
    _downloadState.value = DownloadState.Idle
  }

  sealed interface DownloadState {
    /** Nothing is downloading, either because the model is installed or because nobody asked for it. */
    data object Idle : DownloadState

    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadState

    data object Failed : DownloadState
  }

  fun encoder(context: Context): File = file(context, "encoder.int8.onnx")
  fun decoder(context: Context): File = file(context, "decoder.int8.onnx")
  fun joiner(context: Context): File = file(context, "joiner.int8.onnx")
  fun tokens(context: Context): File = file(context, "tokens.txt")

  /** Removes the model and anything half-downloaded, giving the space back. */
  fun delete(context: Context) {
    directory(context).deleteRecursively()
  }

  /**
   * Downloads whatever is still missing, resuming a partial file where the server allows it.
   *
   * @param onProgress Called with the bytes downloaded so far and [downloadSize].
   * @throws IOException if a file cannot be downloaded or does not match its hash.
   */
  @WorkerThread
  @Throws(IOException::class)
  fun download(context: Context, onProgress: (Long, Long) -> Unit) {
    directory(context).mkdirs()

    var completedBytes = files.filter { it.isComplete(context) }.sumOf { it.size }
    onProgress(completedBytes, downloadSize)

    for (modelFile in files.filterNot { it.isComplete(context) }) {
      download(context, modelFile) { fileBytes -> onProgress(completedBytes + fileBytes, downloadSize) }
      completedBytes += modelFile.size
      onProgress(completedBytes, downloadSize)
    }
  }

  private fun download(context: Context, modelFile: ModelFile, onProgress: (Long) -> Unit) {
    val partial = File(directory(context), modelFile.name + PARTIAL_SUFFIX)

    fetch(AppDependencies.okHttpClient, "$BASE_URL/${modelFile.name}", partial, modelFile.sha256, onProgress)

    if (!partial.renameTo(file(context, modelFile.name))) {
      throw IOException("Could not move ${modelFile.name} into place")
    }

    Log.i(TAG, "Installed ${modelFile.name}")
  }

  /** Downloads [url] into [partial], picking up where an earlier attempt stopped, and checks the result against [sha256]. */
  @VisibleForTesting
  @Throws(IOException::class)
  internal fun fetch(client: OkHttpClient, url: String, partial: File, sha256: String, onProgress: (Long) -> Unit) {
    val alreadyHave = if (partial.exists()) partial.length() else 0

    val request = Request.Builder()
      .url(url)
      .apply { if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-") }
      .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("Download of ${partial.name} failed with ${response.code}")
      }

      // A server that ignored our Range header sends the whole file again, so start over rather than append to it.
      val resumed = response.code == 206
      var written = if (resumed) alreadyHave else 0

      partial.sink(append = resumed).buffer().use { sink ->
        val source = response.body.source()
        val chunk = Buffer()

        while (true) {
          val read = source.read(chunk, BUFFER_SIZE)
          if (read == -1L) {
            break
          }

          sink.write(chunk, read)
          written += read
          onProgress(written)
        }
      }
    }

    val hash = partial.sha256()
    if (hash != sha256) {
      partial.delete()
      throw IOException("${partial.name} hashed to $hash, expected $sha256")
    }
  }

  private fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

  private fun file(context: Context, name: String): File = File(directory(context), name)

  private fun ModelFile.isComplete(context: Context): Boolean {
    val file = file(context, name)

    return file.exists() && file.length() == size
  }

  private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")

    inputStream().use { input ->
      val buffer = ByteArray(BUFFER_SIZE.toInt())
      while (true) {
        val read = input.read(buffer)
        if (read == -1) {
          break
        }
        digest.update(buffer, 0, read)
      }
    }

    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  data class ModelFile(val name: String, val size: Long, val sha256: String)
}
