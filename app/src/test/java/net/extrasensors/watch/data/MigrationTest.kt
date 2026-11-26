package net.extrasensors.watch.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Garante que os dados de quem já tem a versão 1 instalada sobrevivem à atualização. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {

    @Test
    fun `migra v1 ate v3 preservando dados`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        // Esquema exato gerado pelo Room na versão 1.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `pressure_sample` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMillis` INTEGER NOT NULL, `hPa` REAL NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `sun_day` (`epochDay` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
                "CREATE TABLE IF NOT EXISTS `sun_application` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMillis` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `climb_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `gain` REAL NOT NULL, `loss` REAL NOT NULL, `minAltitude` REAL, `maxAltitude` REAL)",
                "CREATE TABLE IF NOT EXISTS `climb_point` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `timeMillis` INTEGER NOT NULL, `altitude` REAL NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `skin_temp_reading` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMillis` INTEGER NOT NULL, `celsius` REAL NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `sweat_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMillis` INTEGER NOT NULL, `liters` REAL NOT NULL, `durationMs` INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS `index_pressure_sample_timeMillis` ON `pressure_sample` (`timeMillis`)",
                "CREATE INDEX IF NOT EXISTS `index_climb_point_sessionId` ON `climb_point` (`sessionId`)",
                "CREATE INDEX IF NOT EXISTS `index_skin_temp_reading_timeMillis` ON `skin_temp_reading` (`timeMillis`)",
                "INSERT INTO sun_day VALUES (20500, 3600000)",
                "INSERT INTO skin_temp_reading (timeMillis, celsius) VALUES (1000, 33.8)",
            ).forEach(db::execSQL)
            db.version = 1
        }
        val room = AppDatabase.create(ctx, name)
        runBlocking {
            val days = room.sun().daysUpdatedAfter(0, 10)
            assertEquals(1, days.size)
            assertEquals(3_600_000L, days[0].totalMs)
            assertNotNull(room.skin().latest())
            assertEquals(0, room.uv().between(0, Long.MAX_VALUE).size)
            room.sun().upsertBucket(SunBucketEntity(0, 1, 0.1, 5.0, 1))
            assertEquals(1, room.sun().bucketsSince(0).size)
            // Tabelas da v3 (migração 2 → 3, encadeada).
            room.stress().insertHrv(HrvEntity(timeMillis = 10, rmssdMs = 40.0, meanHr = 60.0, beats = 60, durationMs = 60_000))
            room.stress().upsertSession(EdaSessionEntity(20, null, 0, 20))
            room.stress().upsertPoint(EdaPointEntity(30, 20, 2.0, 1.9, 2.1, 1))
            assertEquals(1, room.stress().hrvAfter(0, 10).size)
            assertEquals(1, room.stress().pointsAfter(0, 10).size)
        }
        room.close()
    }
}
