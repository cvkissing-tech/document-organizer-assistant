package com.wenxu.app.core.relations

enum class ProtectedIdentifierKind {
    YEAR,
    CHAPTER,
    ASSIGNMENT,
    COURSE,
    PERSON,
}

data class ProtectedIdentifier(
    val kind: ProtectedIdentifierKind,
    val value: String,
)
