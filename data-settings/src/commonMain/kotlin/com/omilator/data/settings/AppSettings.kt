package com.omilator.data.settings

import kotlinx.serialization.Serializable

@Serializable
enum class AppTheme { SYSTEM, LIGHT, DARK }

@Serializable
data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val libraryDirectories: List<String> = emptyList(),
    val coresDirectory: String = "",
    val saveStatesDirectory: String = "",
    val preferAccuracyOverSpeed: Boolean = false,
    val audioLatencyMillis: Int = 50,
    val vsyncEnabled: Boolean = true,
    val theGamesDbApiKey: String = "",
    val libtecaServerUrl: String = "",
    val libtecaServerToken: String = "",
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}
