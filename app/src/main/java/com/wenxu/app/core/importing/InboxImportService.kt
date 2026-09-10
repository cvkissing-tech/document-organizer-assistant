package com.wenxu.app.core.importing

import com.wenxu.app.core.settings.InboxPreferences
import com.wenxu.app.core.storage.DocumentGateway
import com.wenxu.app.core.storage.ScanSourceRepository
import com.wenxu.app.feature.home.HomeRepository

class InboxImportService(
    private val gateway: DocumentGateway,
    private val sourceRepository: ScanSourceRepository,
    private val preferences: InboxPreferences,
    private val homeRepository: HomeRepository,
) {
    suspend fun import(items: List<IncomingDocument>, inboxTreeUri: String): ImportReport {
        sourceRepository.add(inboxTreeUri).getOrElse { error ->
            return ImportReport(
                imported = 0,
                skipped = 0,
                failed = items.size,
                failures = listOf(error.message ?: "无法授权文档收件箱"),
                failedUris = items.map { it.uri },
            )
        }
        preferences.setInboxTreeUri(inboxTreeUri)
        var imported = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        val failedUris = mutableListOf<String>()
        items.distinctBy { it.uri }.forEach { item ->
            if (!com.wenxu.app.core.index.DocumentTypePolicy.supports(item.displayName)) {
                skipped++
                return@forEach
            }
            gateway.copyToFolderVerified(
                sourceUri = item.uri,
                targetTreeUri = inboxTreeUri,
                displayName = item.displayName,
                mimeType = item.mimeType,
                expectedSize = item.sizeBytes,
            ).fold(
                onSuccess = { imported++ },
                onFailure = { error ->
                    failures += "${item.displayName}：${error.message ?: "复制失败"}"
                    failedUris += item.uri
                },
            )
        }
        if (imported > 0) homeRepository.rescan()
        return ImportReport(imported, skipped, failures.size, failures, failedUris)
    }
}
