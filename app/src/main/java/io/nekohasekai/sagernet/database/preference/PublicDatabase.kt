package io.nekohasekai.sagernet.database.preference

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// 1 -> 2 replaced the typed KeyValuePair table with the desktop's `settings` table; the destructive fallback
// resets the settings once.
@Database(entities = [SettingEntry::class], version = 2)
@GenerateRoomMigrations
abstract class PublicDatabase : RoomDatabase() {
    companion object {
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, PublicDatabase::class.java, Key.DB_PUBLIC)
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()
        }

        val settingsDao get() = instance.settingsDao()
    }

    abstract fun settingsDao(): SettingEntry.Dao

}
