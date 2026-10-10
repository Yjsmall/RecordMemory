package dev.local.record.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RecordDatabase::class.java)

    @Test fun structuredUpgradeKeepsUnknownFieldsUnknownAndPreservesTombstones() {
        val name = "structured-memory-migration-4-5"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
        helper.createDatabase(name, 4).use { db ->
            db.execSQL("INSERT INTO memories VALUES ('m', 2, 'PREFERENCE', 'CONFIRMED', '旧偏好', '依据', 'body', '', 'source', '', 'c', 't')")
            db.execSQL("INSERT INTO memories VALUES ('f', 3, 'PREFERENCE', 'FORGOTTEN', '', '', 'gone', '', 'source', 'hash', 'c', 't')")
        }
        helper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).use { db ->
            db.query("SELECT text, factJson, changeJson, suppressionKey FROM memories WHERE id = 'm'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("旧偏好", cursor.getString(0))
                org.junit.Assert.assertTrue(cursor.isNull(1))
                org.junit.Assert.assertTrue(cursor.isNull(2))
                assertEquals("", cursor.getString(3))
            }
            db.query("SELECT status, fingerprint FROM memories WHERE id = 'f'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("FORGOTTEN", cursor.getString(0))
                assertEquals("hash", cursor.getString(1))
            }
        }
    }

    @Test fun versionTwoMemoriesAndContentSurviveUpgrade() {
        val name = "conversation-migration-2-3"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO memories VALUES ('m', 2, 'PREFERENCE', 'CONFIRMED', '旧偏好', '旧依据', 'body', 'recording', 'transcript', '')")
            db.execSQL("INSERT INTO contents VALUES ('body', 'memory', 'historic-body', 1)")
        }
        helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT text, sourceConversationId, sourceTurnId FROM memories WHERE id = 'm'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("旧偏好", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals("", cursor.getString(2))
            }
            db.query("SELECT body FROM contents WHERE id = 'body'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("historic-body", cursor.getString(0))
            }
        }
    }

    @Test fun initialRecordingDatabaseCanUpgradeThroughBothMigrations() {
        val name = "conversation-migration-1-3"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
        helper.createDatabase(name, 1).close()
        helper.runMigrationsAndValidate(name, 3, true, MIGRATION_1_2, MIGRATION_2_3).close()
    }

    @Test fun memoryPlannerUpgradePreservesForgottenTombstonesAndConversations() {
        val name = "memory-planner-migration-3-4"
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
        helper.createDatabase(name, 3).use { db ->
            db.execSQL("INSERT INTO memories VALUES ('forgotten', 3, 'PREFERENCE', 'FORGOTTEN', '', '', 'deleted-body', '', 'source', 'tombstone', 'c', 't')")
            db.execSQL("INSERT INTO conversations VALUES ('c', 1, 100, 0)")
        }
        helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT status, fingerprint, sourceTurnId FROM memories WHERE id = 'forgotten'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("FORGOTTEN", cursor.getString(0))
                assertEquals("tombstone", cursor.getString(1))
                assertEquals("t", cursor.getString(2))
            }
            db.query("SELECT COUNT(*) FROM memory_planning").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM conversations").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }
}
