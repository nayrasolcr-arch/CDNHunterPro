package com.cdhunter.pro.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local database (v1).
 *
 * Migration policy: schema JSON is exported to app/schemas; future versions
 * must register a Migration in [Migrations] and bump [AppDatabase.VERSION].
 * Destructive migration is intentionally NOT enabled so user data survives
 * upgrades.
 */
@Database(
    entities = [
        ProviderStateEntity::class,
        IpRangeEntity::class,
        CandidateEntity::class,
        ScanEntity::class,
        ResultEntity::class,
        SniResultEntity::class,
        ExportLogEntity::class,
        ErrorLogEntity::class,
        SettingsEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerStateDao(): ProviderStateDao
    abstract fun ipRangeDao(): IpRangeDao
    abstract fun candidateDao(): CandidateDao
    abstract fun scanDao(): ScanDao
    abstract fun resultDao(): ResultDao
    abstract fun sniResultDao(): SniResultDao
    abstract fun exportLogDao(): ExportLogDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val VERSION = 1
        const val NAME = "cdnhunter.db"

        /**
         * Registered migrations between schema versions. Empty at v1 by
         * definition; kept as the single place to add future migrations.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

object Migrations
