package com.wenxu.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wenxu.app.core.database.dao.CategoryDao
import com.wenxu.app.core.database.dao.DocumentDao
import com.wenxu.app.core.database.dao.DocumentIdentityAliasDao
import com.wenxu.app.core.database.dao.PendingTrashOperationDao
import com.wenxu.app.core.database.dao.PendingTrashTargetStateDao
import com.wenxu.app.core.database.dao.RelationDao
import com.wenxu.app.core.database.dao.RelationAnalysisDao
import com.wenxu.app.core.database.dao.ScanSessionDao
import com.wenxu.app.core.database.dao.ScanVisitedDirectoryDao
import com.wenxu.app.core.database.dao.SourceDao
import com.wenxu.app.core.database.dao.TrashDao
import com.wenxu.app.core.database.entity.CategoryEntity
import com.wenxu.app.core.database.entity.DocumentCategoryEntity
import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.database.entity.DocumentFingerprintEntity
import com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity
import com.wenxu.app.core.database.entity.ExactDuplicateMemberEntity
import com.wenxu.app.core.database.entity.ExactDuplicateSetEntity
import com.wenxu.app.core.database.entity.IgnoredSimilarityGroupEntity
import com.wenxu.app.core.database.entity.NameSimilarityGroupEntity
import com.wenxu.app.core.database.entity.NameSimilarityMemberEntity
import com.wenxu.app.core.database.entity.PendingTrashOperationEntity
import com.wenxu.app.core.database.entity.PendingTrashTargetStateEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStateEntity
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanSessionEntity
import com.wenxu.app.core.database.entity.ScanVisitedDirectoryEntity
import com.wenxu.app.core.database.entity.SimilarityCandidateEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import com.wenxu.app.core.database.entity.TrashCategorySnapshotEntity
import com.wenxu.app.core.database.entity.TrashRecordEntity

