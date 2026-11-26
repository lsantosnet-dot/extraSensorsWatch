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

@Entity(tableName = "hrv")
data class HrvRow(
    @PrimaryKey val timeMillis: Long,
    val rmssdMs: Double,
    val meanHr: Double,
    val beats: Int,
    val durationMs: Long,
)

@Entity(tableName = "eda_session")
data class EdaSessionRow(
    @PrimaryKey val startedAt: Long,
    val endedAt: Long?,
    val source: Int,
)

@Entity(tableName = "eda_point", indices = [androidx.room.Index("sessionStart")])
data class EdaPointRow(
    @PrimaryKey val timeMillis: Long,
    val sessionStart: Long,
    val mean: Double,
    val min: Double,
    val max: Double,
    val peaks: Int,
)

@Dao
interface PhoneDao {
    @Upsert suspend fun upsertBuckets(rows: List<SunBucketRow>)
    @Upsert suspend fun upsertDays(rows: List<SunDayRow>)
    @Upsert suspend fun upsertSkin(rows: List<SkinRow>)
    @Upsert suspend fun upsertSunscreen(rows: List<SunscreenRow>)
    @Upsert suspend fun upsertUv(rows: List<UvRow>)
    @Upsert suspend fun upsertHrv(rows: List<HrvRow>)
    @Upsert suspend fun upsertEdaSessions(rows: List<EdaSessionRow>)
    @Upsert suspend fun upsertEdaPoints(rows: List<EdaPointRow>)

    @Query("SELECT * FROM hrv WHERE timeMillis >= :from AND timeMillis < :to ORDER BY timeMillis")
    fun hrv(from: Long, to: Long): Flow<List<HrvRow>>

    @Query("SELECT * FROM eda_session WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt DESC")
    fun edaSessions(from: Long, to: Long): Flow<List<EdaSessionRow>>

    @Query("SELECT * FROM eda_point WHERE sessionStart >= :from AND sessionStart < :to ORDER BY timeMillis")
    fun edaPointsForSessions(from: Long, to: Long): Flow<List<EdaPointRow>>

    @Query("SELECT * FROM hrv ORDER BY timeMillis")
    suspend fun allHrv(): List<HrvRow>

    @Query("SELECT * FROM eda_session ORDER BY startedAt")
    suspend fun allEdaSessions(): List<EdaSessionRow>

    @Query("SELECT * FROM eda_point ORDER BY timeMillis")
    suspend fun allEdaPoints(): List<EdaPointRow>

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
    entities = [
        SunBucketRow::class, SunDayRow::class, SkinRow::class, SunscreenRow::class, UvRow::class,
        HrvRow::class, EdaSessionRow::class, EdaPointRow::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class PhoneDatabase : RoomDatabase() {
    abstract fun dao(): PhoneDao

    companion object {
        /** v1 → v2: HRV e EDA. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hrv` (`timeMillis` INTEGER NOT NULL, `rmssdMs` REAL NOT NULL, " +
                        "`meanHr` REAL NOT NULL, `beats` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`timeMillis`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `eda_session` (`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, " +
                        "`source` INTEGER NOT NULL, PRIMARY KEY(`startedAt`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `eda_point` (`timeMillis` INTEGER NOT NULL, `sessionStart` INTEGER NOT NULL, " +
                        "`mean` REAL NOT NULL, `min` REAL NOT NULL, `max` REAL NOT NULL, `peaks` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`timeMillis`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_eda_point_sessionStart` ON `eda_point` (`sessionStart`)")
            }
        }

        fun create(context: Context, name: String = "phone.db"): PhoneDatabase =
            Room.databaseBuilder(context, PhoneDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
    }
}
