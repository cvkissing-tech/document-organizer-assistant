package com.wenxu.app.core.database

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.database.entity.PendingTrashAction
import com.wenxu.app.core.database.entity.PendingTrashOperationEntity
import com.wenxu.app.core.database.entity.PendingTrashStatus
import com.wenxu.app.core.database.entity.PendingTrashSeedFailure
import com.wenxu.app.core.database.entity.PendingTrashSeedSuccess
import com.wenxu.app.core.database.entity.PendingTrashTargetKind
import com.wenxu.app.core.trash.TrashFailureReason
import org.junit.Assert.assertThrows
import org.junit.Test

class WenxuTypeConvertersTest {
    private val converters = WenxuTypeConverters()

    @Test
    fun eligibilityDefaultsToEmptyAndUsesDeterministicSetEncoding() {
        val operation = PendingTrashOperationEntity(
            action = PendingTrashAction.TRASH,
            targetKind = PendingTrashTargetKind.DOCUMENT,
            targetIds = setOf(8L, 3L, 99L),
            status = PendingTrashStatus.AWAITING_CONFIRMATION,
            createdAt = 1L,
            updatedAt = 1L,
        )
        assertThat(operation.eligibleTargetIds).isEmpty()

        val eligible = operation.copy(eligibleTargetIds = setOf(8L, 3L))
        val json = converters.targetIdsToJson(eligible.eligibleTargetIds)
        assertThat(json).isEqualTo("[3,8]")
        assertThat(converters.jsonToLongSet(json)).isEqualTo(eligible.eligibleTargetIds)
        assertThat(eligible.targetIds).isEqualTo(operation.targetIds)
    }

    @Test
    fun targetIdsRejectNonJsonAndNonIntegralValues() {
        listOf(
            "[010]",
            "[0x10]",
            "[1]garbage",
            "[1e3]",
            "[1.0]",
            "[\"1\"]",
            "[true]",
            "[null]",
            "[[1]]",
            "[1,]",
        ).forEach { invalidJson ->
            assertThrows(IllegalArgumentException::class.java) {
                converters.jsonToLongSet(invalidJson)
            }
        }
    }

    @Test
    fun targetIdsAcceptEmptyNegativeAndDuplicateIntegralValues() {
        assertThat(converters.jsonToLongSet("[]")).isEmpty()
        assertThat(converters.jsonToLongSet("[-2,1,-2]"))
            .containsExactly(-2L, 1L)
        assertThat(
            converters.jsonToLongSet("[${Long.MIN_VALUE},${Long.MAX_VALUE}]"),
        ).containsExactly(Long.MIN_VALUE, Long.MAX_VALUE)
    }

    @Test
    fun pendingTrashSeedResultsRoundTripDeterministically() {
        val successes = listOf(
            PendingTrashSeedSuccess(targetId = 8, documentId = 80, trashId = null),
            PendingTrashSeedSuccess(targetId = 3, documentId = 30, trashId = 300),
        )
        val failures = listOf(
            PendingTrashSeedFailure(targetId = 9, reason = TrashFailureReason.DATABASE_FAILED),
            PendingTrashSeedFailure(targetId = 4, reason = TrashFailureReason.VERIFICATION_FAILED),
        )

        val successJson = converters.pendingTrashSeedSuccessesToJson(successes)
        val failureJson = converters.pendingTrashSeedFailuresToJson(failures)

        assertThat(successJson).isEqualTo(
            "[{\"targetId\":3,\"documentId\":30,\"trashId\":300},{\"targetId\":8,\"documentId\":80,\"trashId\":null}]",
        )
        assertThat(failureJson).isEqualTo(
            "[{\"targetId\":4,\"reason\":\"VERIFICATION_FAILED\"},{\"targetId\":9,\"reason\":\"DATABASE_FAILED\"}]",
        )
        assertThat(converters.jsonToPendingTrashSeedSuccesses(successJson)).containsExactlyElementsIn(successes)
        assertThat(converters.jsonToPendingTrashSeedFailures(failureJson)).containsExactlyElementsIn(failures)
    }

    @Test
    fun pendingTrashSeedResultsRejectMalformedOrAmbiguousJson() {
        listOf(
            "[{\"targetId\":1,\"documentId\":2}]",
            "[{\"targetId\":\"1\",\"documentId\":2,\"trashId\":null}]",
            "[{\"targetId\":1,\"documentId\":2,\"trashId\":null,\"extra\":0}]",
            "[{\"targetId\":1,\"documentId\":2,\"trashId\":null},{\"targetId\":1,\"documentId\":3,\"trashId\":null}]",
            "[{\"targetId\":1,\"documentId\":2,\"trashId\":null}] trailing",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                converters.jsonToPendingTrashSeedSuccesses(invalid)
            }
        }
        listOf(
            "[{\"targetId\":1}]",
            "[{\"targetId\":1,\"reason\":\"NOT_A_REASON\"}]",
            "[{\"targetId\":1,\"reason\":\"NOT_FOUND\",\"extra\":0}]",
            "[{\"targetId\":1,\"reason\":\"NOT_FOUND\"}] trailing",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                converters.jsonToPendingTrashSeedFailures(invalid)
            }
        }
    }
}