@Database(
    entities = [
        ScanSourceEntity::class,
        ScanSessionEntity::class,
        ScanVisitedDirectoryEntity::class,
        DocumentEntity::class,
        CategoryEntity::class,
        DocumentCategoryEntity::class,
        NameSimilarityGroupEntity::class,
        NameSimilarityMemberEntity::class,
        ExactDuplicateSetEntity::class,
        ExactDuplicateMemberEntity::class,
        TrashRecordEntity::class,
        TrashCategorySnapshotEntity::class,
        DocumentFingerprintEntity::class,
        SimilarityCandidateEntity::class,
        SimilarityFeedbackEntity::class,
        IgnoredSimilarityGroupEntity::class,
        RelationAnalysisStateEntity::class,
        PendingTrashOperationEntity::class,
        PendingTrashTargetStateEntity::class,
        DocumentIdentityAliasEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(WenxuTypeConverters::class)
abstract class WenxuDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun sourceDao(): SourceDao
    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun scanVisitedDirectoryDao(): ScanVisitedDirectoryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun relationDao(): RelationDao
    abstract fun relationAnalysisDao(): RelationAnalysisDao
    abstract fun trashDao(): TrashDao
    abstract fun pendingTrashOperationDao(): PendingTrashOperationDao
    abstract fun pendingTrashTargetStateDao(): PendingTrashTargetStateDao
    abstract fun documentIdentityAliasDao(): DocumentIdentityAliasDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE trash_records ADD COLUMN trashedParentUri TEXT NOT NULL DEFAULT ''",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE scan_sources ADD COLUMN sourceKind TEXT NOT NULL DEFAULT 'TREE'",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_sessions (
                id TEXT NOT NULL,
                sourceId INTEGER NOT NULL,
                scanId TEXT NOT NULL,
                status TEXT NOT NULL,
                phase TEXT NOT NULL,
                cursorId INTEGER,
                checkedFiles INTEGER NOT NULL,
                discoveredDocuments INTEGER NOT NULL,
                failedFiles INTEGER NOT NULL,
                startedAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                errorMessage TEXT,
                PRIMARY KEY(id),
                FOREIGN KEY(sourceId) REFERENCES scan_sources(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_sessions_sourceId " +
                "ON scan_sessions(sourceId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_sessions_status ON scan_sessions(status)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_visited_directories (
                sessionId TEXT NOT NULL,
                directoryPath TEXT NOT NULL,
                PRIMARY KEY(sessionId, directoryPath),
                FOREIGN KEY(sessionId) REFERENCES scan_sessions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_visited_directories_sessionId " +
                "ON scan_visited_directories(sessionId)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) = Unit
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildNameSimilarityTables(db)
        createRelationAnalysisTables(db)
    }

    private fun rebuildNameSimilarityTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS name_similarity_groups_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                baseName TEXT NOT NULL,
                extension TEXT NOT NULL,
                confirmedDocumentId INTEGER,
                referenceDocumentId INTEGER,
                confidenceBand TEXT NOT NULL DEFAULT 'HIGH',
                analysisVersion INTEGER NOT NULL DEFAULT 1,
                reviewState TEXT NOT NULL DEFAULT 'PENDING',
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(confirmedDocumentId) REFERENCES documents(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(referenceDocumentId) REFERENCES documents(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO name_similarity_groups_new(
                id, baseName, extension, confirmedDocumentId, referenceDocumentId,
                confidenceBand, analysisVersion, reviewState, updatedAt
            )
            SELECT
                g.id,
                g.baseName,
                g.extension,
                g.confirmedDocumentId,
                COALESCE(
                    g.confirmedDocumentId,
                    (SELECT MIN(m.documentId)
                     FROM name_similarity_members AS m
                     WHERE m.groupId = g.id)
                ),
                'HIGH',
                1,
                'PENDING',
                g.updatedAt
            FROM name_similarity_groups AS g
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS name_similarity_members_new (
                groupId INTEGER NOT NULL,
                documentId INTEGER NOT NULL,
                differenceSegments TEXT NOT NULL,
                score INTEGER NOT NULL DEFAULT 0,
                evidenceCodes TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY(groupId, documentId),
                FOREIGN KEY(groupId) REFERENCES name_similarity_groups_new(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(documentId) REFERENCES documents(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO name_similarity_members_new(
                groupId, documentId, differenceSegments, score, evidenceCodes
            )
            SELECT groupId, documentId, differenceSegments, 0, '[]'
            FROM name_similarity_members
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE name_similarity_members")
        db.execSQL("DROP TABLE name_similarity_groups")
        db.execSQL("ALTER TABLE name_similarity_groups_new RENAME TO name_similarity_groups")
        db.execSQL("ALTER TABLE name_similarity_members_new RENAME TO name_similarity_members")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_name_similarity_groups_baseName_extension " +
                "ON name_similarity_groups(baseName, extension)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_name_similarity_groups_confirmedDocumentId " +
                "ON name_similarity_groups(confirmedDocumentId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_name_similarity_groups_referenceDocumentId " +
                "ON name_similarity_groups(referenceDocumentId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_name_similarity_members_documentId " +
                "ON name_similarity_members(documentId)",
        )
    }

    private fun createRelationAnalysisTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS document_fingerprints (
                documentId INTEGER NOT NULL,
                algorithmVersion INTEGER NOT NULL,
                textMinHash BLOB NOT NULL,
                normalizedTextLength INTEGER NOT NULL,
                structureSignature TEXT NOT NULL,
                extractStatus TEXT NOT NULL,
                basisSize INTEGER NOT NULL,
                basisModifiedAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(documentId),
                FOREIGN KEY(documentId) REFERENCES documents(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS similarity_candidates (
                documentAId INTEGER NOT NULL,
                documentBId INTEGER NOT NULL,
                nameScore REAL NOT NULL,
                contentScore REAL,
                metadataScore INTEGER NOT NULL,
                confidenceBand TEXT NOT NULL,
                evidenceCodes TEXT NOT NULL,
                analysisVersion INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(documentAId, documentBId),
                FOREIGN KEY(documentAId) REFERENCES documents(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(documentBId) REFERENCES documents(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_similarity_candidates_documentBId " +
                "ON similarity_candidates(documentBId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_similarity_candidates_confidenceBand " +
                "ON similarity_candidates(confidenceBand)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS similarity_feedback (
                documentAId INTEGER NOT NULL,
                documentBId INTEGER NOT NULL,
                decision TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(documentAId, documentBId),
                FOREIGN KEY(documentAId) REFERENCES documents(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(documentBId) REFERENCES documents(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_similarity_feedback_documentBId " +
                "ON similarity_feedback(documentBId)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ignored_similarity_groups (
                groupFingerprint TEXT NOT NULL,
                ignoredAt INTEGER NOT NULL,
                PRIMARY KEY(groupFingerprint)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS relation_analysis_state (
                id INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL,
                phase TEXT NOT NULL,
                processedCount INTEGER NOT NULL,
                candidateCount INTEGER NOT NULL,
                failedCount INTEGER NOT NULL,
                generation INTEGER NOT NULL,
                errorMessage TEXT,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ignored_similarity_groups " +
                "ADD COLUMN memberIds TEXT NOT NULL DEFAULT '[]'",
        )
        db.execSQL(
            "ALTER TABLE trash_records " +
                "ADD COLUMN storageKind TEXT NOT NULL DEFAULT 'APP_TRASH'",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS document_identity_aliases (
                oldDocumentId INTEGER NOT NULL,
                canonicalDocumentId INTEGER NOT NULL,
                PRIMARY KEY(oldDocumentId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_document_identity_aliases_canonicalDocumentId " +
                "ON document_identity_aliases(canonicalDocumentId)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_trash_operations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                action TEXT NOT NULL,
                targetKind TEXT NOT NULL,
                targetIds TEXT NOT NULL,
                eligibleTargetIds TEXT NOT NULL DEFAULT '[]',
                seedSuccesses TEXT NOT NULL DEFAULT '[]',
                seedFailures TEXT NOT NULL DEFAULT '[]',
                fallbackParentUri TEXT,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_trash_operations_status " +
                "ON pending_trash_operations(status)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_trash_operations_updatedAt " +
                "ON pending_trash_operations(updatedAt)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_trash_target_states (
                operationId INTEGER NOT NULL,
                targetId INTEGER NOT NULL,
                stage TEXT NOT NULL,
                documentUri TEXT NOT NULL,
                parentUri TEXT NOT NULL,
                PRIMARY KEY(operationId, targetId),
                FOREIGN KEY(operationId) REFERENCES pending_trash_operations(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}
