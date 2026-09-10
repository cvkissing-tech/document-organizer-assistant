package com.wenxu.app.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentShareTest {
    @Test
    fun officeFormatsKeepSpecificMimeTypesWhenShared() {
        assertThat(mimeTypeFor("docx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        assertThat(mimeTypeFor("xlsx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertThat(mimeTypeFor("pptx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation")
    }
}
