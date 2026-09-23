package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RouteProfileEntity::class, RouteRuleEntity::class],
    version = 11,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ]
)
@TypeConverters(value = [SubscriptionConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    companion object {

        /** 9 -> 10: the profile table is rebuilt around (type, outboundJson); groups, rules and settings are untouched. */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `proxy_entities`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `proxy_entities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, `type` TEXT NOT NULL, `outboundJson` TEXT NOT NULL, " +
                        "`userOrder` INTEGER NOT NULL, `tx` INTEGER NOT NULL, `rx` INTEGER NOT NULL, " +
                        "`status` INTEGER NOT NULL, `ping` INTEGER NOT NULL, `uuid` TEXT NOT NULL, `error` TEXT, " +
                        "`speedTestMode` TEXT NOT NULL DEFAULT '', " +
                        "`speedTestDownloadBitsPerSecond` INTEGER NOT NULL DEFAULT 0, " +
                        "`speedTestUploadBitsPerSecond` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `groupId` ON `proxy_entities` (`groupId`)")
            }
        }

        /** 10 -> 11: the flat `rules` table gives way to the desktop's route profiles, seeded with the Default one. */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `rules`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `route_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL DEFAULT '', `default_outbound_id` INTEGER NOT NULL DEFAULT -1, " +
                        "`is_remote` INTEGER NOT NULL DEFAULT 0, `remote_url` TEXT NOT NULL DEFAULT '', " +
                        "`auto_update` INTEGER NOT NULL DEFAULT 0, `remote_last_update` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `route_rules` (`route_profile_id` INTEGER NOT NULL, " +
                        "`rule_order` INTEGER NOT NULL, `name` TEXT NOT NULL DEFAULT '', `type` INTEGER NOT NULL DEFAULT 0, " +
                        "`ip_version` TEXT NOT NULL DEFAULT '', `network` TEXT NOT NULL DEFAULT '', " +
                        "`protocol` TEXT NOT NULL DEFAULT '', `inbound_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`domain_json` TEXT NOT NULL DEFAULT '[]', `domain_suffix_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`domain_keyword_json` TEXT NOT NULL DEFAULT '[]', `domain_regex_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`source_ip_cidr_json` TEXT NOT NULL DEFAULT '[]', `source_ip_is_private` INTEGER NOT NULL DEFAULT 0, " +
                        "`ip_cidr_json` TEXT NOT NULL DEFAULT '[]', `ip_is_private` INTEGER NOT NULL DEFAULT 0, " +
                        "`source_port_json` TEXT NOT NULL DEFAULT '[]', `source_port_range_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`port_json` TEXT NOT NULL DEFAULT '[]', `port_range_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`process_name_json` TEXT NOT NULL DEFAULT '[]', `process_path_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`process_path_regex_json` TEXT NOT NULL DEFAULT '[]', `package_name_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`rule_set_json` TEXT NOT NULL DEFAULT '[]', `invert` INTEGER NOT NULL DEFAULT 0, " +
                        "`outbound_id` INTEGER NOT NULL DEFAULT -2, `action` TEXT NOT NULL DEFAULT 'route', " +
                        "`reject_method` TEXT NOT NULL DEFAULT '', `no_drop` INTEGER NOT NULL DEFAULT 0, " +
                        "`override_address` TEXT NOT NULL DEFAULT '', `override_port` TEXT NOT NULL DEFAULT '', " +
                        "`sniffers_json` TEXT NOT NULL DEFAULT '[]', `sniff_override_dest` INTEGER NOT NULL DEFAULT 0, " +
                        "`strategy` TEXT NOT NULL DEFAULT '', `wifi_ssid_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`wifi_bssid_json` TEXT NOT NULL DEFAULT '[]', `tls_spoof` TEXT NOT NULL DEFAULT '', " +
                        "`tls_spoof_method` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`route_profile_id`, `rule_order`), " +
                        "FOREIGN KEY(`route_profile_id`) REFERENCES `route_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `route_profiles` (`id`, `name`, `default_outbound_id`) VALUES (1, 'Default', -1)"
                )
                db.execSQL(
                    "INSERT INTO `route_rules` (`route_profile_id`, `rule_order`, `name`, `protocol`, `action`) " +
                        "VALUES (1, 0, 'Route DNS', 'dns', 'hijack-dns')"
                )
            }
        }

        private fun buildProfileDatabase(): SagerDatabase =
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
//                .addMigrations(*SagerDatabase_Migrations.build())
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()

        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            val db = buildProfileDatabase()
            // 先试打开：数据库文件损坏时首次访问会抛异常，此时记录原始错误、
            // 删除损坏文件并重建空库，让应用可以继续启动而不是陷入崩溃循环。
            try {
                db.openHelper.writableDatabase
            } catch (e: Exception) {
                Logs.e(e)
                runCatching { db.close() }
                backupCorruptedDatabase()
                SagerNet.application.deleteDatabase(Key.DB_PROFILE)
                return@lazy buildProfileDatabase()
            }
            db
        }

        /**
         * 删库重建前将原库文件备份为同目录下带时间戳的副本，降低数据丢失面。
         * 备份失败仅记录日志，不阻断删库重建流程。
         */
        private fun backupCorruptedDatabase() {
            runCatching {
                val dbFile = SagerNet.application.getDatabasePath(Key.DB_PROFILE)
                if (dbFile.exists()) {
                    val backupFile = File(
                        dbFile.parentFile, dbFile.name + ".bak_" + System.currentTimeMillis()
                    )
                    dbFile.copyTo(backupFile, overwrite = false)
                    Logs.i("Corrupted database backed up as ${backupFile.name}")
                }
            }.onFailure {
                Logs.w("Failed to backup corrupted database before rebuild", it)
            }
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val routeDao get() = instance.routeDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun routeDao(): RouteDao

}
