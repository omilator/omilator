package com.omilator.data.settings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class SettingsStore(
    private val readText: suspend (String) -> String?,
    private val writeText: suspend (String, String) -> Unit,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Serializes read-modify-write cycles so concurrent updates cannot
     *  overwrite each other, and forces every update through a full
     *  AppSettings copy instead of a partial reconstruction. */
    private val updateLock = Mutex()

    suspend fun loadAppSettings(path: String): AppSettings {
        val text = readText(path) ?: return AppSettings.DEFAULT
        return runCatching { json.decodeFromString<AppSettings>(text) }
            .getOrElse { AppSettings.DEFAULT }
    }

    suspend fun saveAppSettings(settings: AppSettings, path: String) {
        writeText(path, json.encodeToString(settings))
    }

    suspend fun updateAppSettings(
        path: String,
        transform: (AppSettings) -> AppSettings,
    ): AppSettings = updateLock.withLock {
        val updated = transform(loadAppSettings(path))
        saveAppSettings(updated, path)
        updated
    }

    suspend fun loadGameSettings(path: String): GameSettings? {
        val text = readText(path) ?: return null
        return runCatching { json.decodeFromString<GameSettings>(text) }.getOrNull()
    }

    suspend fun saveGameSettings(settings: GameSettings, path: String) {
        writeText(path, json.encodeToString(settings))
    }
}
