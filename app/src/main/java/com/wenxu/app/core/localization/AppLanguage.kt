package com.wenxu.app.core.localization

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

enum class AppLanguage(val tag: String) {
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en");

    companion object {
        fun fromStoredValue(value: String?): AppLanguage? =
            entries.firstOrNull { language -> language.name == value }

        fun systemDefault(): AppLanguage =
            if (Locale.getDefault().language == Locale.CHINESE.language) {
                SIMPLIFIED_CHINESE
            } else {
                ENGLISH
            }
    }
}

fun applyAppLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(
        LocaleListCompat.forLanguageTags(language.tag),
    )
}
