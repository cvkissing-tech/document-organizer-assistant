package com.wenxu.app.core.localization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLanguageTest {
    @Test
    fun tagsRoundTripAndUnsupportedValuesStayUnselected() {
        assertThat(AppLanguage.SIMPLIFIED_CHINESE.tag).isEqualTo("zh-CN")
        assertThat(AppLanguage.ENGLISH.tag).isEqualTo("en")
        assertThat(AppLanguage.fromStoredValue("SIMPLIFIED_CHINESE"))
            .isEqualTo(AppLanguage.SIMPLIFIED_CHINESE)
        assertThat(AppLanguage.fromStoredValue("ENGLISH"))
            .isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.fromStoredValue("fr")).isNull()
    }
}
