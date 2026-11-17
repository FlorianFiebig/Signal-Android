# Offline voice message transcription — handoff

Branch `feature/offline-voice-message-transcription` on the fork
`git@github.com:FlorianFiebig/Signal-Android.git` (remote `fork`; `origin` is still upstream).
Four commits on top of `origin/main`, 32 files, ~1100 lines added.

## Goal

Two outcomes are acceptable, in this order of preference:

1. **Upstream.** Get this into a shape Signal would merge, open a PR from the fork, and keep the
   feature alive in official builds.
2. **Fork forever.** Maintain it as a self-built app, rebased on each Signal release.

The work so far optimizes for "it works"; nothing has been cut down for a PR yet.

## What exists today

Press and hold a voice message → **Transcribe** → the audio is transcribed on the device and the
transcript appears in italics under the waveform. No network, no protocol change, no automatic
transcription.

| Piece | Where |
| --- | --- |
| Engine, decode and resample | `app/.../audio/VoiceNoteTranscriber.kt` |
| Model install, download, hashes | `app/.../audio/TranscriptionModel.kt` |
| Storage (local-only column) | `AttachmentTable` + `V333_AddAttachmentTranscriptColumn` + `DatabaseAttachment.transcript` |
| Menu entries | `ReactionMenu.canTranscribe`, `ConversationFragment.handleTranscribe`, `ReactionAction.TRANSCRIBE` |
| Display | `ConversationItem.setBodyText` (captionless branch), `MessageRecordUtil.voiceNoteTranscript` |
| Opt-in setting | `SettingsValues.voiceMessageTranscription`, Chats settings screen + `ChatsSettingsRepository/ViewModel` |
| Test build | `sideload` build type, `BenchmarkSetupActivity` setup type `transcription` |

**Engine:** Parakeet TDT 0.6b v3 int8 through sherpa-onnx `v2.0.1` (JitPack, pinned in
`gradle/verification-metadata.xml`). Multilingual, covers German. Audio is decoded with MediaCodec
and resampled to 16 kHz mono, following the loop in `AudioWaveFormGenerator`.

**Model:** four files, 670 MB total, pinned to Hugging Face revision
`ed5d745623472f97cfc2e6c2916ee248798fc5a8`, each checked against a pinned SHA-256 before use.
Downloaded only after the user turns the setting on; deleted when they turn it off.

## Verified

On a OnePlus CPH2797, through the `sideload` build:

- German transcription of a received voice message, near-perfect including "halb 8" and punctuation.
- German transcription of the user's own recordings.
- The 670 MB download, the opt-in toggle, and deletion on opt-out.
- `:Signal-Android:testPlayProdDebugUnitTest` (audio tests + `DatabaseConsistencyTest`, which proves
  the fresh schema matches the migrated one) and `:feature:app-settings:testDebugUnitTest`.
- `./gradlew format` clean.

Not verified: any device other than that one, any language other than German, long recordings,
low-memory devices.

## Open points

**Needed either way**

- **Language.** The model infers the language; there is no setting and no UI hint about which
  languages work. Decide whether that is enough or whether the user picks one.
- **Tests.** Only the resampler, the pinned model table and the settings row are covered. Nothing
  covers `canTranscribe`, the transcript rendering in `ConversationItem`, or the download including
  its resume path. No instrumentation test exists.
- **Failure UX.** A failure is a toast. There is no progress for a long transcription, no cancel.
- **Memory.** The int8 encoder is 652 MB. It ran fine on a flagship; a weak phone may OOM. The
  cheap fallback is a smaller model in the same table in `TranscriptionModel.kt` (int4 encoder
  ~391 MB, or a Canary/Whisper build) — no other code changes.

**Only matters for a fork**

- Signal is signed by Signal; this is debug-signed, so it cannot update an existing install. Going
  self-built means: export a local backup, uninstall, install a release build signed with your own
  keystore (`release` has no signing config today), restore.
- **Do not install official Signal over this data.** The migration takes the database to 333;
  `SignalDatabase` never overrides `onDowngrade`, so the default throws and the official app will
  not open. Upstream's own future V333 will also differ from ours.

**Only matters for upstream**

- Sign the CLA first.
- **Split the PR.** The two fixes to `BenchmarkWebSocketConnection` (`sendRequestSuspend`,
  `runWithChatConnection` both threw `UnsupportedOperationException` and crashed the app from a job
  thread) are real bugs in their benchmark harness and can go in as a small standalone PR.
- **Strip the scaffolding:** the `sideload` build type, its `selectableVariants` entry, the
  `transcription` setup type, the German sample asset, and this file.
- **Expect pushback on:** the JitPack dependency (sherpa-onnx is not on Maven Central; a 47 MB AAR
  of native libs), +25-30 MB per ABI split, a 670 MB model downloaded from Hugging Face (they would
  want their own CDN plus a privacy review, since the request reveals that a user enabled this),
  and a new user-facing setting, which CONTRIBUTING argues against directly.
- Renumber the migration at merge time.
- Product questions for them: do transcripts belong in backups, should they sync to linked devices,
  do iOS and Desktop need parity.

## Running it

JDK 21 and `ANDROID_HOME` are required (`platforms;android-37.0`, `build-tools;36.0.0`,
`ndk;28.0.13004108`, `cmake;3.22.1`).

```sh
./gradlew :Signal-Android:assemblePlayProdSideload
adb install -r app/build/outputs/apk/playProd/sideload/Signal-Android-play-prod-arm64-v8a-sideload-*.apk
adb shell am start -n org.thoughtcrime.securesms.sideload/org.signal.benchmark.BenchmarkSetupActivity -e setup-type transcription
```

The `sideload` build installs as `org.thoughtcrime.securesms.sideload` ("Signal Sideload") next to a
real Signal and stubs out the chat server, so no account is touched. The setup command seeds a chat
holding one received voice message with real audio. Then: Settings → Chats → Transcribe voice
messages → wait for the download → press and hold the voice message.

Messages you send in that build never leave it, so they stay pending; a pending message gets the
selection menu instead of the long press menu, which is why Transcribe is offered in both.
