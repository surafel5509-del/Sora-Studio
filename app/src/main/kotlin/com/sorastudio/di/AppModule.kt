package com.sorastudio.di

import android.content.Context
import com.sorastudio.core.audio.AudioRecorder
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.database.PatternLibraryRepository
import com.sorastudio.core.database.PresetRepository
import com.sorastudio.core.database.ProjectIndexRepository
import com.sorastudio.core.database.RecordingRepository
import com.sorastudio.core.database.SoraDatabase
import com.sorastudio.core.database.SoundLibraryRepository
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.storage.ProjectStorage
import com.sorastudio.core.storage.SampleStore
import com.sorastudio.core.storage.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SoraDatabase =
        SoraDatabase.create(context)

    @Provides
    @Singleton
    fun provideProjectIndexRepository(db: SoraDatabase): ProjectIndexRepository =
        ProjectIndexRepository(db.projectDao())

    @Provides
    @Singleton
    fun providePresetRepository(db: SoraDatabase): PresetRepository =
        PresetRepository(db.presetDao())

    @Provides
    @Singleton
    fun providePatternLibraryRepository(db: SoraDatabase): PatternLibraryRepository =
        PatternLibraryRepository(db.patternDao())

    @Provides
    @Singleton
    fun provideRecordingRepository(db: SoraDatabase): RecordingRepository =
        RecordingRepository(db.recordingDao())

    @Provides
    @Singleton
    fun provideSoundLibraryRepository(db: SoraDatabase): SoundLibraryRepository =
        SoundLibraryRepository(db.soundLibraryDao())

    @Provides
    @Singleton
    fun provideProjectStorage(@ApplicationContext context: Context): ProjectStorage =
        ProjectStorage(context)

    @Provides
    @Singleton
    fun provideSampleStore(@ApplicationContext context: Context): SampleStore =
        SampleStore(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideProjectSession(): ProjectSession = ProjectSession()

    @Provides
    @Singleton
    fun provideAudioRecorder(): AudioRecorder = AudioRecorder()

    @Provides
    @Singleton
    fun providePlaybackController(
        @ApplicationContext context: Context,
        session: ProjectSession,
        storage: ProjectStorage,
        settings: SettingsRepository
    ): PlaybackController {
        val bufferFrames = runCatching {
            runBlocking(Dispatchers.IO) { settings.settings.first().bufferSizeFrames }
        }.getOrDefault(512)
        return PlaybackController(context, session, storage, bufferFrames)
    }
}
