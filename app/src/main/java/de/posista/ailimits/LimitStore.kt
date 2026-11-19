package de.posista.ailimits

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

@Entity(tableName = "limits", primaryKeys = ["provider", "window"])
data class LimitRecord(
    val provider: Provider,
    val window: LimitWindow,
    val resetMillis: Long,
    val observedMillis: Long,
    val source: Source,
    val reached: Boolean,
    val notified: Boolean = false
) {
    val resetAt: Instant get() = Instant.ofEpochMilli(resetMillis)
    val observedAt: Instant get() = Instant.ofEpochMilli(observedMillis)
    val key: String get() = "${provider.name}-${window.name}"
}

@Dao
interface LimitDao {
    @Query("SELECT * FROM limits")
    fun observeAll(): Flow<List<LimitRecord>>

    @Query("SELECT * FROM limits WHERE notified = 0")
    suspend fun pending(): List<LimitRecord>

    @Query("SELECT * FROM limits WHERE provider = :provider AND window = :window")
    suspend fun get(provider: Provider, window: LimitWindow): LimitRecord?

    @Upsert
    suspend fun save(record: LimitRecord)

    @Query("DELETE FROM limits WHERE provider = :provider AND window = :window")
    suspend fun delete(provider: Provider, window: LimitWindow)
}

@Database(entities = [LimitRecord::class], version = 1)
abstract class LimitDatabase : RoomDatabase() {
    abstract fun limits(): LimitDao
}

class LimitStore(database: LimitDatabase) {
    private val db = database
    val dao = db.limits()

    suspend fun ingest(
        candidates: List<ResetCandidate>, source: Source, observedAt: Instant
    ): List<LimitRecord> = db.withTransaction {
        candidates.mapNotNull { candidate ->
            val old = dao.get(candidate.provider, candidate.window)
            val resetMillis = candidate.resetAt.toEpochMilli()
            val observedMillis = observedAt.toEpochMilli()
            if (old != null && (old.observedMillis > observedMillis ||
                    old.observedMillis == observedMillis && source == Source.NOTIFICATION)) {
                return@mapNotNull null
            }
            val sameReset = old?.resetMillis == resetMillis
            val record = LimitRecord(
                candidate.provider, candidate.window, resetMillis, observedMillis, source,
                candidate.reached || sameReset && old?.reached == true,
                sameReset && old?.notified == true
            )
            dao.save(record)
            record
        }
    }
}

fun remainingTime(resetAt: Instant, now: Instant): String {
    if (resetAt <= now) return "Fällig"
    val minutes = Duration.between(now, resetAt).toMinutes()
    if (minutes == 0L) return "< 1 Min."
    return buildList {
        val days = minutes / 1440
        val hours = minutes % 1440 / 60
        if (days > 0) add("$days T.")
        if (hours > 0) add("$hours Std.")
        if (minutes % 60 > 0) add("${minutes % 60} Min.")
    }.joinToString(" ")
}
