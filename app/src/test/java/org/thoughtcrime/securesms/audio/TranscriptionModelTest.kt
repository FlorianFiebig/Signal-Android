/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import org.junit.Test

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
  fun `the download size is what the files add up to`() {
    assertThat(TranscriptionModel.downloadSize).isEqualTo(TranscriptionModel.files.sumOf { it.size })
  }
}
