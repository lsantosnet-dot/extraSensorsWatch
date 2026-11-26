package net.extrasensors.watch.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "pressure_sample", indices = [Index("timeMillis")])
data class PressureSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val hPa: Double,
)

@Entity(tableName = "sun_day")
data class SunDayEntity(
    @PrimaryKey val epochDay: Long,
    val totalMs: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** Bloco de 5 min de exposição solar (para os gráficos do celular). */
@Entity(tableName = "sun_bucket")
data class SunBucketEntity(
    @PrimaryKey val bucketStart: Long,
    val exposedMs: Long,
    val doseSed: Double,
    val maxUv: Double?,
    val updatedAt: Long,
)

/** Medição de HRV de 1 min. */
@Entity(tableName = "hrv_measurement")
data class HrvEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val rmssdMs: Double,
    val meanHr: Double,
    val beats: Int,
    val durationMs: Long,
)

/** Sessão de atividade eletrodérmica. source: 0 = manual, 1 = corrida (tela Suor). */
@Entity(tableName = "eda_session")
data class EdaSessionEntity(
    @PrimaryKey val startedAt: Long,
    val endedAt: Long?,
    val source: Int,
    val updatedAt: Long,
)

/** Janela de 10 s de EDA. */
@Entity(tableName = "eda_point")
data class EdaPointEntity(
    @PrimaryKey val timeMillis: Long,
    val sessionStart: Long,
    val mean: Double,
    val min: Double,
    val max: Double,
    val peaks: Int,
)

/** Previsão horária do índice UV recebida do celular. */
@Entity(tableName = "uv_hour")
data class UvHourEntity(
    @PrimaryKey val hourStart: Long,
    val uvi: Double,
)

@Entity(tableName = "sun_application")
data class SunApplicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
)

@Entity(tableName = "climb_session")
data class ClimbSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val gain: Double = 0.0,
    val loss: Double = 0.0,
    val minAltitude: Double? = null,
    val maxAltitude: Double? = null,
)

@Entity(tableName = "climb_point", indices = [Index("sessionId")])
data class ClimbPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMillis: Long,
    val altitude: Double,
)

@Entity(tableName = "skin_temp_reading", indices = [Index("timeMillis")])
data class SkinTempEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val celsius: Double,
)

@Entity(tableName = "sweat_result")
data class SweatResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val liters: Double,
    val durationMs: Long,
)

@Dao
interface PressureDao {
    @Insert
    suspend fun insert(sample: PressureSampleEntity)

    @Query("SELECT * FROM pressure_sample WHERE timeMillis >= :since ORDER BY timeMillis")
    fun observeSince(since: Long): Flow<List<PressureSampleEntity>>

    @Query("SELECT * FROM pressure_sample WHERE timeMillis >= :since ORDER BY timeMillis")
    suspend fun since(since: Long): List<PressureSampleEntity>

    @Query("SELECT * FROM pressure_sample ORDER BY timeMillis DESC LIMIT 1")
    fun observeLatest(): Flow<PressureSampleEntity?>

    @Query("SELECT * FROM pressure_sample ORDER BY timeMillis DESC LIMIT 1")
    suspend fun latest(): PressureSampleEntity?

