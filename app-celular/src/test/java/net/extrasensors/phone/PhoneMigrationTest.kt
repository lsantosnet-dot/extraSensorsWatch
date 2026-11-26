package net.extrasensors.phone

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.extrasensors.phone.data.HrvRow
import net.extrasensors.phone.data.PhoneDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Os dados de sol e pele já no celular sobrevivem à atualização para a v2. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneMigrationTest {
    @Test
    fun `migra v1 para v2 preservando dados`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "phone-migration-test.db"
        ctx.deleteDatabase(name)
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `sun_bucket` (`bucketStart` INTEGER NOT NULL, `exposedMs` INTEGER NOT NULL, `doseSed` REAL NOT NULL, `maxUv` REAL, PRIMARY KEY(`bucketStart`))",
                "CREATE TABLE IF NOT EXISTS `sun_day` (`epochDay` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
                "CREATE TABLE IF NOT EXISTS `skin_reading` (`timeMillis` INTEGER NOT NULL, `celsius` REAL NOT NULL, PRIMARY KEY(`timeMillis`))",
                "CREATE TABLE IF NOT EXISTS `sunscreen` (`timeMillis` INTEGER NOT NULL, PRIMARY KEY(`timeMillis`))",
                "CREATE TABLE IF NOT EXISTS `uv_hour` (`hourStart` INTEGER NOT NULL, `uvi` REAL NOT NULL, PRIMARY KEY(`hourStart`))",
                "INSERT INTO skin_reading VALUES (1000, 33.5)",
            ).forEach(db::execSQL)
            db.version = 1
        }
        val room = PhoneDatabase.create(ctx, name)
        runBlocking {
            assertEquals(1, room.dao().allSkin().size)
            room.dao().upsertHrv(listOf(HrvRow(5, 40.0, 60.0, 60, 60_000)))
            assertEquals(1, room.dao().allHrv().size)
        }
        room.close()
    }
}
