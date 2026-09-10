package com.wenxu.app.core.index

import com.wenxu.app.core.model.DocumentFormat
import java.util.Locale

object DocumentTypePolicy {
    private val extensionFormats = mapOf(
        "pdf" to DocumentFormat.PDF,
        "doc" to DocumentFormat.DOC,
        "docx" to DocumentFormat.DOC,
        "xls" to DocumentFormat.XLS,
        "xlsx" to DocumentFormat.XLS,
        "ppt" to DocumentFormat.PPT,
        "pptx" to DocumentFormat.PPT,
    )

    fun supports(displayName: String): Boolean = formatOf(displayName) != null

    fun formatOf(displayName: String): DocumentFormat? =
        extensionFormats[extensionOf(displayName)]

    fun extensionOf(displayName: String): String =
        displayName
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)

    fun normalizedBaseName(displayName: String): String =
        displayName
            .substringBeforeLast('.', missingDelimiterValue = displayName)
            .trim()
            .lowercase(Locale.ROOT)
}