    @Query("DELETE FROM pressure_sample WHERE timeMillis < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface SunDao {
    @Upsert
    suspend fun upsertDay(day: SunDayEntity)

    @Insert
    suspend fun insertApplication(a: SunApplicationEntity)

    @Query("DELETE FROM sun_day WHERE epochDay < :beforeDay")
    suspend fun deleteDaysBefore(beforeDay: Long)

    @Query("SELECT * FROM sun_bucket WHERE bucketStart = :start")
    suspend fun bucket(start: Long): SunBucketEntity?

    @Upsert
    suspend fun upsertBucket(b: SunBucketEntity)

    @Query("DELETE FROM sun_bucket WHERE bucketStart < :before")
    suspend fun deleteBucketsBefore(before: Long)

    @Query("SELECT * FROM sun_bucket WHERE updatedAt > :after ORDER BY updatedAt LIMIT :limit")
    suspend fun bucketsUpdatedAfter(after: Long, limit: Int): List<SunBucketEntity>

    @Query("SELECT * FROM sun_day WHERE updatedAt > :after ORDER BY updatedAt LIMIT :limit")
    suspend fun daysUpdatedAfter(after: Long, limit: Int): List<SunDayEntity>

    @Query("SELECT * FROM sun_application WHERE timeMillis > :after ORDER BY timeMillis LIMIT :limit")
    suspend fun applicationsAfter(after: Long, limit: Int): List<SunApplicationEntity>

    @Query("SELECT * FROM sun_bucket WHERE bucketStart >= :since ORDER BY bucketStart")
    suspend fun bucketsSince(since: Long): List<SunBucketEntity>

    @Query("DELETE FROM sun_application WHERE timeMillis < :before")
    suspend fun deleteApplicationsBefore(before: Long)
}

@Dao
interface ClimbDao {
    @Insert
    suspend fun insertSession(s: ClimbSessionEntity): Long

    @Upsert
    suspend fun upsertSession(s: ClimbSessionEntity)

    @Insert
    suspend fun insertPoint(p: ClimbPointEntity)

