/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Guards the pinned model table, since a typo there would only show up as a failed download for everyone.
 */
class TranscriptionModelTest {

  @Test
  fun `every file has a sha256 and a positive size`() {
    for (file in TranscriptionModel.files) {
      assertThat(file.sha256.matches(Regex("[0-9a-f]{64}")), "sha256 of ${file.name}").isTrue()
      assertThat(file.size, "size of ${file.name}").isGreaterThan(0L)
    }
  }

  @Test
  fun `the recognizer needs all four model files`() {
    assertThat(TranscriptionModel.files.map { it.name }.toSet()).isEqualTo(
      setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    )
  }

  @Test
  fun `a partial download is resumed and only installed if the hash matches`() {
    val content = "the whole model".toByteArray()
    val sha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    val partial = File.createTempFile("model", ".partial").apply { writeBytes(content.copyOf(4)) }
    val ranges = mutableListOf<String?>()

    // Serves the rest of the file for a Range request, like the real host does.
    val client = OkHttpClient.Builder().addInterceptor { chain ->
      val range = chain.request().header("Range").also { ranges += it }
      val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0

      Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(if (range != null) 206 else 200)
        .message("OK")
        .body(content.copyOfRange(from, content.size).toResponseBody())
        .build()
    }.build()

    try {
      TranscriptionModel.fetch(client, "https://example.com/model", partial, sha256) {}

      assertThat(ranges).isEqualTo(listOf<String?>("bytes=4-"))
      assertThat(partial.readBytes().toList()).isEqualTo(content.toList())

      val failure = runCatching { TranscriptionModel.fetch(client, "https://example.com/model", partial, "0".repeat(64)) {} }

      assertThat(failure.exceptionOrNull() is IOException, "a wrong hash is refused").isTrue()
      assertThat(partial.exists(), "the mismatching file is kept").isEqualTo(false)
    } finally {
      partial.delete()
    }
  }

  @Test
  fun `the download size is what the files add up to`() {
    assertThat(TranscriptionModel.downloadSize).isEqualTo(TranscriptionModel.files.sumOf { it.size })
  }
}
