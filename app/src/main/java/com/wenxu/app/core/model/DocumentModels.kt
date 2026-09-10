package com.wenxu.app.core.model

enum class DocumentFormat {
    PDF,
    DOC,
    XLS,
    PPT,
}

enum class DocumentIndexStatus {
    ACTIVE,
    MISSING,
    TRASHED,
}

enum class SourcePermissionState {
    ACTIVE,
    NEEDS_ATTENTION,
}

data class DocumentRecord(
    val id: Long,
    val uri: String,
    val displayName: String,
    val extension: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val sourceId: Long,
    val parentUri: String,
)

data class CategorySummary(
    val id: Long,
    val name: String,
    val documentCount: Int,
)

data class DiscoveredDocument(
    val uri: String,
    val parentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class NameSegment(
    val text: String,
    val isDifference: Boolean,
)

data class NormalizedName(
    val baseName: String,
    val differenceSegments: List<NameSegment>,
)

data class NameGroup(
    val baseName: String,
    val extension: String,
    val memberIds: List<Long>,
)

data class IndexReport(
    val added: Int,
    val changed: Int,
    val missingIds: List<Long>,
)

data class ScanReport(
    val scanned: Int,
    val added: Int,
    val changed: Int,
    val exactSets: Int,
)

data class DuplicateSet(
    val id: Long,
    val memberIds: List<Long>,
)

data class DuplicateReport(
    val sets: List<DuplicateSet>,
)
