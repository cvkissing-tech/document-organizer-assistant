package com.wenxu.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.CategoryEntity
import com.wenxu.app.core.database.entity.DocumentCategoryEntity
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionEntity
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.database.entity.ScanSourceEntity
import com.wenxu.app.core.database.entity.ScanVisitedDirectoryEntity
import com.wenxu.app.core.database.entity.DocumentFingerprintEntity
import com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity
import com.wenxu.app.core.database.entity.ExactDuplicateMemberEntity
import com.wenxu.app.core.database.entity.ExactDuplicateSetEntity
import com.wenxu.app.core.database.entity.FingerprintExtractStatus
import com.wenxu.app.core.database.entity.IgnoredSimilarityGroupEntity
import com.wenxu.app.core.database.entity.NameSimilarityGroupEntity
import com.wenxu.app.core.database.entity.NameSimilarityMemberEntity
import com.wenxu.app.core.database.entity.PendingTrashAction
import com.wenxu.app.core.database.entity.PendingTrashOperationEntity
import com.wenxu.app.core.database.entity.PendingTrashSeedFailure
import com.wenxu.app.core.database.entity.PendingTrashSeedSuccess
import com.wenxu.app.core.database.entity.PendingTrashStatus
import com.wenxu.app.core.database.entity.PendingTrashTargetKind
import com.wenxu.app.core.database.entity.PendingTrashTargetStage
import com.wenxu.app.core.database.entity.PendingTrashTargetStateEntity
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStateEntity
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.database.entity.SimilarityCandidateEntity
import com.wenxu.app.core.database.entity.SimilarityFeedbackDecision
import com.wenxu.app.core.database.entity.SimilarityFeedbackEntity
import com.wenxu.app.core.database.entity.SimilarityReviewState
import com.wenxu.app.core.database.entity.TrashStorageKind
import com.wenxu.app.core.relations.EvidenceCode
import com.wenxu.app.core.relations.ConfidenceBand
import com.wenxu.app.core.relations.ExactDuplicateGroup
import com.wenxu.app.core.relations.NameGroupDraft
import com.wenxu.app.core.relations.NameGroupMemberDraft
import com.wenxu.app.core.relations.RoomNameSimilarityStore
import com.wenxu.app.core.relations.RoomRelationAnalysisScheduleStore
import com.wenxu.app.core.relations.RoomSimilarityFeedbackStore
import com.wenxu.app.core.relations.MergeVersionsRequest
import com.wenxu.app.core.relations.FeedbackPersistenceResult
import com.wenxu.app.core.relations.similarityGroupFingerprint
import com.wenxu.app.core.relations.ignoredFingerprintCandidates
import com.wenxu.app.core.trash.RoomTrashStore
import com.wenxu.app.core.trash.TrashMetadataChange
import com.wenxu.app.core.trash.TrashFailureReason
import com.wenxu.app.core.storage.TrashLocation
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.testDocumentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WenxuDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WenxuDatabase::class.java,
    )

    private lateinit var database: WenxuDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, WenxuDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun oneDocumentCanBelongToTwoCategories() = runTest {
        database.sourceDao().upsert(
            ScanSourceEntity(
                id = 1,
                treeUri = "content://test/tree",
                displayName = "测试文件夹",
            ),
        )
        val documentId = database.documentDao().upsert(
            testDocumentEntity(uri = "content://docs/a.pdf"),
        )
        val courseId = database.categoryDao().insert(
            CategoryEntity(name = "课程资料", sortOrder = 0),
        )
        val examId = database.categoryDao().insert(
            CategoryEntity(name = "考试", sortOrder = 1),
        )

        database.categoryDao().add(DocumentCategoryEntity(documentId, courseId))
        database.categoryDao().add(DocumentCategoryEntity(documentId, examId))

        assertThat(database.categoryDao().observeCategoryIds(documentId).first())
            .containsExactly(courseId, examId)
    }

    @Test
    fun deletingSourceRemovesItsIndexedDocuments() = runTest {
        val source = ScanSourceEntity(
            id = 1,
            treeUri = "content://test/tree",
            displayName = "测试文件夹",
        )
        database.sourceDao().upsert(source)
        database.documentDao().upsert(testDocumentEntity(sourceId = source.id))

        database.sourceDao().delete(source)

        assertThat(database.documentDao().observeActive().first()).isEmpty()
    }

    @Test
    fun tokenOwnedRetryAndCancellationNeverOverwriteCompletedOrNewRun() = runTest {
        val dao = database.relationAnalysisDao()
        val store = RoomRelationAnalysisScheduleStore(database, now = { 500L })
        val base = RelationAnalysisStateEntity(
            status = RelationAnalysisStatus.COMPLETED,
            phase = RelationAnalysisPhase.SAVING,
            processedCount = 4,
            candidateCount = 2,
            failedCount = 0,
            generation = 2,
            errorMessage = null,
            updatedAt = 400L,
        )
        dao.upsertState(base)

        assertThat(store.cancelCurrentRun(2, "token")).isFalse()
        assertThat(dao.getState()?.status).isEqualTo(RelationAnalysisStatus.COMPLETED)

        dao.upsertState(
            base.copy(
                status = RelationAnalysisStatus.RUNNING,
                errorMessage = "run:token",
            ),
        )
        assertThat(
            store.queueCurrentRunForRetry(2, "token", "relation_analysis_io_failed"),
        ).isTrue()
        assertThat(dao.getState()?.status).isEqualTo(RelationAnalysisStatus.QUEUED)
        assertThat(dao.getState()?.errorMessage).isEqualTo("relation_analysis_io_failed")

        dao.upsertState(base.copy(status = RelationAnalysisStatus.FAILED))
        assertThat(RoomNameSimilarityStore(database).begin(2, "after-fail")).isFalse()
        assertThat(dao.getState()?.status).isEqualTo(RelationAnalysisStatus.FAILED)

        dao.upsertState(base.copy(status = RelationAnalysisStatus.QUEUED))
        val analysisStore = RoomNameSimilarityStore(database)
        assertThat(analysisStore.begin(2, "token-A")).isTrue()
        assertThat(analysisStore.begin(2, "token-B")).isTrue()
        assertThat(store.queueCurrentRunForRetry(2, "token-A", "late")).isFalse()
        assertThat(dao.getState()?.errorMessage).isEqualTo("run:token-B")
    }

    @Test
    fun migration2To3CreatesResumableScanTablesAndPreservesSources() {
        migrationHelper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO scan_sources(
                    id, treeUri, displayName, permissionState, lastScanAt, sortOrder
                ) VALUES(1, 'content://test/tree', '测试文件夹', 'ACTIVE', 123, 0)
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            db.query(
                "SELECT treeUri, sourceKind FROM scan_sources WHERE id = 1",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("content://test/tree")
                assertThat(cursor.getString(1)).isEqualTo("TREE")
            }

            db.query("SELECT status, cursorId, checkedFiles FROM scan_sessions").close()
            db.query(
                "SELECT sessionId, directoryPath FROM scan_visited_directories",
            ).close()
            db.query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'index'
                    AND name = 'index_scan_visited_directories_sessionId'
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
            }
        }
    }

    @Test
    fun migration4To5PreservesRelationsAndCreatesAnalysisTables() {
        migrationHelper.createDatabase(TEST_DB, 4).use { db ->
            insertVersion4Fixture(db)
        }

        migrationHelper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).use { db ->
            assertTableCount(db, "documents", 2)
            assertTableCount(db, "document_categories", 1)
            assertTableCount(db, "exact_duplicate_sets", 1)
            assertTableCount(db, "exact_duplicate_members", 2)
            assertTableCount(db, "trash_records", 1)
            assertTableCount(db, "document_fingerprints", 0)
            assertTableCount(db, "similarity_candidates", 0)
            assertTableCount(db, "similarity_feedback", 0)
            assertTableCount(db, "ignored_similarity_groups", 0)
            assertTableCount(db, "relation_analysis_state", 0)

            db.query(
                """
                SELECT id, confirmedDocumentId, referenceDocumentId, confidenceBand,
                       analysisVersion, reviewState
                FROM name_similarity_groups
                WHERE id = 7
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(7)
                assertThat(cursor.getLong(1)).isEqualTo(2)
                assertThat(cursor.getLong(2)).isEqualTo(2)
                assertThat(cursor.getString(3)).isEqualTo("HIGH")
                assertThat(cursor.getInt(4)).isEqualTo(1)
                assertThat(cursor.getString(5)).isEqualTo("PENDING")
            }
            db.query(
                "SELECT confirmedDocumentId, referenceDocumentId FROM name_similarity_groups WHERE id = 8",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.isNull(0)).isTrue()
                assertThat(cursor.getLong(1)).isEqualTo(1)
            }
            db.query(
                "SELECT referenceDocumentId FROM name_similarity_groups WHERE id = 9",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.isNull(0)).isTrue()
            }
            db.query(
                "SELECT groupId, documentId, score, evidenceCodes FROM name_similarity_members ORDER BY documentId",
            ).use { cursor ->
                assertThat(cursor.count).isEqualTo(3)
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(7)
                assertThat(cursor.getInt(2)).isEqualTo(0)
                assertThat(cursor.getString(3)).isEqualTo("[]")
            }
        }
    }

    @Test
    fun migration4To5AllowsIndependentGroupsWithTheSameNameAndFamily() {
        migrationHelper.createDatabase(TEST_DB, 4).use { db ->
            insertVersion4Fixture(db)
        }

        migrationHelper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).use { db ->
            db.execSQL(
                """
                INSERT INTO name_similarity_groups(
                    id, baseName, extension, confirmedDocumentId, referenceDocumentId,
                    confidenceBand, analysisVersion, reviewState, updatedAt
                ) VALUES(10, '实习报告', 'word', NULL, 1, 'HIGH', 1, 'PENDING', 300)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO name_similarity_members(
                    groupId, documentId, differenceSegments, score, evidenceCodes
                ) VALUES(10, 1, '[]', 88, '["CORE_EXACT"]')
                """.trimIndent(),
            )

            assertTableCount(db, "name_similarity_groups", 4)
            assertTableCount(db, "name_similarity_members", 4)
            db.query(
                "SELECT COUNT(*) FROM name_similarity_groups WHERE baseName = '实习报告' AND extension = 'word'",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(2)
            }
        }
    }

    @Test
    fun migration5To6PreservesExistingDataAndCreatesPendingOperations() {
        migrationHelper.createDatabase(TEST_DB, 5).use { db ->
            insertVersion5Fixture(db)
            db.execSQL(
                "INSERT INTO ignored_similarity_groups(groupFingerprint, ignoredAt) VALUES('legacy', 12)",
            )
        }

        migrationHelper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6).use { db ->
            assertTableCount(db, "documents", 2)
            assertTableCount(db, "categories", 1)
            assertTableCount(db, "document_categories", 1)
            assertTableCount(db, "exact_duplicate_sets", 1)
            assertTableCount(db, "exact_duplicate_members", 2)
            assertTableCount(db, "trash_records", 1)
            assertTableCount(db, "trash_category_snapshots", 1)
            assertTableCount(db, "pending_trash_operations", 0)
            assertTableCount(db, "pending_trash_target_states", 0)
            assertTableCount(db, "document_identity_aliases", 0)
            db.query(
                "SELECT memberIds FROM ignored_similarity_groups WHERE groupFingerprint = 'legacy'",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("[]")
            }

            db.query("PRAGMA table_info(pending_trash_operations)").use { cursor ->
                var foundEligibilityColumn = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "eligibleTargetIds") {
                        foundEligibilityColumn = true
                        assertThat(cursor.getString(cursor.getColumnIndexOrThrow("type"))).isEqualTo("TEXT")
                        assertThat(cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))).isEqualTo(1)
                        assertThat(cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))).isEqualTo("'[]'")
                    }
                }
                assertThat(foundEligibilityColumn).isTrue()
            }
            db.execSQL(
                """
                INSERT INTO pending_trash_operations(id, action, targetKind, targetIds, status, createdAt, updatedAt)
                VALUES(99, 'TRASH', 'DOCUMENT', '[1]', 'AWAITING_CONFIRMATION', 1, 1)
                """.trimIndent(),
            )
            db.query("SELECT eligibleTargetIds FROM pending_trash_operations").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("[]")
            }
            db.query("SELECT seedSuccesses, seedFailures FROM pending_trash_operations").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("[]")
                assertThat(cursor.getString(1)).isEqualTo("[]")
            }
            db.execSQL(
                """
                INSERT INTO pending_trash_target_states(operationId, targetId, stage, documentUri, parentUri)
                VALUES(99, 1, 'STARTED', 'content://trash/a.docx', 'content://trash')
                """.trimIndent(),
            )
            db.query(
                "SELECT stage, documentUri, parentUri FROM pending_trash_target_states WHERE operationId = 99 AND targetId = 1",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("STARTED")
                assertThat(cursor.getString(1)).isEqualTo("content://trash/a.docx")
                assertThat(cursor.getString(2)).isEqualTo("content://trash")
            }
            db.execSQL("DELETE FROM pending_trash_operations WHERE id = 99")
            assertTableCount(db, "pending_trash_target_states", 0)

            db.query(
                """
                SELECT uri, displayName, normalizedName, sourceId, parentUri, contentHash,
                       indexStatus
                FROM documents
                WHERE id = 1
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("content://docs/a.docx")
                assertThat(cursor.getString(1)).isEqualTo("实习报告.docx")
                assertThat(cursor.getString(2)).isEqualTo("实习报告")
                assertThat(cursor.getLong(3)).isEqualTo(1L)
                assertThat(cursor.getString(4)).isEqualTo("content://docs")
                assertThat(cursor.getString(5)).isEqualTo("hash-a")
                assertThat(cursor.getString(6)).isEqualTo("ACTIVE")
            }
            db.query("SELECT name, sortOrder, createdAt FROM categories WHERE id = 3").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("实习")
                assertThat(cursor.getInt(1)).isEqualTo(0)
                assertThat(cursor.getLong(2)).isEqualTo(10L)
            }
            db.query(
                "SELECT documentId, categoryId FROM document_categories",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(1L)
                assertThat(cursor.getLong(1)).isEqualTo(3L)
            }
            db.query(
                """
                SELECT baseName, extension, confirmedDocumentId, referenceDocumentId,
                       confidenceBand, analysisVersion, reviewState, updatedAt
                FROM name_similarity_groups
                WHERE id = 7
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("实习报告")
                assertThat(cursor.getString(1)).isEqualTo("word")
                assertThat(cursor.getLong(2)).isEqualTo(2L)
                assertThat(cursor.getLong(3)).isEqualTo(2L)
                assertThat(cursor.getString(4)).isEqualTo("HIGH")
                assertThat(cursor.getInt(5)).isEqualTo(1)
                assertThat(cursor.getString(6)).isEqualTo("PENDING")
                assertThat(cursor.getLong(7)).isEqualTo(200L)
            }
            db.query(
                """
                SELECT differenceSegments, score, evidenceCodes
                FROM name_similarity_members
                WHERE groupId = 7 AND documentId = 1
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("[]")
                assertThat(cursor.getInt(1)).isEqualTo(0)
                assertThat(cursor.getString(2)).isEqualTo("[]")
            }
            db.query(
                """
                SELECT documentId, originalUri, trashedUri, trashedParentUri,
                       originalParentUri, deletedAt, expiresAt, storageKind
                FROM trash_records
                WHERE id = 11
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(1L)
                assertThat(cursor.getString(1)).isEqualTo("content://docs/a.docx")
                assertThat(cursor.getString(2)).isEqualTo("content://trash/a.docx")
                assertThat(cursor.getString(3)).isEqualTo("content://trash")
                assertThat(cursor.getString(4)).isEqualTo("content://docs")
                assertThat(cursor.getLong(5)).isEqualTo(250L)
                assertThat(cursor.getLong(6)).isEqualTo(999999L)
                assertThat(cursor.getString(7)).isEqualTo("APP_TRASH")
            }
            db.query(
                """
                SELECT trashRecordId, categoryId, categoryName
                FROM trash_category_snapshots
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(11L)
                assertThat(cursor.getLong(1)).isEqualTo(3L)
                assertThat(cursor.getString(2)).isEqualTo("实习")
            }
        }
    }

    @Test
    fun migrationReopenPersistsPendingSeedResults() = runTest {
        migrationHelper.createDatabase(TEST_DB, 5).use { db ->
            insertVersion5Fixture(db)
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrated = Room.databaseBuilder(context, WenxuDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            migrated.pendingTrashOperationDao().insert(
                pendingTrashOperation(PendingTrashStatus.AWAITING_CONFIRMATION, createdAt = 90).copy(
                    seedSuccesses = listOf(PendingTrashSeedSuccess(70, 7, 700)),
                    seedFailures = listOf(
                        PendingTrashSeedFailure(71, TrashFailureReason.VERIFICATION_FAILED),
                    ),
                ),
            )
        } finally {
            migrated.close()
        }

        val reopened = Room.databaseBuilder(context, WenxuDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        try {
            val pending = reopened.pendingTrashOperationDao().findUnfinished().single()
            assertThat(pending.seedSuccesses).containsExactly(PendingTrashSeedSuccess(70, 7, 700))
            assertThat(pending.seedFailures).containsExactly(
                PendingTrashSeedFailure(71, TrashFailureReason.VERIFICATION_FAILED),
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun migration1To6UsesTheCompleteNonDestructiveChain() {
        migrationHelper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO scan_sources(
                    id, treeUri, displayName, permissionState, lastScanAt, sortOrder
                ) VALUES(1, 'content://legacy/tree', '旧文件夹', 'ACTIVE', 10, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO documents(
                    id, uri, displayName, normalizedName, mimeType, extension, sizeBytes,
                    modifiedAt, lastOpenedAt, sourceId, parentUri, contentHash, hashBasisSize,
                    hashBasisModifiedAt, indexStatus, lastSeenScanId
                ) VALUES(1, 'content://legacy/a.pdf', '旧报告.pdf', '旧报告',
                         'application/pdf', 'pdf', 20, 30, NULL, 1, 'content://legacy',
                         NULL, NULL, NULL, 'ACTIVE', 'legacy-scan')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO trash_records(
                    id, documentId, originalUri, trashedUri, originalParentUri,
                    deletedAt, expiresAt
                ) VALUES(1, 1, 'content://legacy/a.pdf', 'content://trash/a.pdf',
                         'content://legacy', 40, 50)
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
        ).use { db ->
            assertTableCount(db, "documents", 1)
            assertTableCount(db, "trash_records", 1)
            assertTableCount(db, "pending_trash_operations", 0)
            db.query("SELECT sourceKind FROM scan_sources WHERE id = 1").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("TREE")
            }
            db.query(
                "SELECT trashedParentUri, storageKind FROM trash_records WHERE id = 1",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEmpty()
                assertThat(cursor.getString(1)).isEqualTo("APP_TRASH")
            }
        }
    }

    @Test
    fun evidenceCodesUseStableSortedJson() {
        val converters = WenxuTypeConverters()

        val json = converters.evidenceCodesToJson(
            linkedSetOf(EvidenceCode.SAME_FOLDER, EvidenceCode.CORE_EXACT),
        )

        assertThat(json).isEqualTo("[\"CORE_EXACT\",\"SAME_FOLDER\"]")
        assertThat(converters.jsonToEvidenceCodes(json))
            .containsExactly(EvidenceCode.CORE_EXACT, EvidenceCode.SAME_FOLDER)
    }

    @Test
    fun pendingTrashConvertersRoundTripStableValues() {
        val converters = WenxuTypeConverters()

        assertThat(converters.stringToPendingTrashAction("RESTORE"))
            .isEqualTo(PendingTrashAction.RESTORE)
        assertThat(converters.pendingTrashActionToString(PendingTrashAction.DELETE_FOREVER))
            .isEqualTo("DELETE_FOREVER")
        assertThat(converters.stringToPendingTrashTargetKind("TRASH_RECORD"))
            .isEqualTo(PendingTrashTargetKind.TRASH_RECORD)
        assertThat(converters.pendingTrashTargetKindToString(PendingTrashTargetKind.DOCUMENT))
            .isEqualTo("DOCUMENT")
        assertThat(converters.stringToPendingTrashStatus("CONFIRMED"))
            .isEqualTo(PendingTrashStatus.CONFIRMED)
        assertThat(converters.pendingTrashStatusToString(PendingTrashStatus.COMPLETED))
            .isEqualTo("COMPLETED")
        assertThat(converters.stringToTrashStorageKind("MEDIA_STORE"))
            .isEqualTo(TrashStorageKind.MEDIA_STORE)
        assertThat(converters.trashStorageKindToString(TrashStorageKind.APP_TRASH))
            .isEqualTo("APP_TRASH")
    }

    @Test
    fun targetIdsUseSortedLongJsonAndDeduplicateOnRead() {
        val converters = WenxuTypeConverters()

        assertThat(converters.targetIdsToJson(setOf(Long.MAX_VALUE, 7L, 2L)))
            .isEqualTo("[2,7,9223372036854775807]")
        assertThat(converters.jsonToLongSet("[9,2,9,9223372036854775807]"))
            .containsExactly(9L, 2L, Long.MAX_VALUE)
    }

    @Test
    fun targetIdsRejectJsonValuesThatAreNotIntegralLongs() {
        val converters = WenxuTypeConverters()

        assertThrows(IllegalArgumentException::class.java) {
            converters.jsonToLongSet("[\"7\"]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            converters.jsonToLongSet("[7.5]")
        }
    }

    @Test
    fun pendingTrashOperationDaoPersistsAndFindsOperation() = runTest {
        val operation = PendingTrashOperationEntity(
            action = PendingTrashAction.TRASH,
            targetKind = PendingTrashTargetKind.DOCUMENT,
            targetIds = setOf(8L, 3L),
            eligibleTargetIds = setOf(3L),
            fallbackParentUri = "content://fallback",
            status = PendingTrashStatus.AWAITING_CONFIRMATION,
            createdAt = 100L,
            updatedAt = 100L,
        )

        val id = database.pendingTrashOperationDao().insert(operation)

        assertThat(database.pendingTrashOperationDao().findById(id))
            .isEqualTo(operation.copy(id = id))

        val consumed = operation.copy(id = id, targetIds = setOf(8L), eligibleTargetIds = emptySet(), updatedAt = 200L)
        assertThat(database.pendingTrashOperationDao().update(consumed)).isEqualTo(1)
        assertThat(database.pendingTrashOperationDao().findById(id)).isEqualTo(consumed)
    }

    @Test
    fun roomTrashStoreConsumesBothTargetSetsAndDeletesFinishedOperation() = runTest {
        val store = RoomTrashStore(database)
        val operation = pendingTrashOperation(PendingTrashStatus.CONFIRMED, createdAt = 1L).copy(
            targetIds = setOf(3L, 8L, 99L),
            eligibleTargetIds = setOf(3L, 8L),
        )
        val id = store.createPending(operation)

        store.commitPendingTarget(id, 99L, updatedAt = 2L) {}
        assertThat(store.findPending(id)?.targetIds).containsExactly(3L, 8L)
        assertThat(store.findPending(id)?.eligibleTargetIds).containsExactly(3L, 8L)

        val beforeFailure = store.findPending(id)
        val failed = runCatching {
            store.commitPendingTarget(id, 3L, updatedAt = 3L) {
                store.updatePendingStatus(id, PendingTrashStatus.COMPLETED, updatedAt = 3L)
                error("metadata commit failed")
            }
        }
        assertThat(failed.isFailure).isTrue()
        assertThat(store.findPending(id)).isEqualTo(beforeFailure)

        store.commitPendingTarget(id, 3L, updatedAt = 4L) {}
        assertThat(store.findPending(id)?.targetIds).containsExactly(8L)
        assertThat(store.findPending(id)?.eligibleTargetIds).containsExactly(8L)
        store.commitPendingTarget(id, 8L, updatedAt = 5L) {}
        assertThat(store.findPending(id)).isNull()
    }

    @Test
    fun pendingTrashOperationDaoFindsOnlyUnfinishedInCreationOrder() = runTest {
        val dao = database.pendingTrashOperationDao()
        val confirmedId = dao.insert(
            pendingTrashOperation(
                status = PendingTrashStatus.CONFIRMED,
                createdAt = 20L,
            ),
        )
        val cancelledId = dao.insert(
            pendingTrashOperation(
                status = PendingTrashStatus.CANCELLED,
                createdAt = 10L,
            ),
        )
        val awaitingId = dao.insert(
            pendingTrashOperation(
                status = PendingTrashStatus.AWAITING_CONFIRMATION,
                createdAt = 30L,
            ),
        )
        dao.insert(
            pendingTrashOperation(
                status = PendingTrashStatus.COMPLETED,
                createdAt = 5L,
            ),
        )

        assertThat(dao.findUnfinished().map { Triple(it.id, it.status, it.createdAt) })
            .containsExactly(
                Triple(cancelledId, PendingTrashStatus.CANCELLED, 10L),
                Triple(confirmedId, PendingTrashStatus.CONFIRMED, 20L),
                Triple(awaitingId, PendingTrashStatus.AWAITING_CONFIRMATION, 30L),
            )
            .inOrder()
    }

    @Test
    fun roomTrashStoreCancellationAtomicallyRemovesOperationAndTargetJournal() = runTest {
        val store = RoomTrashStore(database)
        val operationId = store.createPending(
            pendingTrashOperation(PendingTrashStatus.CONFIRMED, createdAt = 10L).copy(
                targetIds = setOf(8L),
                eligibleTargetIds = setOf(8L),
            ),
        )
        store.saveTargetState(
            PendingTrashTargetStateEntity(
                operationId = operationId,
                targetId = 8L,
                stage = PendingTrashTargetStage.STARTED,
                documentUri = "content://trash/8.pdf",
                parentUri = "content://trash",
            ),
        )

        store.cancelPending(operationId, updatedAt = 20L)

        assertThat(store.findPending(operationId)).isNull()
        assertThat(store.findTargetStates(operationId)).isEmpty()
        assertThat(database.pendingTrashOperationDao().findUnfinished()).isEmpty()
    }

    @Test
    fun roomTrashStoreRestoresCanonicalDocumentAcrossExactUriScanCollision() = runTest {
        val source = ScanSourceEntity(
            id = 1L,
            treeUri = "content://tree/root",
            displayName = "Root",
        )
        database.sourceDao().upsert(source)
        val documentDao = database.documentDao()
        val categoryDao = database.categoryDao()
        val relationDao = database.relationDao()
        val store = RoomTrashStore(database)
        val originalUri = "content://tree/original.pdf"
        val trashUri = "content://tree/trash/original.pdf"
        val restoredUri = "content://tree/restored/original.pdf"
        val restoredParentUri = "content://tree/restored"
        val originalId = documentDao.upsert(
            testDocumentEntity(uri = originalUri).copy(
                sourceId = source.id,
                parentUri = "content://tree",
            ),
        )
        val original = checkNotNull(documentDao.findById(originalId))
        val categoryId = categoryDao.insert(CategoryEntity(name = "保留分类", sortOrder = 0, createdAt = 1L))
        categoryDao.add(DocumentCategoryEntity(originalId, categoryId))
        val originalNameGroupId = relationDao.upsertNameGroup(
            NameSimilarityGroupEntity(
                baseName = "原记录关系",
                extension = "pdf",
                confirmedDocumentId = originalId,
                referenceDocumentId = originalId,
                reviewState = SimilarityReviewState.REVIEWED,
                updatedAt = 1L,
            ),
        )
        relationDao.insertNameMembers(
            listOf(NameSimilarityMemberEntity(originalNameGroupId, originalId, emptyList())),
        )
        val originalExactSetId = relationDao.insertExactSet(
            ExactDuplicateSetEntity(contentHash = "original-hash", sizeBytes = original.sizeBytes, updatedAt = 1L),
        )
        relationDao.insertExactMembers(listOf(ExactDuplicateMemberEntity(originalExactSetId, originalId)))
        val trashId = store.recordTrash(
            original,
            TrashLocation(trashUri, "content://tree/trash"),
            deletedAt = 2L,
            expiresAt = 3L,
        )
        val expected = checkNotNull(store.findEntry(trashId))
        val collisionId = documentDao.upsert(
            testDocumentEntity(uri = restoredUri).copy(
                displayName = "扫描占位.pdf",
                normalizedName = "扫描占位",
                sourceId = source.id,
                parentUri = restoredParentUri,
                indexStatus = DocumentIndexStatus.ACTIVE,
            ),
        )
        val collisionCategoryId = categoryDao.insert(
            CategoryEntity(name = "占位记录分类", sortOrder = 1, createdAt = 2L),
        )
        categoryDao.add(DocumentCategoryEntity(collisionId, collisionCategoryId))
        val collisionNameGroupId = relationDao.upsertNameGroup(
            NameSimilarityGroupEntity(
                baseName = "占位记录关系",
                extension = "pdf",
                confirmedDocumentId = collisionId,
                referenceDocumentId = collisionId,
                reviewState = SimilarityReviewState.REVIEWED,
                updatedAt = 2L,
            ),
        )
        relationDao.insertNameMembers(
            listOf(NameSimilarityMemberEntity(collisionNameGroupId, collisionId, emptyList())),
        )
        val collisionExactSetId = relationDao.insertExactSet(
            ExactDuplicateSetEntity(contentHash = "collision-hash", sizeBytes = original.sizeBytes, updatedAt = 2L),
        )
        relationDao.insertExactMembers(listOf(ExactDuplicateMemberEntity(collisionExactSetId, collisionId)))
        val allowOtherId = documentDao.upsert(testDocumentEntity(uri = "content://tree/allow-other.pdf"))
        val blockOtherId = documentDao.upsert(testDocumentEntity(uri = "content://tree/block-other.pdf"))
        relationDao.insertNameMembers(
            listOf(NameSimilarityMemberEntity(collisionNameGroupId, allowOtherId, emptyList())),
        )
        val analysisDao = database.relationAnalysisDao()
        analysisDao.upsertFeedback(
            listOf(
                SimilarityFeedbackEntity.normalized(
                    collisionId, allowOtherId, SimilarityFeedbackDecision.ALLOW, createdAt = 3L, updatedAt = 4L,
                ),
                SimilarityFeedbackEntity.normalized(
                    collisionId, blockOtherId, SimilarityFeedbackDecision.ALLOW, createdAt = 5L, updatedAt = 6L,
                ),
                SimilarityFeedbackEntity.normalized(
                    originalId, blockOtherId, SimilarityFeedbackDecision.BLOCK, createdAt = 2L, updatedAt = 7L,
                ),
                SimilarityFeedbackEntity.normalized(
                    originalId, collisionId, SimilarityFeedbackDecision.BLOCK, createdAt = 8L, updatedAt = 9L,
                ),
            ),
        )
        analysisDao.upsertIgnoredGroup(
            IgnoredSimilarityGroupEntity(
                groupFingerprint = similarityGroupFingerprint(setOf(collisionId, allowOtherId)),
                ignoredAt = 10L,
                memberIds = setOf(collisionId, allowOtherId),
            ),
        )
        val operationId = store.createPending(
            pendingTrashOperation(PendingTrashStatus.CONFIRMED, createdAt = 4L).copy(
                targetIds = setOf(trashId),
                eligibleTargetIds = setOf(trashId),
            ),
        )
        store.saveTargetState(
            PendingTrashTargetStateEntity(
                operationId = operationId,
                targetId = trashId,
                stage = PendingTrashTargetStage.RESTORED,
                documentUri = restoredUri,
                parentUri = restoredParentUri,
            ),
        )

        val outcome = store.commitPendingTarget(operationId, trashId, updatedAt = 5L) {
            store.restoreMetadata(expected, restoredUri, restoredParentUri)
        }

        assertThat(outcome).isEqualTo(TrashMetadataChange.CHANGED)
        assertThat(documentDao.findById(originalId)?.uri).isEqualTo(restoredUri)
        assertThat(documentDao.findById(originalId)?.indexStatus).isEqualTo(DocumentIndexStatus.ACTIVE)
        assertThat(documentDao.findById(collisionId)).isNull()
        assertThat(database.documentIdentityAliasDao().listAll()).containsExactly(
            com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity(collisionId, originalId),
        )
        assertThat(categoryDao.observeCategoryIds(originalId).first())
            .containsExactly(categoryId, collisionCategoryId)
        val nameGroups = relationDao.listNameGroups().associateBy { it.id }
        assertThat(nameGroups.getValue(originalNameGroupId).confirmedDocumentId).isEqualTo(originalId)
        assertThat(nameGroups.getValue(originalNameGroupId).referenceDocumentId).isEqualTo(originalId)
        assertThat(nameGroups.getValue(originalNameGroupId).reviewState).isEqualTo(SimilarityReviewState.REVIEWED)
        assertThat(nameGroups.getValue(collisionNameGroupId).confirmedDocumentId).isEqualTo(originalId)
        assertThat(nameGroups.getValue(collisionNameGroupId).referenceDocumentId).isEqualTo(originalId)
        assertThat(nameGroups.getValue(collisionNameGroupId).reviewState).isEqualTo(SimilarityReviewState.REVIEWED)
        assertThat(relationDao.listNameMemberIds(originalNameGroupId)).containsExactly(originalId)
        assertThat(relationDao.listNameMemberIds(collisionNameGroupId))
            .containsExactly(originalId, allowOtherId)
        assertThat(relationDao.observeExactMembers().first())
            .containsExactly(
                ExactDuplicateMemberEntity(originalExactSetId, originalId),
                ExactDuplicateMemberEntity(collisionExactSetId, originalId),
            )
        assertThat(analysisDao.listFeedback()).containsExactly(
            SimilarityFeedbackEntity.normalized(
                originalId, allowOtherId, SimilarityFeedbackDecision.ALLOW, createdAt = 3L, updatedAt = 4L,
            ),
            SimilarityFeedbackEntity.normalized(
                originalId, blockOtherId, SimilarityFeedbackDecision.BLOCK, createdAt = 2L, updatedAt = 7L,
            ),
        )
        assertThat(
            analysisDao.isGroupIgnored(similarityGroupFingerprint(setOf(originalId, allowOtherId))),
        ).isTrue()
        assertThat(
            analysisDao.listIgnoredGroups().single {
                it.groupFingerprint == similarityGroupFingerprint(setOf(originalId, allowOtherId))
            }.memberIds,
        ).containsExactly(originalId, allowOtherId)
        assertThat(store.findEntry(trashId)).isNull()
        assertThat(store.findPending(operationId)).isNull()
        assertThat(store.findTargetStates(operationId)).isEmpty()
    }

    @Test
    fun migrationReopenPreservesLegacyHashOnlyIgnoreAcrossRestoreIdentityCollision() = runTest {
        val legacyFingerprint = similarityGroupFingerprint(setOf(9L, 1L))
        migrationHelper.createDatabase(TEST_DB, 5).use { db ->
            insertVersion5Fixture(db)
            db.execSQL(
                """
                INSERT INTO documents(
                    id, uri, displayName, normalizedName, mimeType, extension, sizeBytes,
                    modifiedAt, lastOpenedAt, sourceId, parentUri, contentHash, hashBasisSize,
                    hashBasisModifiedAt, indexStatus, lastSeenScanId
                ) VALUES(9, 'content://docs/restored-b.docx', '扫描占位.docx', '扫描占位',
                    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                    'docx', 100, 3000, NULL, 1, 'content://docs', NULL, NULL, NULL,
                    'ACTIVE', 'scan-2')
                """.trimIndent(),
            )
            db.execSQL(
                "UPDATE documents SET uri = 'content://trash/b.docx', parentUri = 'content://trash', " +
                    "indexStatus = 'TRASHED' WHERE id = 2",
            )
            db.execSQL(
                """
                INSERT INTO trash_records(
                    id, documentId, originalUri, trashedUri, trashedParentUri,
                    originalParentUri, deletedAt, expiresAt
                ) VALUES(12, 2, 'content://docs/restored-b.docx', 'content://trash/b.docx',
                    'content://trash', 'content://docs', 300, 999999)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO ignored_similarity_groups(groupFingerprint, ignoredAt) " +
                    "VALUES('$legacyFingerprint', 12)",
            )
            // The v5 group rows have already been cleared by an earlier analysis pass; only the hash remains.
            db.execSQL("DELETE FROM name_similarity_members")
            db.execSQL("DELETE FROM name_similarity_groups")
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val reopened = Room.databaseBuilder(context, WenxuDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val trashStore = RoomTrashStore(reopened)
            val entry = checkNotNull(trashStore.findEntry(12))

            assertThat(trashStore.restoreMetadata(
                entry,
                restoredUri = "content://docs/restored-b.docx",
                restoredParentUri = "content://docs",
            )).isEqualTo(TrashMetadataChange.CHANGED)
            assertThat(reopened.documentIdentityAliasDao().listAll()).containsExactly(
                com.wenxu.app.core.database.entity.DocumentIdentityAliasEntity(9, 2),
            )

            reopened.relationAnalysisDao().upsertState(
                RelationAnalysisStateEntity(
                    status = RelationAnalysisStatus.QUEUED,
                    phase = RelationAnalysisPhase.EXACT_DUPLICATES,
                    processedCount = 0,
                    candidateCount = 0,
                    failedCount = 0,
                    generation = 77,
                    updatedAt = 20,
                ),
            )
            val relationStore = RoomNameSimilarityStore(reopened)
            assertThat(relationStore.begin(77, "legacy-alias")).isTrue()
            assertThat(relationStore.replace(
                generation = 77,
                runToken = "legacy-alias",
                exactGroups = emptyList(),
                groups = listOf(
                    NameGroupDraft(
                        baseName = "实习报告",
                        extension = "docx",
                        members = listOf(
                            NameGroupMemberDraft(2, emptyList()),
                            NameGroupMemberDraft(1, emptyList()),
                        ),
                    ),
                ),
                candidates = emptyList(),
                fingerprints = emptyList(),
                failedCount = 0,
            )).isTrue()
            assertThat(reopened.relationDao().listNameGroups()).isEmpty()
        } finally {
            reopened.close()
        }
    }

    @Test
    fun manualMergeClearsIgnoredFingerprintsForCurrentAndAliasedIdentities() = runTest {
        database.sourceDao().upsert(ScanSourceEntity(id = 1, treeUri = "content://tree", displayName = "测试"))
        val firstId = database.documentDao().upsert(
            testDocumentEntity(uri = "content://docs/first.docx").copy(sourceId = 1),
        )
        val secondId = database.documentDao().upsert(
            testDocumentEntity(uri = "content://docs/second.docx").copy(sourceId = 1),
        )
        database.documentIdentityAliasDao().upsert(DocumentIdentityAliasEntity(9, firstId))
        val analysisDao = database.relationAnalysisDao()
        analysisDao.upsertIgnoredGroup(
            IgnoredSimilarityGroupEntity(
                similarityGroupFingerprint(setOf(firstId, secondId)),
                ignoredAt = 1,
            ),
        )
        analysisDao.upsertIgnoredGroup(
            IgnoredSimilarityGroupEntity(
                similarityGroupFingerprint(setOf(9, secondId)),
                ignoredAt = 2,
            ),
        )

        val saved = RoomSimilarityFeedbackStore(database).mergeVersions(
            MergeVersionsRequest(setOf(firstId, secondId), timestamp = 10),
        ) as FeedbackPersistenceResult.Saved

        assertThat(analysisDao.listIgnoredGroups()).isEmpty()
        val relationStore = RoomNameSimilarityStore(database)
        assertThat(relationStore.begin(checkNotNull(saved.generation), "manual-merge")).isTrue()
        assertThat(relationStore.replace(
            generation = checkNotNull(saved.generation),
            runToken = "manual-merge",
            exactGroups = emptyList(),
            groups = listOf(
                NameGroupDraft(
                    baseName = "报告",
                    extension = "docx",
                    members = listOf(
                        NameGroupMemberDraft(firstId, emptyList()),
                        NameGroupMemberDraft(secondId, emptyList()),
                    ),
                ),
            ),
            candidates = emptyList(),
            fingerprints = emptyList(),
            failedCount = 0,
        )).isTrue()
        assertThat(database.relationDao().listNameGroups()).hasSize(1)
    }

    @Test
    fun manualMergeDeletesMoreThanSqliteVariableLimitInOneTransaction() = runTest {
        database.sourceDao().upsert(ScanSourceEntity(id = 1, treeUri = "content://tree", displayName = "测试"))
        val firstId = database.documentDao().upsert(
            testDocumentEntity(uri = "content://docs/chunk-first.docx").copy(sourceId = 1),
        )
        val secondId = database.documentDao().upsert(
            testDocumentEntity(uri = "content://docs/chunk-second.docx").copy(sourceId = 1),
        )
        val aliases = (10_000L until 11_000L).map { oldId ->
            DocumentIdentityAliasEntity(oldId, firstId)
        }
        aliases.forEach { database.documentIdentityAliasDao().upsert(it) }
        val fingerprints = ignoredFingerprintCandidates(setOf(firstId, secondId), aliases)
        assertThat(fingerprints.size).isGreaterThan(999)
        fingerprints.forEach { fingerprint ->
            database.relationAnalysisDao().upsertIgnoredGroup(
                IgnoredSimilarityGroupEntity(fingerprint, ignoredAt = 1),
            )
        }

        val result = RoomSimilarityFeedbackStore(database).mergeVersions(
            MergeVersionsRequest(setOf(firstId, secondId), timestamp = 10),
        )

        assertThat(result).isInstanceOf(FeedbackPersistenceResult.Saved::class.java)
        assertThat(database.relationAnalysisDao().listIgnoredGroups()).isEmpty()
    }

    @Test
    fun pendingTrashOperationDaoUpdatesStatusAndDeletes() = runTest {
        val dao = database.pendingTrashOperationDao()
        val id = dao.insert(
            pendingTrashOperation(
                status = PendingTrashStatus.AWAITING_CONFIRMATION,
                createdAt = 40L,
            ).copy(
                seedSuccesses = listOf(PendingTrashSeedSuccess(90, 9, 900)),
                seedFailures = listOf(PendingTrashSeedFailure(91, TrashFailureReason.VERIFICATION_FAILED)),
            ),
        )

        assertThat(
            dao.updateStatus(id, PendingTrashStatus.CONFIRMED, updatedAt = 50L),
        ).isEqualTo(1)
        assertThat(dao.findById(id)?.status).isEqualTo(PendingTrashStatus.CONFIRMED)
        assertThat(dao.findById(id)?.updatedAt).isEqualTo(50L)
        assertThat(dao.findById(id)?.seedSuccesses).containsExactly(PendingTrashSeedSuccess(90, 9, 900))
        assertThat(dao.findById(id)?.seedFailures).containsExactly(
            PendingTrashSeedFailure(91, TrashFailureReason.VERIFICATION_FAILED),
        )
        assertThat(dao.deleteById(id)).isEqualTo(1)
        assertThat(dao.findById(id)).isNull()
    }

    @Test
    fun staleGenerationCannotReplaceAnyRelationAnalysisTable() = runTest {
        database.sourceDao().upsert(
            ScanSourceEntity(id = 1, treeUri = "content://tree", displayName = "测试"),
        )
        val firstId = database.documentDao().upsert(testDocumentEntity(uri = "content://docs/1.docx"))
        val secondId = database.documentDao().upsert(testDocumentEntity(uri = "content://docs/2.docx"))
        val relationDao = database.relationDao()
        val analysisDao = database.relationAnalysisDao()
        val exactSetId = relationDao.insertExactSet(
            ExactDuplicateSetEntity(contentHash = "old-hash", sizeBytes = 10, updatedAt = 1),
        )
        relationDao.insertExactMembers(
            listOf(
                ExactDuplicateMemberEntity(exactSetId, firstId),
                ExactDuplicateMemberEntity(exactSetId, secondId),
            ),
        )
        val nameGroupId = relationDao.upsertNameGroup(
            NameSimilarityGroupEntity(baseName = "旧组", extension = "docx", updatedAt = 1),
        )
        relationDao.insertNameMembers(
            listOf(
                NameSimilarityMemberEntity(nameGroupId, firstId, emptyList()),
                NameSimilarityMemberEntity(nameGroupId, secondId, emptyList()),
            ),
        )
        analysisDao.upsertFingerprint(
            DocumentFingerprintEntity(
                documentId = firstId,
                algorithmVersion = 1,
                textMinHash = byteArrayOf(1),
                normalizedTextLength = 1,
                structureSignature = "0,0,0||",
                extractStatus = FingerprintExtractStatus.SUCCESS,
                basisSize = 10,
                basisModifiedAt = 1,
                updatedAt = 1,
            ),
        )
        analysisDao.upsertCandidates(
            listOf(
                SimilarityCandidateEntity.normalized(
                    firstDocumentId = firstId,
                    secondDocumentId = secondId,
                    nameScore = 1.0,
                    contentScore = 1.0,
                    metadataScore = 10,
                    confidenceBand = ConfidenceBand.HIGH,
                    evidenceCodes = setOf(EvidenceCode.CORE_EXACT),
                    analysisVersion = 1,
                    updatedAt = 1,
                ),
            ),
        )
        analysisDao.upsertState(
            RelationAnalysisStateEntity(
                status = RelationAnalysisStatus.RUNNING,
                phase = RelationAnalysisPhase.NAME_CANDIDATES,
                processedCount = 0,
                candidateCount = 0,
                failedCount = 0,
                generation = 2,
                errorMessage = "run:current",
                updatedAt = 2,
            ),
        )

        val saved = RoomNameSimilarityStore(database).replace(
            generation = 1,
            runToken = "stale",
            exactGroups = listOf(ExactDuplicateGroup("new-hash", 20, listOf(firstId, secondId))),
            groups = listOf(
                NameGroupDraft(
                    baseName = "新组",
                    extension = "docx",
                    members = listOf(
                        NameGroupMemberDraft(firstId, emptyList()),
                        NameGroupMemberDraft(secondId, emptyList()),
                    ),
                ),
            ),
            candidates = emptyList(),
            fingerprints = emptyList(),
            failedCount = 0,
        )

        assertThat(saved).isFalse()
        val sql = database.openHelper.readableDatabase
        assertTableCount(sql, "exact_duplicate_sets", 1)
        assertTableCount(sql, "document_fingerprints", 1)
        assertTableCount(sql, "similarity_candidates", 1)
        assertTableCount(sql, "name_similarity_groups", 1)
        sql.query("SELECT contentHash FROM exact_duplicate_sets").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("old-hash")
        }
        sql.query("SELECT baseName FROM name_similarity_groups").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("旧组")
        }
    }

    @Test
    fun newerRunTokenCompletesAllRelationTablesAndFencesOlderToken() = runTest {
        database.sourceDao().upsert(
            ScanSourceEntity(id = 1, treeUri = "content://tree", displayName = "测试"),
        )
        val firstId = database.documentDao().upsert(
            testDocumentEntity(uri = "content://docs/token-1.docx"),
        )
        val secondId = database.documentDao().upsert(
            testDocumentEntity(uri = "content://docs/token-2.docx"),
        )
        val analysisDao = database.relationAnalysisDao()
        val store = RoomNameSimilarityStore(database)
        val scheduleStore = RoomRelationAnalysisScheduleStore(database)
        analysisDao.upsertState(
            RelationAnalysisStateEntity(
                status = RelationAnalysisStatus.QUEUED,
                phase = RelationAnalysisPhase.EXACT_DUPLICATES,
                processedCount = 0,
                candidateCount = 0,
                failedCount = 0,
                generation = 50,
                updatedAt = 1,
            ),
        )
        val candidate = SimilarityCandidateEntity.normalized(
            firstDocumentId = firstId,
            secondDocumentId = secondId,
            nameScore = 0.9,
            contentScore = 0.8,
            metadataScore = 80,
            confidenceBand = ConfidenceBand.HIGH,
            evidenceCodes = setOf(EvidenceCode.CORE_EXACT),
            analysisVersion = 1,
            updatedAt = 2,
        )
        val fingerprint = DocumentFingerprintEntity(
            documentId = firstId,
            algorithmVersion = 1,
            textMinHash = byteArrayOf(1, 2),
            normalizedTextLength = 10,
            structureSignature = "1,0,0||",
            extractStatus = FingerprintExtractStatus.SUCCESS,
            basisSize = 10,
            basisModifiedAt = 1,
            updatedAt = 2,
        )

        assertThat(store.begin(50, "token-A")).isTrue()
        assertThat(store.begin(50, "token-B")).isTrue()
        assertThat(
            store.replace(
                generation = 50,
                runToken = "token-B",
                exactGroups = listOf(
                    ExactDuplicateGroup("winner-hash", 10, listOf(firstId, secondId)),
                ),
                groups = listOf(
                    NameGroupDraft(
                        baseName = "胜出组",
                        extension = "docx",
                        members = listOf(
                            NameGroupMemberDraft(firstId, emptyList()),
                            NameGroupMemberDraft(secondId, emptyList()),
                        ),
                    ),
                ),
                candidates = listOf(candidate),
                fingerprints = listOf(fingerprint),
                failedCount = 0,
            ),
        ).isTrue()

        assertThat(store.phase(50, "token-A", RelationAnalysisPhase.SAVING, 9, 9, 9)).isFalse()
        assertThat(scheduleStore.queueCurrentRunForRetry(50, "token-A", "late")).isFalse()
        assertThat(scheduleStore.failCurrentRun(50, "token-A", "late")).isFalse()
        assertThat(scheduleStore.cancelCurrentRun(50, "token-A")).isFalse()
        assertThat(
            store.replace(
                generation = 50,
                runToken = "token-A",
                exactGroups = listOf(ExactDuplicateGroup("stale-hash", 1, emptyList())),
                groups = emptyList(),
                candidates = emptyList(),
                fingerprints = emptyList(),
                failedCount = 9,
            ),
        ).isFalse()

        assertThat(analysisDao.getState()?.status).isEqualTo(RelationAnalysisStatus.COMPLETED)
        assertThat(store.begin(50, "token-C")).isFalse()
        assertThat(database.relationDao().observeExactSets().first().single().contentHash)
            .isEqualTo("winner-hash")
        assertThat(database.relationDao().listNameGroups().single().baseName).isEqualTo("胜出组")
        assertThat(analysisDao.listCandidates()).containsExactly(candidate)
        assertThat(analysisDao.findFingerprint(firstId)?.textMinHash)
            .isEqualTo(byteArrayOf(1, 2))
    }

    @Test
    fun cancellationQueuesCurrentGenerationButNeverOverwritesNewerState() = runTest {
        val analysisDao = database.relationAnalysisDao()
        val store = RoomNameSimilarityStore(database)
        val scheduleStore = RoomRelationAnalysisScheduleStore(database)
        analysisDao.upsertState(
            RelationAnalysisStateEntity(
                status = RelationAnalysisStatus.RUNNING,
                phase = RelationAnalysisPhase.CONTENT_VERIFICATION,
                processedCount = 3,
                candidateCount = 5,
                failedCount = 1,
                generation = 7,
                errorMessage = "run:seven",
                updatedAt = 1,
            ),
        )

        scheduleStore.cancelCurrentRun(7, "seven")

        assertThat(analysisDao.getState()?.status).isEqualTo(RelationAnalysisStatus.QUEUED)
        analysisDao.upsertState(
            checkNotNull(analysisDao.getState()).copy(
                status = RelationAnalysisStatus.RUNNING,
                generation = 8,
                errorMessage = "run:eight",
                updatedAt = 2,
            ),
        )

        scheduleStore.cancelCurrentRun(7, "seven")

        assertThat(analysisDao.getState()?.generation).isEqualTo(8)
        assertThat(analysisDao.getState()?.status).isEqualTo(RelationAnalysisStatus.RUNNING)
    }

    @Test
    fun scanSessionPersistsProgressAndCountsUniqueDirectories() = runTest {
        database.sourceDao().upsert(
            ScanSourceEntity(
                id = 1,
                treeUri = "content://shared-storage",
                displayName = "全部文档",
            ),
        )
        database.scanSessionDao().upsert(
            ScanSessionEntity(
                id = "session-1",
                sourceId = 1,
                scanId = "scan-1",
                status = ScanSessionStatus.RUNNING,
                phase = ScanPhase.DISCOVERING,
                cursorId = 200,
                checkedFiles = 200,
                discoveredDocuments = 14,
                failedFiles = 0,
                startedAt = 100,
                updatedAt = 200,
            ),
        )
        database.scanVisitedDirectoryDao().insertAll(
            listOf(
                ScanVisitedDirectoryEntity("session-1", "Download/"),
                ScanVisitedDirectoryEntity("session-1", "Download/"),
                ScanVisitedDirectoryEntity("session-1", "Documents/"),
            ),
        )

        val latest = database.scanSessionDao().observeLatest().first()
        assertThat(latest?.cursorId).isEqualTo(200)
        assertThat(latest?.status).isEqualTo(ScanSessionStatus.RUNNING)
        assertThat(database.scanVisitedDirectoryDao().count("session-1")).isEqualTo(2)

        database.scanSessionDao().upsert(
            checkNotNull(latest).copy(
                status = ScanSessionStatus.PAUSED,
                updatedAt = 300,
            ),
        )

        assertThat(database.scanVisitedDirectoryDao().count("session-1")).isEqualTo(2)
    }

    private fun insertVersion4Fixture(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO scan_sources(
                id, treeUri, displayName, sourceKind, permissionState, lastScanAt, sortOrder
            ) VALUES(1, 'content://test/tree', '测试文件夹', 'TREE', 'ACTIVE', 123, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO documents(
                id, uri, displayName, normalizedName, mimeType, extension, sizeBytes,
                modifiedAt, lastOpenedAt, sourceId, parentUri, contentHash, hashBasisSize,
                hashBasisModifiedAt, indexStatus, lastSeenScanId
            ) VALUES
                (1, 'content://docs/a.docx', '实习报告.docx', '实习报告',
                 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                 'docx', 100, 1000, NULL, 1, 'content://docs', 'hash-a', 100, 1000,
                 'ACTIVE', 'scan-1'),
                (2, 'content://docs/b.docx', '实习报告_最终版.docx', '实习报告_最终版',
                 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                 'docx', 100, 2000, NULL, 1, 'content://docs', 'hash-a', 100, 2000,
                 'ACTIVE', 'scan-1')
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO categories(id, name, sortOrder, createdAt) VALUES(3, '实习', 0, 10)",
        )
        db.execSQL(
            "INSERT INTO document_categories(documentId, categoryId) VALUES(1, 3)",
        )
        db.execSQL(
            """
            INSERT INTO name_similarity_groups(
                id, baseName, extension, confirmedDocumentId, updatedAt
            ) VALUES(7, '实习报告', 'word', 2, 200)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO name_similarity_members(groupId, documentId, differenceSegments)
            VALUES(7, 1, '[]'), (7, 2, '[]')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO name_similarity_groups(
                id, baseName, extension, confirmedDocumentId, updatedAt
            ) VALUES(8, '课程作业', 'word', NULL, 150)
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO name_similarity_members(groupId, documentId, differenceSegments) VALUES(8, 1, '[]')",
        )
        db.execSQL(
            """
            INSERT INTO name_similarity_groups(
                id, baseName, extension, confirmedDocumentId, updatedAt
            ) VALUES(9, '空分组', 'word', NULL, 140)
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO exact_duplicate_sets(id, contentHash, sizeBytes, updatedAt) VALUES(9, 'hash-a', 100, 200)",
        )
        db.execSQL(
            "INSERT INTO exact_duplicate_members(setId, documentId) VALUES(9, 1), (9, 2)",
        )
        db.execSQL(
            """
            INSERT INTO trash_records(
                id, documentId, originalUri, trashedUri, trashedParentUri,
                originalParentUri, deletedAt, expiresAt
            ) VALUES(11, 1, 'content://docs/a.docx', 'content://trash/a.docx',
                     'content://trash', 'content://docs', 250, 999999)
            """.trimIndent(),
        )
    }

    private fun insertVersion5Fixture(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        insertVersion4Fixture(db)
        db.execSQL(
            "UPDATE name_similarity_groups SET referenceDocumentId = 2 WHERE id = 7",
        )
        db.execSQL(
            """
            INSERT INTO trash_category_snapshots(trashRecordId, categoryId, categoryName)
            VALUES(11, 3, '实习')
            """.trimIndent(),
        )
    }

    private fun pendingTrashOperation(
        status: PendingTrashStatus,
        createdAt: Long,
    ) = PendingTrashOperationEntity(
        action = PendingTrashAction.TRASH,
        targetKind = PendingTrashTargetKind.DOCUMENT,
        targetIds = setOf(createdAt),
        eligibleTargetIds = setOf(createdAt),
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun assertTableCount(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        expected: Int,
    ) {
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(expected)
        }
    }

    private companion object {
        const val TEST_DB = "wenxu-migration-test"
    }
}
