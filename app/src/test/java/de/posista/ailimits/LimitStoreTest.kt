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

    @Test fun recoversPendingResetWhenDatabaseReopens() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "limit-persistence-test.db"
        try {
            Room.databaseBuilder(context, LimitDatabase::class.java, name).build().use { first ->
                LimitStore(first).ingest(listOf(session), Source.SHARED, now)
            }
            Room.databaseBuilder(context, LimitDatabase::class.java, name).build().use { reopened ->
                val saved = reopened.limits().pending().single()
                assertEquals(session.resetAt, saved.resetAt)
                assertEquals(now, saved.observedAt)
                assertEquals(Source.SHARED, saved.source)
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
