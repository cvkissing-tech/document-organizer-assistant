package com.wenxu.app.core.trash

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.PendingTrashAction
import com.wenxu.app.core.database.entity.ScanSourceKind
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashRequestPlannerTest {
    @Test
    fun api30PartitionsMixedSourcesBetweenSystemAndImmediate() {
        val shared = target(1, ScanSourceKind.SHARED_STORAGE)
        val tree = target(2, ScanSourceKind.TREE)
        val inbox = target(3, ScanSourceKind.INBOX)

        val plan = TrashRequestPlanner(apiLevel = 30).partition(listOf(shared, tree, inbox))

        assertThat(plan.system).containsExactly(shared)
        assertThat(plan.immediate).containsExactly(tree, inbox).inOrder()
        assertThat(plan.unsupported).isEmpty()
    }

    @Test
    fun api29NeverRoutesSharedStorageToImmediate() {
        val shared = target(1, ScanSourceKind.SHARED_STORAGE)
        val tree = target(2, ScanSourceKind.TREE)
        val inbox = target(3, ScanSourceKind.INBOX)

        val plan = TrashRequestPlanner(apiLevel = 29).partition(listOf(shared, tree, inbox))

        assertThat(plan.system).isEmpty()
        assertThat(plan.immediate).containsExactly(tree, inbox).inOrder()
        assertThat(plan.unsupported).containsExactly(shared)
    }

    @Test
    fun duplicateIdsKeepOnlyTheFirstTargetAndPreserveOrder() {
        val firstShared = target(7, ScanSourceKind.SHARED_STORAGE, "first")
        val tree = target(8, ScanSourceKind.TREE)
        val duplicateTree = target(7, ScanSourceKind.TREE, "duplicate")
        val inbox = target(9, ScanSourceKind.INBOX)

        val plan = TrashRequestPlanner(apiLevel = 30).partition(
            listOf(firstShared, tree, duplicateTree, inbox),
        )

        assertThat(plan.system).containsExactly(firstShared)
        assertThat(plan.immediate).containsExactly(tree, inbox).inOrder()
        assertThat(plan.unsupported).isEmpty()
    }

    @Test
    fun requestUrisAreDeduplicatedInFirstOccurrenceOrder() {
        val first = "content://media/external/file/10"
        val second = "content://media/external/file/20"

        val result = prepareMediaStoreUriStrings(listOf(first, second, first))

        assertThat(result.getOrThrow()).containsExactly(first, second).inOrder()
    }

    @Test
    fun requestUrisRejectEmptyAndNonMediaStoreInputs() {
        val empty = prepareMediaStoreUriStrings(emptyList())
        val nonMediaStore = prepareMediaStoreUriStrings(
            listOf("content://com.example.documents/document/10"),
        )

        assertThat(empty.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(empty.exceptionOrNull()).hasMessageThat().contains("MediaStore URI")
        assertThat(nonMediaStore.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(nonMediaStore.exceptionOrNull()).hasMessageThat().contains("not a MediaStore")
    }

    @Test
    fun requestUrisAcceptItemUrisAcrossVolumesAndSupportedCollections() {
        val validUris = listOf(
            "content://media/external/file/1",
            "content://media/external_primary/file/2",
            "content://media/internal/file/3",
            "content://media/0123-4567/file/4",
            "content://media/external/images/media/5",
            "content://media/external/video/media/6",
            "content://media/external/audio/media/7",
            "content://media/external/downloads/8",
        )

        val result = prepareMediaStoreUriStrings(validUris)

        assertThat(result.getOrThrow()).containsExactlyElementsIn(validUris).inOrder()
    }

    @Test
    fun requestUrisRejectCollectionsNonNumericIdsAndAbnormalPaths() {
        val invalidUris = listOf(
            "content://media/external/file",
            "content://media/external/images/media",
            "content://media/external/file/not-a-number",
            "content://media/external/file/1/extra",
            "content://media/external/downloads/0",
        )

        invalidUris.forEach { uri ->
            assertThat(prepareMediaStoreUriStrings(listOf(uri)).isFailure).isTrue()
        }
    }

    @Test
    fun verificationRulesMatchEachAction() {
        assertThat(verificationFor(PendingTrashAction.TRASH, MediaStoreRowState.TRASHED))
            .isEqualTo(MediaStoreVerification.MATCHED)
        assertThat(verificationFor(PendingTrashAction.TRASH, MediaStoreRowState.NOT_TRASHED))
            .isEqualTo(MediaStoreVerification.NOT_MATCHED)
        assertThat(verificationFor(PendingTrashAction.RESTORE, MediaStoreRowState.NOT_TRASHED))
            .isEqualTo(MediaStoreVerification.MATCHED)
        assertThat(verificationFor(PendingTrashAction.RESTORE, MediaStoreRowState.TRASHED))
            .isEqualTo(MediaStoreVerification.NOT_MATCHED)
        assertThat(verificationFor(PendingTrashAction.DELETE_FOREVER, MediaStoreRowState.MISSING))
            .isEqualTo(MediaStoreVerification.MATCHED)
        assertThat(verificationFor(PendingTrashAction.DELETE_FOREVER, MediaStoreRowState.TRASHED))
            .isEqualTo(MediaStoreVerification.NOT_MATCHED)
        assertThat(verificationFor(PendingTrashAction.DELETE_FOREVER, MediaStoreRowState.NOT_TRASHED))
            .isEqualTo(MediaStoreVerification.NOT_MATCHED)
        assertThat(verificationFor(PendingTrashAction.TRASH, MediaStoreRowState.UNKNOWN))
            .isEqualTo(MediaStoreVerification.UNKNOWN)
        assertThat(verificationFor(PendingTrashAction.RESTORE, MediaStoreRowState.MISSING))
            .isEqualTo(MediaStoreVerification.UNKNOWN)
    }

    @Test
    fun cursorFactsTreatMissingColumnsAndUnexpectedValuesAsUnknown() {
        assertThat(mediaStoreRowState(hasTrashedColumn = false, rowExists = false, trashedValue = null))
            .isEqualTo(MediaStoreRowState.UNKNOWN)
        assertThat(mediaStoreRowState(hasTrashedColumn = true, rowExists = false, trashedValue = null))
            .isEqualTo(MediaStoreRowState.MISSING)
        assertThat(mediaStoreRowState(hasTrashedColumn = true, rowExists = true, trashedValue = 1))
            .isEqualTo(MediaStoreRowState.TRASHED)
        assertThat(mediaStoreRowState(hasTrashedColumn = true, rowExists = true, trashedValue = 0))
            .isEqualTo(MediaStoreRowState.NOT_TRASHED)
        assertThat(mediaStoreRowState(hasTrashedColumn = true, rowExists = true, trashedValue = 2))
            .isEqualTo(MediaStoreRowState.UNKNOWN)
    }

    @Test
    fun verifierChecksEachDistinctUriOnceAndIsolatesFailures() = runTest {
        val first = "content://media/external/file/10"
        val failed = "content://media/external/file/20"
        val calls = mutableListOf<String>()
        val verifier = MediaStoreVerifier(apiLevel = 30) { uri ->
            calls += uri
            if (uri == failed) throw SecurityException("denied")
            MediaStoreRowState.TRASHED
        }

        val result = verifier.verify(PendingTrashAction.TRASH, listOf(first, failed, first))

        assertThat(result.keys).containsExactly(first, failed).inOrder()
        assertThat(result[first]).isEqualTo(MediaStoreVerification.MATCHED)
        assertThat(result[failed]).isEqualTo(MediaStoreVerification.UNKNOWN)
        assertThat(calls).containsExactly(first, failed).inOrder()
    }

    @Test
    fun verifierSkipsInvalidUrisAndContinuesWithValidMediaStoreUris() = runTest {
        val invalid = "not a uri"
        val otherProvider = "content://com.example.documents/document/20"
        val collection = "content://media/external/file"
        val nonNumericId = "content://media/external/file/not-a-number"
        val valid = "content://media/external/file/30"
        val calls = mutableListOf<String>()
        val verifier = MediaStoreVerifier(apiLevel = 30) { uri ->
            calls += uri
            MediaStoreRowState.TRASHED
        }

        val result = verifier.verify(
            PendingTrashAction.TRASH,
            listOf(invalid, otherProvider, collection, nonNumericId, valid),
        )

        assertThat(result.keys)
            .containsExactly(invalid, otherProvider, collection, nonNumericId, valid)
            .inOrder()
        assertThat(result[invalid]).isEqualTo(MediaStoreVerification.UNKNOWN)
        assertThat(result[otherProvider]).isEqualTo(MediaStoreVerification.UNKNOWN)
        assertThat(result[collection]).isEqualTo(MediaStoreVerification.UNKNOWN)
        assertThat(result[nonNumericId]).isEqualTo(MediaStoreVerification.UNKNOWN)
        assertThat(result[valid]).isEqualTo(MediaStoreVerification.MATCHED)
        assertThat(calls).containsExactly(valid)
    }

    @Test
    fun deleteVerificationDoesNotTreatSkippedNonMediaStoreUriAsMissing() = runTest {
        val collection = "content://media/external/file"
        val calls = mutableListOf<String>()
        val verifier = MediaStoreVerifier(apiLevel = 30) { uri ->
            calls += uri
            MediaStoreRowState.MISSING
        }

        val result = verifier.verify(PendingTrashAction.DELETE_FOREVER, listOf(collection))

        assertThat(result[collection]).isEqualTo(MediaStoreVerification.UNKNOWN)
        assertThat(calls).isEmpty()
    }

    @Test
    fun verifierReturnsUnknownWithoutQueriesBelowApi30() = runTest {
        val first = "content://media/external/file/10"
        val second = "content://media/external/file/20"
        val calls = mutableListOf<String>()
        val verifier = MediaStoreVerifier(apiLevel = 29) { uri ->
            calls += uri
            MediaStoreRowState.TRASHED
        }

        val result = verifier.verify(PendingTrashAction.TRASH, listOf(first, second, first))

        assertThat(result.keys).containsExactly(first, second).inOrder()
        assertThat(result.values).containsExactly(
            MediaStoreVerification.UNKNOWN,
            MediaStoreVerification.UNKNOWN,
        ).inOrder()
        assertThat(calls).isEmpty()
    }

    @Test
    fun verifierRunsQueriesOnInjectedDispatcher() = runTest(UnconfinedTestDispatcher()) {
        val valid = "content://media/external/file/10"
        val dispatcher = StandardTestDispatcher(testScheduler)
        var queried = false
        val verifier = MediaStoreVerifier(apiLevel = 30, ioDispatcher = dispatcher) {
            queried = true
            MediaStoreRowState.TRASHED
        }

        val deferred = async { verifier.verify(PendingTrashAction.TRASH, listOf(valid)) }

        assertThat(queried).isFalse()
        testScheduler.runCurrent()
        assertThat(deferred.await()[valid]).isEqualTo(MediaStoreVerification.MATCHED)
    }

    @Test
    fun verifierStopsBeforeNextUriWhenCoroutineIsCancelled() =
        runTest(UnconfinedTestDispatcher()) {
            val first = "content://media/external/file/10"
            val second = "content://media/external/file/20"
            val dispatcher = StandardTestDispatcher(testScheduler)
            val calls = mutableListOf<String>()
            lateinit var deferred: kotlinx.coroutines.Deferred<Map<String, MediaStoreVerification>>
            val verifier = MediaStoreVerifier(apiLevel = 30, ioDispatcher = dispatcher) { uri ->
                calls += uri
                deferred.cancel()
                MediaStoreRowState.TRASHED
            }
            deferred = async(start = CoroutineStart.LAZY) {
                verifier.verify(PendingTrashAction.TRASH, listOf(first, second))
            }

            deferred.start()
            testScheduler.runCurrent()
            val failure = runCatching { deferred.await() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(calls).containsExactly(first)
        }

    private fun target(
        id: Long,
        sourceKind: ScanSourceKind,
        uriSuffix: String = id.toString(),
    ) = TrashTarget(
        id = id,
        uri = "content://media/external/file/$uriSuffix",
        sourceKind = sourceKind,
    )
}
