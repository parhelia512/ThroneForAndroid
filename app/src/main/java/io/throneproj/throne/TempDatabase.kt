package io.throneproj.throne

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.throneproj.throne.SagerNet
import io.throneproj.throne.database.preference.KeyValuePair
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Database(entities = [KeyValuePair::class], version = 1)
abstract class TempDatabase : RoomDatabase() {

    companion object {
        @Suppress("EXPERIMENTAL_API_USAGE")
        private val instance by lazy {
            Room.inMemoryDatabaseBuilder(SagerNet.application, TempDatabase::class.java)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()
        }

        val profileCacheDao get() = instance.profileCacheDao()

    }

    abstract fun profileCacheDao(): KeyValuePair.Dao
}