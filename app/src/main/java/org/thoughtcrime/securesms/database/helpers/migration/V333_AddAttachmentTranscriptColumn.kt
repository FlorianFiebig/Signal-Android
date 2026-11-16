package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase

/** Adds a local-only column holding the on-device transcript of a voice message. */
@Suppress("ClassName")
object V333_AddAttachmentTranscriptColumn : SignalDatabaseMigration {

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("ALTER TABLE attachment ADD COLUMN transcript TEXT DEFAULT NULL")
  }
}
