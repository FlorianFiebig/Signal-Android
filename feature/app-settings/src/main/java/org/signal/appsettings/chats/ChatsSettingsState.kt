/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.chats

import org.signal.appsettings.backups.BackupCreationProgress

data class ChatsSettingsState(
  val generateLinkPreviews: Boolean,
  val useAddressBook: Boolean,
  val keepMutedChatsArchived: Boolean,
  val useSystemEmoji: Boolean,
  val enterKeySends: Boolean,
  val localBackupsEnabled: Boolean,
  val folderCount: Int,
  val userUnregistered: Boolean,
  val clientDeprecated: Boolean,
  val isPlaintextExportEnabled: Boolean,
  val plaintextExportProgress: BackupCreationProgress = BackupCreationProgress.Idle,
  val chatExportState: ChatExportState = ChatExportState.None,
  val includeMediaInExport: Boolean = false,
  val shouldAutoplayStickersAndGifs: Boolean = false,
  val voiceMessageTranscription: Boolean = false,
  /** Percent of the speech model downloaded, or null when no download is running. */
  val transcriptionModelDownloadPercent: Int? = null,
  val transcriptionModelDownloadFailed: Boolean = false,
  /** How big the one-time speech model download is, in whole megabytes. */
  val transcriptionModelSizeMegabytes: Int = 0
) {
  fun isRegisteredAndUpToDate(): Boolean {
    return !userUnregistered && !clientDeprecated
  }
}
