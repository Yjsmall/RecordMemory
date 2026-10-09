package dev.local.record.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds AI content, text, jobs, outbox and memories without touching recording history. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `contents` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recording_text` (`recordingId` TEXT NOT NULL, `version` INTEGER NOT NULL, `title` TEXT, `titleContentId` TEXT, `titleOrigin` TEXT NOT NULL, `titleSuggestion` TEXT, `titleSuggestionContentId` TEXT, `transcript` TEXT, `transcriptContentId` TEXT, `transcriptOrigin` TEXT NOT NULL, `summary` TEXT, `summaryContentId` TEXT, `summaryOrigin` TEXT NOT NULL, `summarySuggestion` TEXT, `summarySuggestionContentId` TEXT, `stale` INTEGER NOT NULL, PRIMARY KEY(`recordingId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_jobs` (`id` TEXT NOT NULL, `version` INTEGER NOT NULL, `recordingId` TEXT NOT NULL, `capability` TEXT NOT NULL, `status` TEXT NOT NULL, `generation` INTEGER NOT NULL, `attempt` INTEGER NOT NULL, `sourceContentId` TEXT, `resultContentId` TEXT, `error` TEXT, `leaseUntil` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `outbox` (`jobId` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`jobId`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memories` (`id` TEXT NOT NULL, `version` INTEGER NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, `text` TEXT NOT NULL, `evidence` TEXT NOT NULL, `contentId` TEXT NOT NULL, `sourceRecordingId` TEXT NOT NULL, `sourceContentId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}
