package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

@Composable
internal fun ColumnScope.RgbwCalibrationPreferences(prefs: Preferences) {
    val resources = LocalResources.current
    SettingsGroup(title = stringResource(R.string.pref_group_rgbw_calibration)) {
        val controls = listOf(
            Triple(R.string.pref_key_rgbw_white_threshold, R.string.pref_title_rgbw_white_threshold, 0..100),
            Triple(R.string.pref_key_rgbw_white_intensity, R.string.pref_title_rgbw_white_intensity, 0..200),
            Triple(R.string.pref_key_rgbw_white_red, R.string.pref_title_rgbw_white_red, 1..200),
            Triple(R.string.pref_key_rgbw_white_green, R.string.pref_title_rgbw_white_green, 1..200),
            Triple(R.string.pref_key_rgbw_white_blue, R.string.pref_title_rgbw_white_blue, 1..200),
        )
        for ((key, title, range) in controls) {
            SliderPreference(
                prefs = prefs,
                keyRes = key,
                title = stringResource(title),
                range = range,
                summaryProvider = { resources.getString(R.string.unit_percent, it) },
            )
        }
    }
}
