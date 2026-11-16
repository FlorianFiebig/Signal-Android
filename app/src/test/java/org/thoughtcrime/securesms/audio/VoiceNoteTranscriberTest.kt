/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.thoughtcrime.securesms.audio.VoiceNoteTranscriber.Resampler

class VoiceNoteTranscriberTest {

  @Test
  fun `audio already at the output rate is passed through`() {
    val resampler = Resampler(inputSampleRate = 16_000, channelCount = 1)
    val samples = ShortArray(160) { (it * 10).toShort() }

    assertThat(resampler.resample(samples, samples.size).toList()).isEqualTo(samples.toList())
  }

  @Test
  fun `downsampling averages the frames of each output sample`() {
    val resampler = Resampler(inputSampleRate = 32_000, channelCount = 1)
    val samples = shortArrayOf(100, 200, 300, 400)

    assertThat(resampler.resample(samples, samples.size).toList()).isEqualTo(listOf<Short>(150, 350))
  }

  @Test
  fun `upsampling repeats the last sample`() {
    val resampler = Resampler(inputSampleRate = 8_000, channelCount = 1)
    val samples = shortArrayOf(100, 200)

    assertThat(resampler.resample(samples, samples.size).toList()).isEqualTo(listOf<Short>(100, 100, 200, 200))
  }

  @Test
  fun `stereo frames are mixed down to mono`() {
    val resampler = Resampler(inputSampleRate = 16_000, channelCount = 2)
    val samples = shortArrayOf(100, 300, 500, 700)

    assertThat(resampler.resample(samples, samples.size).toList()).isEqualTo(listOf<Short>(200, 600))
  }

  @Test
  fun `splitting the audio across buffers gives the same output`() {
    val samples = ShortArray(441) { (it * 7).toShort() }

    val whole = Resampler(inputSampleRate = 44_100, channelCount = 1).let {
      it.resample(samples, samples.size).toList() + it.drain().toList()
    }

    val split = Resampler(inputSampleRate = 44_100, channelCount = 1).let {
      it.resample(samples, 100).toList() +
        it.resample(samples.copyOfRange(100, samples.size), samples.size - 100).toList() +
        it.drain().toList()
    }

    assertThat(split).isEqualTo(whole)
  }

  @Test
  fun `a partial output sample is flushed on drain`() {
    val resampler = Resampler(inputSampleRate = 32_000, channelCount = 1)

    assertThat(resampler.resample(shortArrayOf(100, 200, 300), 3).toList()).isEqualTo(listOf<Short>(150))
    assertThat(resampler.drain().toList()).isEqualTo(listOf<Short>(300))
  }

  @Test
  fun `drain produces nothing when every sample was emitted`() {
    val resampler = Resampler(inputSampleRate = 16_000, channelCount = 1)

    resampler.resample(shortArrayOf(100, 200), 2)

    assertThat(resampler.drain().toList()).isEqualTo(emptyList<Short>())
  }
}
