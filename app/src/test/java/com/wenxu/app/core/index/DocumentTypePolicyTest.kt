package com.wenxu.app.core.index

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.model.DocumentFormat
import org.junit.Test

class DocumentTypePolicyTest {
    @Test
    fun acceptsOnlySupportedDocuments() {
        val accepted = listOf(
            "a.pdf",
            "b.doc",
            "c.docx",
            "d.xls",
            "e.xlsx",
            "f.ppt",
            "g.pptx",
            "讲义.PDF",
        )

        assertThat(accepted.all(DocumentTypePolicy::supports)).isTrue()
        assertThat(DocumentTypePolicy.supports("photo.jpg")).isFalse()
        assertThat(DocumentTypePolicy.supports("archive.zip")).isFalse()
        assertThat(DocumentTypePolicy.supports("没有扩展名")).isFalse()
    }

    @Test
    fun groupsOfficeExtensionsIntoFourProductFormats() {
        assertThat(DocumentTypePolicy.formatOf("讲义.docx")).isEqualTo(DocumentFormat.DOC)
        assertThat(DocumentTypePolicy.formatOf("成绩.xlsx")).isEqualTo(DocumentFormat.XLS)
        assertThat(DocumentTypePolicy.formatOf("答辩.pptx")).isEqualTo(DocumentFormat.PPT)
        assertThat(DocumentTypePolicy.formatOf("论文.pdf")).isEqualTo(DocumentFormat.PDF)
    }
}
