package net.extrasensors.watch.data

import android.content.Context
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
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pressure(): PressureDao
    abstract fun sun(): SunDao
    abstract fun climb(): ClimbDao
    abstract fun skin(): SkinTempDao
    abstract fun sweat(): SweatDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sensors.db").build()
    }
}
