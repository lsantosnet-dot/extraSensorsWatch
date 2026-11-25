package net.extrasensors.phone.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sun_bucket")
data class SunBucketRow(
    @PrimaryKey val bucketStart: Long,
    val exposedMs: Long,
    val doseSed: Double,
    val maxUv: Double?,
)

@Entity(tableName = "sun_day")
data class SunDayRow(
    @PrimaryKey val epochDay: Long,
    val totalMs: Long,
)

@Entity(tableName = "skin_reading")
data class SkinRow(
    @PrimaryKey val timeMillis: Long,
    val celsius: Double,
)

@Entity(tableName = "sunscreen")
data class SunscreenRow(
    @PrimaryKey val timeMillis: Long,
)

@Entity(tableName = "uv_hour")
data class UvRow(
    @PrimaryKey val hourStart: Long,
    val uvi: Double,
)

@Dao
interface PhoneDao {
    @Upsert suspend fun upsertBuckets(rows: List<SunBucketRow>)
    @Upsert suspend fun upsertDays(rows: List<SunDayRow>)
    @Upsert suspend fun upsertSkin(rows: List<SkinRow>)
    @Upsert suspend fun upsertSunscreen(rows: List<SunscreenRow>)
    @Upsert suspend fun upsertUv(rows: List<UvRow>)

    @Query("SELECT * FROM sun_bucket WHERE bucketStart >= :from AND bucketStart < :to ORDER BY bucketStart")
    fun buckets(from: Long, to: Long): Flow<List<SunBucketRow>>

    @Query("SELECT * FROM sun_day WHERE epochDay >= :fromDay AND epochDay <= :toDay ORDER BY epochDay")
    fun days(fromDay: Long, toDay: Long): Flow<List<SunDayRow>>

    @Query("SELECT * FROM skin_reading WHERE timeMillis >= :from AND timeMillis < :to ORDER BY timeMillis")
    fun skin(from: Long, to: Long): Flow<List<SkinRow>>

    @Query("SELECT * FROM sunscreen WHERE timeMillis >= :from AND timeMillis < :to ORDER BY timeMillis")
    fun sunscreen(from: Long, to: Long): Flow<List<SunscreenRow>>

    @Query("SELECT * FROM uv_hour WHERE hourStart >= :from AND hourStart < :to ORDER BY hourStart")
    fun uv(from: Long, to: Long): Flow<List<UvRow>>

    @Query("SELECT * FROM sun_bucket ORDER BY bucketStart")
    suspend fun allBuckets(): List<SunBucketRow>

    @Query("SELECT * FROM sun_day ORDER BY epochDay")
    suspend fun allDays(): List<SunDayRow>

    @Query("SELECT * FROM skin_reading ORDER BY timeMillis")
    suspend fun allSkin(): List<SkinRow>

    @Query("SELECT * FROM sunscreen ORDER BY timeMillis")
    suspend fun allSunscreen(): List<SunscreenRow>

    @Query("SELECT * FROM uv_hour WHERE hourStart >= :from ORDER BY hourStart")
    suspend fun uvSince(from: Long): List<UvRow>
}

@Database(
    entities = [SunBucketRow::class, SunDayRow::class, SkinRow::class, SunscreenRow::class, UvRow::class],
    version = 1,
    exportSchema = false,
)
abstract class PhoneDatabase : RoomDatabase() {
    abstract fun dao(): PhoneDao

    companion object {
        fun create(context: Context): PhoneDatabase =
            Room.databaseBuilder(context, PhoneDatabase::class.java, "phone.db").build()
    }
}
