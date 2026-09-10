package com.wenxu.app.core.relations

import com.wenxu.app.core.model.NameSegment

enum class NameMarkerKind {
    VERSION,
    STATUS,
    COPY,
    REVIEW,
    DATE,
}

data class NameMarker(
    val text: String,
    val kind: NameMarkerKind,
)

data class NameFeatures(
    val rawStem: String,
    val coreName: String,
    val coreKey: String,
    val markers: List<NameMarker>,
    val differenceSegments: List<NameSegment>,
    val characterTokens: Set<String>,
    val protectedIdentifiers: Set<ProtectedIdentifier>,
    val unprotectedNumericIdentifiers: List<String>,
    val numericIdentifiers: Set<String>,
) {
    val hasVersionEvidence: Boolean get() = markers.isNotEmpty()
}

internal fun characterTokens(value: String): Set<String> {
    if (value.isBlank()) return emptySet()
    if (value.length <= 2) return setOf(value)
    return buildSet {
        for (size in 2..3) {
            if (value.length < size) continue
            for (start in 0..value.length - size) {
                add(value.substring(start, start + size))
            }
        }
    }
}
