package com.wearos.ancsbridge.health

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Room
import android.content.Context

/**
 * Buffered health records awaiting delivery to the iPhone companion.
 * Nothing is deleted until the iOS side ACKs the record id (cumulative).
 */
@Entity(tableName = "health_records")
data class HealthRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "record_id")
    val recordId: Long = 0,

    /** One of HealthTlv.TYPE_* */
    @ColumnInfo(name = "type")
    val type: Int,

    @ColumnInfo(name = "start_epoch_ms")
    val startEpochMs: Long,

    @ColumnInfo(name = "end_epoch_ms")
    val endEpochMs: Long,

    /** Primary numeric value (bpm, step count, battery %) when the type has one. */
    @ColumnInfo(name = "value")
    val value: Double? = null,

    /** Type-specific extras as JSON (sleep stage, workout fields, HR samples). */
    @ColumnInfo(name = "meta_json")
    val metaJson: String? = null,

    /** Epoch ms when the record was last written to …B6; NULL = unsent. */
    @ColumnInfo(name = "sent_at")
    val sentAt: Long? = null
)

@Dao
interface HealthDao {

    @Insert
    suspend fun insert(record: HealthRecordEntity): Long

    @Insert
    suspend fun insertAll(records: List<HealthRecordEntity>)

    @Query("SELECT * FROM health_records WHERE sent_at IS NULL ORDER BY record_id ASC LIMIT :limit")
    suspend fun oldestUnsent(limit: Int): List<HealthRecordEntity>

    @Query("SELECT MIN(record_id) FROM health_records WHERE sent_at IS NULL")
    suspend fun oldestUnsentId(): Long?

    @Query("UPDATE health_records SET sent_at = :timestampMs WHERE record_id IN (:ids)")
    suspend fun markSent(ids: List<Long>, timestampMs: Long)

    @Query("UPDATE health_records SET sent_at = NULL WHERE record_id IN (:ids)")
    suspend fun clearSentFor(ids: List<Long>)

    @Query("UPDATE health_records SET sent_at = NULL")
    suspend fun clearAllSent()

    @Query("DELETE FROM health_records WHERE record_id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM health_records WHERE sent_at IS NULL")
    suspend fun unsentCount(): Int

    @Query("DELETE FROM health_records")
    suspend fun deleteAll()
}

/**
 * Health Connect record ids already imported into health_records — prevents
 * duplicate Room rows when the same SleepSession/ExerciseSession is read twice.
 */
@Entity(tableName = "seen_hc_records")
data class SeenHcRecord(
    @PrimaryKey
    @ColumnInfo(name = "hc_record_id")
    val hcRecordId: String
)

@Dao
interface SeenHcDao {

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<SeenHcRecord>)

    @Query("SELECT hc_record_id FROM seen_hc_records")
    suspend fun allIds(): List<String>
}

@Database(entities = [HealthRecordEntity::class, SeenHcRecord::class], version = 1, exportSchema = false)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao
    abstract fun seenHcDao(): SeenHcDao

    companion object {
        @Volatile
        private var instance: HealthDatabase? = null

        fun get(context: Context): HealthDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HealthDatabase::class.java,
                    "health_bridge.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
