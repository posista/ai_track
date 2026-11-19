package de.posista.ailimits

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LimitStoreTest {
    private lateinit var db: LimitDatabase
    private lateinit var store: LimitStore
    private val now = Instant.parse("2026-03-27T10:00:00Z")
    private val session = ResetCandidate(Provider.CLAUDE, LimitWindow.SHORT, now.plusSeconds(600), true)
    private val week = ResetCandidate(Provider.CLAUDE, LimitWindow.WEEKLY, now.plusSeconds(86400))

    @Before fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LimitDatabase::class.java).build()
        store = LimitStore(db)
    }

    @After fun closeDatabase() = db.close()

    @Test fun keepsSeparateWindowsAndDeletesJustOne() = runBlocking {
        store.ingest(listOf(session, week), Source.SHARED, now)
        assertEquals(2, store.dao.pending().size)
        assertEquals(session.resetAt, store.dao.get(session.provider, session.window)?.resetAt)
        store.dao.delete(session.provider, session.window)
        assertNull(store.dao.get(session.provider, session.window))
        assertEquals(week.resetAt, store.dao.pending().single().resetAt)
    }

    @Test fun keepsTwoWeeklyScopesAndIgnoresRelativeRepostsUntilTheReset() = runBlocking {
        val general = week.copy(relativeFingerprint = "same-notice", scope = "Alle Modelle")
        val fable = week.copy(resetAt = week.resetAt.plusSeconds(60), scope = "Fable")
        store.ingest(listOf(general, fable), Source.NOTIFICATION, now)
        val repost = general.copy(resetAt = general.resetAt.plusSeconds(30), scope = "ALLE MODELLE")
        assertTrue(store.ingest(listOf(repost), Source.NOTIFICATION, now.plusSeconds(30)).isEmpty())
        assertEquals(general.resetAt, store.dao.get(Provider.CLAUDE, LimitWindow.WEEKLY, "alle modelle")?.resetAt)
        assertEquals(fable.resetAt, store.dao.get(Provider.CLAUDE, LimitWindow.WEEKLY, "fable")?.resetAt)
        val next = general.copy(resetAt = general.resetAt.plusSeconds(86400))
        store.ingest(listOf(next), Source.NOTIFICATION, general.resetAt.plusSeconds(5))
        assertEquals(next.resetAt, store.dao.get(Provider.CLAUDE, LimitWindow.WEEKLY, "alle modelle")?.resetAt)
    }

    @Test fun migratesThePreviewDatabaseAndRetainsDeliveryHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "preview-migration-test.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { legacy ->
                legacy.execSQL("""
                    CREATE TABLE limits (
                        provider TEXT NOT NULL, window TEXT NOT NULL,
                        resetMillis INTEGER NOT NULL, observedMillis INTEGER NOT NULL, source TEXT NOT NULL,
                        reached INTEGER NOT NULL, notified INTEGER NOT NULL,
                        PRIMARY KEY(provider, window)
                    )
                """.trimIndent())
                legacy.execSQL("INSERT INTO limits VALUES ('CLAUDE', 'WEEKLY', ?, ?, 'MANUAL', 1, 1)",
                    arrayOf(week.resetAt.toEpochMilli(), now.toEpochMilli()))
                legacy.version = 1
            }
            val upgraded = Room.databaseBuilder(context, LimitDatabase::class.java, name)
                .addMigrations(migrateLimits1To2).build()
            try {
                val saved = upgraded.limits().get(Provider.CLAUDE, LimitWindow.WEEKLY)!!
                assertEquals(week.resetAt, saved.resetAt)
                assertEquals(now, saved.observedAt)
                assertEquals(Source.MANUAL, saved.source)
                assertEquals("", saved.scope)
                assertTrue(saved.reached)
                assertTrue(saved.notified)
                assertTrue(upgraded.limits().pending().isEmpty())
            } finally {
                upgraded.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun recoversPendingResetWhenDatabaseReopens() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "limit-persistence-test.db"
        try {
            val first = Room.databaseBuilder(context, LimitDatabase::class.java, name).build()
            try {
                LimitStore(first).ingest(listOf(session), Source.SHARED, now)
            } finally {
                first.close()
            }
            val reopened = Room.databaseBuilder(context, LimitDatabase::class.java, name).build()
            try {
                val saved = reopened.limits().pending().single()
                assertEquals(session.resetAt, saved.resetAt)
                assertEquals(now, saved.observedAt)
                assertEquals(Source.SHARED, saved.source)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun rejectsOlderUpdatesAndPreservesDeliveryForSameReset() = runBlocking {
        val record = store.ingest(listOf(session), Source.NOTIFICATION, now).single()
        store.dao.save(record.copy(notified = true))
        val duplicate = store.ingest(listOf(session.copy(reached = false)), Source.SHARED, now.plusSeconds(1)).single()
        assertTrue(duplicate.notified)
        assertTrue(duplicate.reached)
        assertTrue(store.ingest(listOf(session.copy(resetAt = week.resetAt)), Source.NOTIFICATION, now).isEmpty())
        assertTrue(store.dao.pending().isEmpty())
        val updated = store.ingest(listOf(session.copy(resetAt = week.resetAt, reached = false)),
            Source.MANUAL, now.plusSeconds(2)).single()
        assertFalse(updated.notified)
        assertFalse(updated.reached)
        assertEquals(week.resetAt, store.dao.pending().single().resetAt)
    }
}