    @Query("SELECT * FROM climb_session ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestSession(): Flow<ClimbSessionEntity?>

    @Query("SELECT * FROM climb_session ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestSession(): ClimbSessionEntity?

    @Query("SELECT * FROM climb_point WHERE sessionId = :sessionId ORDER BY timeMillis")
    fun observePoints(sessionId: Long): Flow<List<ClimbPointEntity>>

    @Query("DELETE FROM climb_point WHERE sessionId != :keepSessionId")
    suspend fun deletePointsExcept(keepSessionId: Long)
}

@Dao
interface SkinTempDao {
    @Insert
    suspend fun insert(r: SkinTempEntity)

    @Query("SELECT * FROM skin_temp_reading WHERE timeMillis >= :since ORDER BY timeMillis")
    fun observeSince(since: Long): Flow<List<SkinTempEntity>>

    @Query("SELECT * FROM skin_temp_reading ORDER BY timeMillis DESC LIMIT 1")
    fun observeLatest(): Flow<SkinTempEntity?>

    @Query("SELECT * FROM skin_temp_reading ORDER BY timeMillis DESC LIMIT 1")
    suspend fun latest(): SkinTempEntity?

    @Query("DELETE FROM skin_temp_reading WHERE timeMillis < :before")
    suspend fun deleteBefore(before: Long)

    @Query("SELECT * FROM skin_temp_reading WHERE timeMillis > :after ORDER BY timeMillis LIMIT :limit")
    suspend fun after(after: Long, limit: Int): List<SkinTempEntity>
}

@Dao
interface StressDao {
    @Insert
    suspend fun insertHrv(h: HrvEntity)

    @Query("SELECT * FROM hrv_measurement WHERE timeMillis > :after ORDER BY timeMillis LIMIT :limit")
    suspend fun hrvAfter(after: Long, limit: Int): List<HrvEntity>

    @Query("SELECT * FROM hrv_measurement WHERE timeMillis >= :since ORDER BY timeMillis")
    fun observeHrvSince(since: Long): Flow<List<HrvEntity>>

    @Query("SELECT * FROM hrv_measurement WHERE timeMillis >= :since ORDER BY timeMillis")
    suspend fun hrvSince(since: Long): List<HrvEntity>

    @Query("DELETE FROM hrv_measurement WHERE timeMillis < :before")
    suspend fun deleteHrvBefore(before: Long)

    @Upsert
    suspend fun upsertSession(s: EdaSessionEntity)

    @Query("SELECT * FROM eda_session WHERE updatedAt > :after ORDER BY updatedAt LIMIT :limit")
    suspend fun sessionsUpdatedAfter(after: Long, limit: Int): List<EdaSessionEntity>

    @Query("SELECT * FROM eda_session ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestSession(): Flow<EdaSessionEntity?>

    @Query("SELECT * FROM eda_session WHERE startedAt = :start")
    suspend fun session(start: Long): EdaSessionEntity?

    @Upsert
    suspend fun upsertPoint(p: EdaPointEntity)

    @Query("SELECT * FROM eda_point WHERE timeMillis > :after ORDER BY timeMillis LIMIT :limit")
    suspend fun pointsAfter(after: Long, limit: Int): List<EdaPointEntity>

    @Query("SELECT * FROM eda_point WHERE sessionStart = :start ORDER BY timeMillis")
    fun observePoints(start: Long): Flow<List<EdaPointEntity>>

    @Query("DELETE FROM eda_point WHERE timeMillis < :before")
    suspend fun deletePointsBefore(before: Long)

    @Query("DELETE FROM eda_session WHERE startedAt < :before")
    suspend fun deleteSessionsBefore(before: Long)
}

@Dao
interface UvDao {
    @Upsert
    suspend fun upsert(hours: List<UvHourEntity>)

    @Query("SELECT * FROM uv_hour WHERE hourStart BETWEEN :from AND :to ORDER BY hourStart")
    suspend fun between(from: Long, to: Long): List<UvHourEntity>

    @Query("SELECT MAX(hourStart) FROM uv_hour")
    suspend fun lastHour(): Long?

    @Query("DELETE FROM uv_hour WHERE hourStart < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface SweatDao {
    @Insert
    suspend fun insert(r: SweatResultEntity)

    @Query("SELECT * FROM sweat_result ORDER BY timeMillis DESC LIMIT 1")
    fun observeLatest(): Flow<SweatResultEntity?>
}

@Database(
    entities = [
        PressureSampleEntity::class,
        SunDayEntity::class,
        SunApplicationEntity::class,
        ClimbSessionEntity::class,
        ClimbPointEntity::class,
        SkinTempEntity::class,
        SweatResultEntity::class,
        SunBucketEntity::class,
        UvHourEntity::class,
        HrvEntity::class,
        EdaSessionEntity::class,
        EdaPointEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pressure(): PressureDao
    abstract fun sun(): SunDao
    abstract fun climb(): ClimbDao
    abstract fun skin(): SkinTempDao
    abstract fun sweat(): SweatDao
    abstract fun uv(): UvDao
    abstract fun stress(): StressDao

    companion object {
        /** v1 → v2: blocos de sol, previsão de UV e updatedAt nos totais diários. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sun_bucket` (`bucketStart` INTEGER NOT NULL, `exposedMs` INTEGER NOT NULL, " +
                        "`doseSed` REAL NOT NULL, `maxUv` REAL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bucketStart`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `uv_hour` (`hourStart` INTEGER NOT NULL, `uvi` REAL NOT NULL, PRIMARY KEY(`hourStart`))"
                )
                db.execSQL("ALTER TABLE `sun_day` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                // Faz os totais antigos serem enviados ao celular na primeira sincronização.
                db.execSQL("UPDATE `sun_day` SET `updatedAt` = 1")
            }
        }

        /** v2 → v3: HRV e sessões de EDA. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hrv_measurement` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timeMillis` INTEGER NOT NULL, `rmssdMs` REAL NOT NULL, `meanHr` REAL NOT NULL, " +
                        "`beats` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `eda_session` (`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, " +
                        "`source` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`startedAt`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `eda_point` (`timeMillis` INTEGER NOT NULL, `sessionStart` INTEGER NOT NULL, " +
                        "`mean` REAL NOT NULL, `min` REAL NOT NULL, `max` REAL NOT NULL, `peaks` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`timeMillis`))"
                )
            }
        }

        fun create(context: Context, name: String = "sensors.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
