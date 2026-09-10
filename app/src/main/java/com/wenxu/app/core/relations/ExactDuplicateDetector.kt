package com.wenxu.app.core.relations

import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.model.DuplicateReport
import kotlinx.coroutines.CancellationException
import java.io.IOException

class ExactDuplicateDetector(
    private val store: ExactDuplicateStore,
    private val hasher: ContentHasher,
) : ExactDuplicateAnalysis {
    override suspend fun analyze(): List<ExactDuplicateGroup> {
        val groups = mutableListOf<ExactDuplicateGroup>()
        val candidateBuckets = store.activeCandidates()
            .groupBy { it.sizeBytes }
            .values
            .filter { bucket -> bucket.size > 1 }

        candidateBuckets.forEach { bucket ->
            val hashedDocuments = mutableListOf<Pair<String, DocumentEntity>>()
            bucket.forEach { document ->
                cachedOrFreshHash(document)?.let { hash ->
                    hashedDocuments += hash to document
                }
            }
            hashedDocuments.groupBy(
                    keySelector = { (hash, _) -> hash },
                    valueTransform = { (_, document) -> document },
                )
                .filterValues { members -> members.size > 1 }
                .forEach { (hash, members) ->
                    groups += ExactDuplicateGroup(
                    contentHash = hash,
                    sizeBytes = members.first().sizeBytes,
                    memberIds = members.map { it.id }.sorted(),
                )
            }
        }

        groups.sortWith(
            compareByDescending<ExactDuplicateGroup> { it.sizeBytes }
                .thenBy { it.contentHash },
        )

        return groups
    }

    suspend fun rebuild(): DuplicateReport {
        return DuplicateReport(store.replaceGroups(analyze()))
    }

    private suspend fun cachedOrFreshHash(document: DocumentEntity): String? {
        val cached = document.contentHash
        if (
            cached != null &&
            document.hashBasisSize == document.sizeBytes &&
            document.hashBasisModifiedAt == document.modifiedAt
        ) {
            return cached
        }

        return try {
            hasher.hash(document).also { hash ->
                store.saveHash(
                    documentId = document.id,
                    hash = hash,
                    sizeBytes = document.sizeBytes,
                    modifiedAt = document.modifiedAt,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
