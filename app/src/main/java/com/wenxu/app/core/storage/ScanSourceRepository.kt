package com.wenxu.app.core.storage

import android.net.Uri
import com.wenxu.app.core.database.dao.SourceDao
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.model.SourcePermissionState
import kotlinx.coroutines.flow.Flow

interface ScanSourceRepository {
    fun observeSources(): Flow<List<ScanSourceEntity>>
    suspend fun add(uri: String): Result<Long>
    suspend fun reauthorize(sourceId: Long, uri: String): Result<Unit>
    suspend fun remove(sourceId: Long): Result<Unit>
}

class RoomScanSourceRepository(
    private val sourceDao: SourceDao,
    private val gateway: DocumentGateway,
) : ScanSourceRepository {
    override fun observeSources(): Flow<List<ScanSourceEntity>> = sourceDao.observeAll()

    override suspend fun add(uri: String): Result<Long> {
        val parsedUri = Uri.parse(uri)
        return gateway.persistTreePermission(parsedUri).mapCatching {
            val existing = sourceDao.findByTreeUri(uri)
            sourceDao.upsert(
                ScanSourceEntity(
                    id = existing?.id ?: 0,
                    treeUri = uri,
                    displayName = displayNameFrom(parsedUri),
                    permissionState = SourcePermissionState.ACTIVE,
                    lastScanAt = existing?.lastScanAt,
                    sortOrder = existing?.sortOrder ?: 0,
                ),
            )
        }
    }

    override suspend fun reauthorize(sourceId: Long, uri: String): Result<Unit> {
        val source = sourceDao.findById(sourceId)
            ?: return Result.failure(IllegalArgumentException("扫描来源不存在"))
        val parsedUri = Uri.parse(uri)
        return gateway.persistTreePermission(parsedUri).mapCatching {
            sourceDao.upsert(
                source.copy(
                    treeUri = uri,
                    displayName = displayNameFrom(parsedUri),
                    permissionState = SourcePermissionState.ACTIVE,
                ),
            )
            Unit
        }
    }

    override suspend fun remove(sourceId: Long): Result<Unit> = runCatching {
        val source = sourceDao.findById(sourceId)
            ?: throw IllegalArgumentException("扫描来源不存在")
        check(sourceDao.countTrashedDocuments(sourceId) == 0) {
            "请先恢复或永久删除该位置的回收站文档"
        }
        sourceDao.delete(source)
    }

    private fun displayNameFrom(uri: Uri): String {
        val rawName = uri.lastPathSegment
            ?.substringAfterLast(':')
            ?.takeIf(String::isNotBlank)
            ?: "已授权文件夹"
        return Uri.decode(rawName)
    }
}
