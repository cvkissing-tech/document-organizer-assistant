package com.wenxu.app.core.relations

import java.util.Locale

enum class DocumentFamily {
    PDF,
    WORD,
    SHEET,
    SLIDES,
    ;

    companion object {
        fun fromExtension(extension: String): DocumentFamily? =
            when (extension.trim().trimStart('.').lowercase(Locale.ROOT)) {
                "pdf" -> PDF
                "doc", "docx" -> WORD
                "xls", "xlsx" -> SHEET
                "ppt", "pptx" -> SLIDES
                else -> null
            }
    }
}
