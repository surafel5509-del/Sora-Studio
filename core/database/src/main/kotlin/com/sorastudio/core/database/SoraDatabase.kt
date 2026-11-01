package com.sorastudio.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        PresetEntity::class,
        PatternEntity::class,
        RecordingEntity::class,
        FavoriteSoundEntity::class,
        RecentSoundEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SoraDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun presetDao(): PresetDao
    abstract fun patternDao(): PatternDao
    abstract fun recordingDao(): RecordingDao
    abstract fun soundLibraryDao(): SoundLibraryDao

    companion object {
        fun create(context: Context): SoraDatabase =
            Room.databaseBuilder(context.applicationContext, SoraDatabase::class.java, "sora_studio.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
