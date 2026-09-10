package com.wenxu.app.core.storage

import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import org.junit.Test

class DocumentDeletionTest {
    @Test
    fun alreadyMissingDocumentSucceedsWithoutDeletingAgain() {
        var deletes = 0
        val result = deleteDocumentIdempotently(
            query = { DocumentPresence.MISSING },
            delete = { deletes++; false },
        )
        assertThat(result.isSuccess).isTrue()
        assertThat(deletes).isEqualTo(0)
    }

    @Test
    fun confirmedDeletionCanBeRetriedAfterItsDatabaseCommitFails() {
        var present = true
        var deletes = 0
        val query = { if (present) DocumentPresence.PRESENT else DocumentPresence.MISSING }
        val delete = { deletes++; present = false; true }

        assertThat(deleteDocumentIdempotently(query, delete).isSuccess).isTrue()
        assertThat(deleteDocumentIdempotently(query, delete).isSuccess).isTrue()
        assertThat(deletes).isEqualTo(1)
    }

    @Test
    fun rejectedDeleteWhoseDocumentStillExistsIsKnownUnchanged() {
        val result = deleteDocumentIdempotently({ DocumentPresence.PRESENT }, { false })
        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentOperationUnchangedException::class.java)
    }

    @Test
    fun providerSecurityExceptionIsFailureAndNeverMeansMissing() {
        var deletes = 0
        val result = deleteDocumentIdempotently(
            query = { throw SecurityException("permission revoked") },
            delete = { deletes++; true },
        )
        assertThat(result.isFailure).isTrue()
        assertThat(deletes).isEqualTo(0)
    }

    @Test
    fun nullQueryResultNeverMeansMissing() {
        var deletes = 0
        val result = deleteDocumentIdempotently(
            query = { documentPresence(null) },
            delete = { deletes++; true },
        )
        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
        assertThat(deletes).isEqualTo(0)
    }

    @Test
    fun failedQueryAfterDeleteKeepsOutcomeUnknown() {
        var queries = 0
        val result = deleteDocumentIdempotently(
            query = {
                if (++queries == 1) DocumentPresence.PRESENT else throw SecurityException("permission changed")
            },
            delete = { true },
        )
        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
    }

    @Test
    fun deleteAcknowledgementWithoutConfirmedAbsenceCannotSucceed() {
        val result = deleteDocumentIdempotently({ DocumentPresence.PRESENT }, { true })
        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
    }

    @Test
    fun disappearanceDuringRejectedDeleteStillCompletesDesiredAbsence() {
        var queries = 0
        val result = deleteDocumentIdempotently(
            query = { if (++queries == 1) DocumentPresence.PRESENT else DocumentPresence.MISSING },
            delete = { false },
        )
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun queryOnlyProvesAbsenceWithExpectedColumnsAndCompleteResult() {
        assertThat(documentPresence(DocumentQuerySnapshot(true, false, false))).isEqualTo(DocumentPresence.MISSING)
        assertThat(documentPresence(DocumentQuerySnapshot(false, false, false))).isEqualTo(DocumentPresence.UNKNOWN)
        assertThat(documentPresence(DocumentQuerySnapshot(true, false, false, incomplete = true))).isEqualTo(DocumentPresence.UNKNOWN)
        assertThat(documentPresence(DocumentQuerySnapshot(true, true, false))).isEqualTo(DocumentPresence.UNKNOWN)
        assertThat(documentPresence(DocumentQuerySnapshot(true, true, true))).isEqualTo(DocumentPresence.PRESENT)
    }

    @Test
    fun parentListingOnlyProvesAbsenceWhenItsStableIdsAreComplete() {
        val complete = ParentDocumentQuerySnapshot(
            hasDocumentIdColumn = true,
            documentIds = setOf("other-id"),
        )

        assertThat(parentDocumentPresence(complete, "target-id")).isEqualTo(DocumentPresence.MISSING)
        assertThat(parentDocumentPresence(complete.copy(documentIds = setOf("target-id")), "target-id"))
            .isEqualTo(DocumentPresence.PRESENT)
        assertThat(parentDocumentPresence(null, "target-id")).isEqualTo(DocumentPresence.UNKNOWN)
        assertThat(parentDocumentPresence(complete.copy(hasDocumentIdColumn = false), "target-id"))
            .isEqualTo(DocumentPresence.UNKNOWN)
        assertThat(parentDocumentPresence(complete.copy(incomplete = true), "target-id"))
            .isEqualTo(DocumentPresence.UNKNOWN)
    }

    @Test
    fun parentFallbackRequiresEveryUriToUseTheExactSameAuthority() {
        assertThat(sameSafProviderAuthority(
            sourceTreeUri = "content://provider.one/tree/root",
            parentUri = "content://provider.one/document/parent",
            documentUri = "content://provider.one/document/target",
        )).isTrue()
        assertThat(sameSafProviderAuthority(
            sourceTreeUri = "content://provider.one/tree/root",
            parentUri = "content://provider.two/document/parent",
            documentUri = "content://provider.one/document/target",
        )).isFalse()
        assertThat(sameSafProviderAuthority(
            sourceTreeUri = "content://provider.one/tree/root",
            parentUri = "content://provider.one/document/parent",
            documentUri = "content://provider.two/document/target",
        )).isFalse()
    }

    @Test
    fun crossProviderParentFallbackStaysUnknownWithoutQueryingProvider() {
        var queried = false

        val presence = authorizedSafParentPresence(
            sourceTreeUri = "content://provider.one/tree/root",
            parentUri = "content://provider.two/document/parent",
            documentUri = "content://provider.one/document/target",
        ) {
            queried = true
            DocumentPresence.MISSING
        }

        assertThat(presence).isEqualTo(DocumentPresence.UNKNOWN)
        assertThat(queried).isFalse()
    }

    @Test
    fun nullStableIdMakesParentListingIncomplete() {
        val snapshot = parentDocumentQuerySnapshot(
            hasDocumentIdColumn = true,
            documentIds = listOf("other-id", null),
            providerIncomplete = false,
        )

        assertThat(parentDocumentPresence(snapshot, "target-id")).isEqualTo(DocumentPresence.UNKNOWN)
    }

    @Test
    fun unknownDirectAndParentQueriesNeverBecomeSuccessfulAbsence() {
        var deletes = 0

        val result = deleteDocumentIdempotently(
            query = {
                verifiedSafDocumentPresence(
                    directQuery = { DocumentPresence.MISSING },
                    parentQuery = { DocumentPresence.UNKNOWN },
                )
            },
            delete = { deletes++; true },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
        assertThat(deletes).isEqualTo(0)
    }

    @Test
    fun completedDirectAbsenceCanBeCorroboratedByCompleteParentAbsence() {
        val presence = verifiedSafDocumentPresence(
            directQuery = { DocumentPresence.MISSING },
            parentQuery = { DocumentPresence.MISSING },
        )

        assertThat(presence).isEqualTo(DocumentPresence.MISSING)
    }

    @Test
    fun ambiguousDirectFailureCannotBeUpgradedByParentAbsence() {
        val result = deleteDocumentIdempotently(
            query = {
                verifiedSafDocumentPresence(
                    directQuery = { throw IllegalArgumentException("single URI unavailable") },
                    parentQuery = { DocumentPresence.MISSING },
                )
            },
            delete = { error("already absent") },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
    }

    @Test
    fun permissionDeniedDirectQueryNeverConsultsStaleParentAbsence() {
        var parentQueries = 0

        val presence = verifiedSafDocumentPresence(
            directQuery = { throw SecurityException("permission revoked") },
            parentQuery = {
                parentQueries++
                DocumentPresence.MISSING
            },
        )

        assertThat(presence).isEqualTo(DocumentPresence.UNKNOWN)
        assertThat(parentQueries).isEqualTo(0)
    }

    @Test
    fun acknowledgedDeleteUsesCompleteParentListingForAospIllegalArgumentAbsence() {
        var directQueries = 0
        var deletes = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = false,
            directQuery = {
                if (++directQueries == 1) DirectDocumentQueryResult.Resolved(DocumentPresence.PRESENT)
                else DirectDocumentQueryResult.Failed(IllegalArgumentException("document no longer exists"))
            },
            parentQuery = { DocumentPresence.MISSING },
            delete = { deletes++; true },
        )

        assertThat(result.getOrThrow().providerAcknowledged).isTrue()
        assertThat(deletes).isEqualTo(1)
    }

    @Test
    fun persistedDeleteAcknowledgementUsesCompleteParentListingForNullDirectQueryWithoutDeletingAgain() {
        var deletes = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = true,
            directQuery = { DirectDocumentQueryResult.Resolved(documentPresence(null)) },
            parentQuery = { DocumentPresence.MISSING },
            delete = { deletes++; true },
        )

        assertThat(result.getOrThrow().providerAcknowledged).isTrue()
        assertThat(deletes).isEqualTo(0)
    }

    @Test
    fun persistedDeleteAcknowledgementNeverDeletesAUriThatIsPresentAgain() {
        var parentQueries = 0
        var deletes = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = true,
            directQuery = { DirectDocumentQueryResult.Resolved(DocumentPresence.PRESENT) },
            parentQuery = { parentQueries++; DocumentPresence.MISSING },
            delete = { deletes++; true },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
        assertThat(parentQueries).isEqualTo(0)
        assertThat(deletes).isEqualTo(0)
    }

    @Test
    fun oldParentAbsenceCannotUpgradeAmbiguousQueryWithoutDeleteAcknowledgement() {
        var parentQueries = 0
        var deletes = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = false,
            directQuery = { DirectDocumentQueryResult.Failed(FileNotFoundException("stale uri")) },
            parentQuery = { parentQueries++; DocumentPresence.MISSING },
            delete = { deletes++; throw FileNotFoundException("stale uri") },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
        assertThat(parentQueries).isEqualTo(0)
        assertThat(deletes).isEqualTo(1)
    }

    @Test
    fun resolvedOldUriAbsenceCannotProveDeletionWhenProviderDoesNotAcknowledge() {
        var parentQueries = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = false,
            directQuery = { DirectDocumentQueryResult.Resolved(DocumentPresence.MISSING) },
            parentQuery = { parentQueries++; DocumentPresence.MISSING },
            delete = { false },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
        assertThat(parentQueries).isEqualTo(0)
    }

    @Test
    fun securityFailureCannotUseParentAbsenceEvenWithPersistedAcknowledgement() {
        var parentQueries = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = true,
            directQuery = { DirectDocumentQueryResult.Failed(SecurityException("permission revoked")) },
            parentQuery = { parentQueries++; DocumentPresence.MISSING },
            delete = { error("must not delete again") },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
        assertThat(parentQueries).isEqualTo(0)
    }

    @Test
    fun preflightSecurityFailureExplicitlyReportsThatDeleteWasNotAttempted() {
        var deletes = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = false,
            directQuery = { DirectDocumentQueryResult.Failed(SecurityException("permission revoked")) },
            parentQuery = { error("must not query parent") },
            delete = { deletes++; true },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentDeleteNotAttemptedException::class.java)
        assertThat(deletes).isEqualTo(0)
    }

    @Test
    fun preflightTemporaryProviderFailureExplicitlyReportsThatDeleteWasNotAttempted() {
        var deletes = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = false,
            directQuery = { DirectDocumentQueryResult.Failed(IllegalStateException("provider unavailable")) },
            parentQuery = { error("must not query parent") },
            delete = { deletes++; true },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentDeleteNotAttemptedException::class.java)
        assertThat(deletes).isEqualTo(0)
    }

    @Test
    fun exceptionFromDeleteCallIsAttemptedAndRemainsUnknown() {
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = false,
            directQuery = { DirectDocumentQueryResult.Resolved(DocumentPresence.PRESENT) },
            parentQuery = { DocumentPresence.MISSING },
            delete = { throw SecurityException("provider call failed") },
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentStateUnknownException::class.java)
        assertThat(result.exceptionOrNull()).isNotInstanceOf(DocumentDeleteNotAttemptedException::class.java)
    }

    @Test
    fun providerAcknowledgementIsReturnedEvenWhenPostDeletePermissionLossPreventsVerification() {
        var queries = 0
        val result = deleteSafDocumentVerified(
            deletePreviouslyAcknowledged = false,
            directQuery = {
                if (++queries == 1) DirectDocumentQueryResult.Resolved(DocumentPresence.PRESENT)
                else DirectDocumentQueryResult.Failed(SecurityException("permission revoked"))
            },
            parentQuery = { error("security failure must not consult parent") },
            delete = { true },
        ).getOrThrow()

        assertThat(result.providerAcknowledged).isTrue()
        assertThat(result.verifiedAbsent).isFalse()
    }

    @Test
    fun directQueryFailureRetainsItsProviderCause() {
        val permissionFailure = SecurityException("permission revoked")

        val outcome = captureDirectDocumentPresence { throw permissionFailure }

        assertThat(outcome).isInstanceOf(DirectDocumentQueryResult.Failed::class.java)
        assertThat((outcome as DirectDocumentQueryResult.Failed).cause).isSameInstanceAs(permissionFailure)
    }

    @Test
    fun failedParentListingNeverTurnsSingleUriFailureIntoAbsence() {
        val presence = verifiedSafDocumentPresence(
            directQuery = { documentPresence(null) },
            parentQuery = { throw FileNotFoundException("parent unavailable") },
        )

        assertThat(presence).isEqualTo(DocumentPresence.UNKNOWN)
    }

    @Test
    fun cancellationIsNotWrappedAsProviderFailure() {
        val error = runCatching {
            deleteDocumentIdempotently({ DocumentPresence.PRESENT }, { throw CancellationException("cancelled") })
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(CancellationException::class.java)
    }
}
