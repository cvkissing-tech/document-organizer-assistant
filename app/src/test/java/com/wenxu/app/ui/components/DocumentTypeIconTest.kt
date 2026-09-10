package com.wenxu.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentTypeIconTest {
    @Test
    fun mapsCommonOfficeExtensionsToReadableLabels() {
        assertThat(documentFormatLabel("pdf")).isEqualTo("PDF")
        assertThat(documentFormatLabel("docx")).isEqualTo("W")
        assertThat(documentFormatLabel("XLSX")).isEqualTo("X")
        assertThat(documentFormatLabel("ppt")).isEqualTo("PPT")
    }

    @Test
    fun fallsBackToShortUppercaseExtension() {
        assertThat(documentFormatLabel("txt")).isEqualTo("TXT")
        assertThat(documentFormatLabel("")).isEqualTo("FILE")
    }
}
