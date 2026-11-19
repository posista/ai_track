package de.posista.ailimits

import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = LimitApplication::class, sdk = [34])
class ReminderTest {
    private val app get() = RuntimeEnvironment.getApplication() as LimitApplication
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Test fun permissionAndDeliveryStatePreventMissedOrDuplicateReminders() = runBlocking {
        val now = Instant.now()
        val expires = now.plusSeconds(600)
        val record = app.store.ingest(listOf(
            ResetCandidate(Provider.CLAUDE, LimitWindow.WEEKLY, expires, true, "Fable")
        ), Source.SHARED, now).single()
        app.notifyDue(now)
        assertTrue(manager.activeNotifications.isEmpty())

        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(app.showTestNotification())
        app.notifyDue(expires)
        assertEquals(record.key, app.store.dao.pending().single().key)

        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(app.showTestNotification())
        app.notifyDue(expires)
        assertTrue(app.store.dao.pending().isEmpty())
        assertEquals(setOf("test-reminder", record.key), manager.activeNotifications.map { it.tag }.toSet())

        manager.cancelAll()
        app.notifyDue(expires.plusSeconds(10))
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
