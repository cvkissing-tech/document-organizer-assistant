package com.wenxu.app.core.model

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.testDocument
import org.junit.Test

class DocumentModelsTest {
    @Test
    fun fixtureDerivesLowercaseExtensionFromDisplayName() {
        val document = testDocument("课程讲义.PDF")

        assertThat(document.extension).isEqualTo("pdf")
        assertThat(document.displayName).isEqualTo("课程讲义.PDF")
    }
}
