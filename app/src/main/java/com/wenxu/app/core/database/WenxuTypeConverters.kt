package com.wenxu.app.core.database

import androidx.room.TypeConverter
import com.wenxu.app.core.database.entity.FingerprintExtractStatus
import com.wenxu.app.core.database.entity.PendingTrashAction
import com.wenxu.app.core.database.entity.PendingTrashStatus
import com.wenxu.app.core.database.entity.PendingTrashSeedFailure
import com.wenxu.app.core.database.entity.PendingTrashSeedSuccess
import com.wenxu.app.core.database.entity.PendingTrashTargetKind
import com.wenxu.app.core.database.entity.RelationAnalysisPhase
import com.wenxu.app.core.database.entity.RelationAnalysisStatus
import com.wenxu.app.core.database.entity.ScanPhase
import com.wenxu.app.core.database.entity.ScanSessionStatus
import com.wenxu.app.core.database.entity.ScanSourceKind
import com.wenxu.app.core.database.entity.SimilarityFeedbackDecision
import com.wenxu.app.core.database.entity.SimilarityReviewState
import com.wenxu.app.core.database.entity.TrashStorageKind
import com.wenxu.app.core.model.DocumentIndexStatus
import com.wenxu.app.core.model.NameSegment
import com.wenxu.app.core.model.SourcePermissionState
import com.wenxu.app.core.relations.ConfidenceBand
import com.wenxu.app.core.relations.EvidenceCode
import com.wenxu.app.core.trash.TrashFailureReason
import org.json.JSONArray
import org.json.JSONObject

class WenxuTypeConverters {
    @TypeConverter
    fun scanSourceKindToString(value: ScanSourceKind): String = value.name

    @TypeConverter
    fun stringToScanSourceKind(value: String): ScanSourceKind = ScanSourceKind.valueOf(value)

    @TypeConverter
    fun scanPhaseToString(value: ScanPhase): String = value.name

    @TypeConverter
    fun stringToScanPhase(value: String): ScanPhase = ScanPhase.valueOf(value)

    @TypeConverter
    fun scanSessionStatusToString(value: ScanSessionStatus): String = value.name

    @TypeConverter
    fun stringToScanSessionStatus(value: String): ScanSessionStatus =
        ScanSessionStatus.valueOf(value)

    @TypeConverter
    fun documentIndexStatusToString(value: DocumentIndexStatus): String = value.name

    @TypeConverter
    fun stringToDocumentIndexStatus(value: String): DocumentIndexStatus =
        DocumentIndexStatus.valueOf(value)

    @TypeConverter
    fun sourcePermissionStateToString(value: SourcePermissionState): String = value.name

    @TypeConverter
    fun stringToSourcePermissionState(value: String): SourcePermissionState =
        SourcePermissionState.valueOf(value)

    @TypeConverter
    fun fingerprintExtractStatusToString(value: FingerprintExtractStatus): String = value.name

    @TypeConverter
    fun stringToFingerprintExtractStatus(value: String): FingerprintExtractStatus =
        FingerprintExtractStatus.valueOf(value)

    @TypeConverter
    fun similarityFeedbackDecisionToString(value: SimilarityFeedbackDecision): String = value.name

    @TypeConverter
    fun stringToSimilarityFeedbackDecision(value: String): SimilarityFeedbackDecision =
        SimilarityFeedbackDecision.valueOf(value)

    @TypeConverter
    fun relationAnalysisStatusToString(value: RelationAnalysisStatus): String = value.name

    @TypeConverter
    fun stringToRelationAnalysisStatus(value: String): RelationAnalysisStatus =
        RelationAnalysisStatus.valueOf(value)

    @TypeConverter
    fun relationAnalysisPhaseToString(value: RelationAnalysisPhase): String = value.name

    @TypeConverter
    fun stringToRelationAnalysisPhase(value: String): RelationAnalysisPhase =
        RelationAnalysisPhase.valueOf(value)

    @TypeConverter
    fun similarityReviewStateToString(value: SimilarityReviewState): String = value.name

    @TypeConverter
    fun stringToSimilarityReviewState(value: String): SimilarityReviewState =
        SimilarityReviewState.valueOf(value)

    @TypeConverter
    fun pendingTrashActionToString(value: PendingTrashAction): String = value.name

    @TypeConverter
    fun stringToPendingTrashAction(value: String): PendingTrashAction =
        PendingTrashAction.valueOf(value)

    @TypeConverter
    fun pendingTrashTargetKindToString(value: PendingTrashTargetKind): String = value.name

    @TypeConverter
    fun stringToPendingTrashTargetKind(value: String): PendingTrashTargetKind =
        PendingTrashTargetKind.valueOf(value)

    @TypeConverter
    fun pendingTrashStatusToString(value: PendingTrashStatus): String = value.name

    @TypeConverter
    fun stringToPendingTrashStatus(value: String): PendingTrashStatus =
        PendingTrashStatus.valueOf(value)

    @TypeConverter
    fun trashStorageKindToString(value: TrashStorageKind): String = value.name

    @TypeConverter
    fun stringToTrashStorageKind(value: String): TrashStorageKind =
        TrashStorageKind.valueOf(value)

    @TypeConverter
    fun targetIdsToJson(value: Set<Long>): String {
        val array = JSONArray()
        value.sorted().forEach(array::put)
        return array.toString()
    }

    @TypeConverter
    fun jsonToLongSet(value: String): Set<Long> {
        requireStrictLongArrayJson(value)
        val array = JSONArray(value)
        return buildSet(array.length()) {
            repeat(array.length()) { index ->
                val item = array.get(index)
                require(item is Int || item is Long) {
                    "targetIds[$index] must fit in a Long"
                }
                add(array.getLong(index))
            }
        }
    }

    @TypeConverter
    fun pendingTrashSeedSuccessesToJson(value: List<PendingTrashSeedSuccess>): String =
        value.sortedBy { it.targetId }.joinToString(separator = ",", prefix = "[", postfix = "]") { success ->
            "{\"targetId\":${success.targetId},\"documentId\":${success.documentId}," +
                "\"trashId\":${success.trashId ?: "null"}}"
        }

    @TypeConverter
    fun jsonToPendingTrashSeedSuccesses(value: String): List<PendingTrashSeedSuccess> {
        requireSingleJsonArray(value, "seedSuccesses")
        val array = JSONArray(value)
        val targetIds = mutableSetOf<Long>()
        return buildList(array.length()) {
            repeat(array.length()) { index ->
                val item = array.get(index)
                require(item is JSONObject) { "seedSuccesses[$index] must be an object" }
                requireExactKeys(item, setOf("targetId", "documentId", "trashId"), "seedSuccesses[$index]")
                val targetId = strictJsonLong(item, "targetId", "seedSuccesses[$index]")
                require(targetIds.add(targetId)) { "seedSuccesses targetId must be unique" }
                val trashValue = item.get("trashId")
                add(PendingTrashSeedSuccess(
                    targetId = targetId,
                    documentId = strictJsonLong(item, "documentId", "seedSuccesses[$index]"),
                    trashId = if (trashValue == JSONObject.NULL) null
                    else strictJsonLongValue(trashValue, "seedSuccesses[$index].trashId"),
                ))
            }
        }
    }

    @TypeConverter
    fun pendingTrashSeedFailuresToJson(value: List<PendingTrashSeedFailure>): String =
        value.sortedBy { it.targetId }.joinToString(separator = ",", prefix = "[", postfix = "]") { failure ->
            "{\"targetId\":${failure.targetId},\"reason\":\"${failure.reason.name}\"}"
        }

    @TypeConverter
    fun jsonToPendingTrashSeedFailures(value: String): List<PendingTrashSeedFailure> {
        requireSingleJsonArray(value, "seedFailures")
        val array = JSONArray(value)
        val targetIds = mutableSetOf<Long>()
        return buildList(array.length()) {
            repeat(array.length()) { index ->
                val item = array.get(index)
                require(item is JSONObject) { "seedFailures[$index] must be an object" }
                requireExactKeys(item, setOf("targetId", "reason"), "seedFailures[$index]")
                val targetId = strictJsonLong(item, "targetId", "seedFailures[$index]")
                require(targetIds.add(targetId)) { "seedFailures targetId must be unique" }
                val reason = item.get("reason")
                require(reason is String) { "seedFailures[$index].reason must be a string" }
                add(PendingTrashSeedFailure(targetId, TrashFailureReason.valueOf(reason)))
            }
        }
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>, label: String) {
        val actual = buildSet {
            val keys = value.keys()
            while (keys.hasNext()) add(keys.next())
        }
        require(actual == expected) { "$label has invalid fields" }
    }

    private fun strictJsonLong(value: JSONObject, key: String, label: String): Long =
        strictJsonLongValue(value.get(key), "$label.$key")

    private fun strictJsonLongValue(value: Any, label: String): Long {
        require(value is Int || value is Long) { "$label must be an integer" }
        return (value as Number).toLong()
    }

    private fun requireSingleJsonArray(value: String, label: String) {
        var index = 0
        while (index < value.length && value[index].isWhitespace()) index++
        require(index < value.length && value[index] == '[') { "$label must be a JSON array" }
        var depth = 0
        var inString = false
        var escaped = false
        var closedAt = -1
        while (index < value.length) {
            val character = value[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        require(depth >= 0) { "$label must be a JSON array" }
                        if (depth == 0) {
                            closedAt = index
                            break
                        }
                    }
                }
            }
            index++
        }
        require(closedAt >= 0 && !inString) { "$label must be a complete JSON array" }
        require(value.substring(closedAt + 1).all(Char::isWhitespace)) {
            "$label must contain only one JSON array"
        }
    }

    private fun requireStrictLongArrayJson(value: String) {
        var index = 0

        fun skipWhitespace() {
            while (
                index < value.length &&
                (value[index] == ' ' || value[index] == '\t' ||
                    value[index] == '\r' || value[index] == '\n')
            ) {
                index++
            }
        }

        fun requireCharacter(expected: Char) {
            require(index < value.length && value[index] == expected) {
                "targetIds must be a JSON array of decimal integers"
            }
            index++
        }

        skipWhitespace()
        requireCharacter('[')
        skipWhitespace()
        if (index < value.length && value[index] == ']') {
            index++
            skipWhitespace()
            require(index == value.length) { "targetIds must not contain trailing content" }
            return
        }

        while (true) {
            if (index < value.length && value[index] == '-') index++
            require(index < value.length && value[index] in '0'..'9') {
                "targetIds entries must be decimal integers"
            }
            if (value[index] == '0') {
                index++
                require(index >= value.length || value[index] !in '0'..'9') {
                    "targetIds entries must not contain leading zeroes"
                }
            } else {
                while (index < value.length && value[index] in '0'..'9') index++
            }

            skipWhitespace()
            require(index < value.length) { "targetIds JSON array is not closed" }
            when (value[index]) {
                ',' -> {
                    index++
                    skipWhitespace()
                }

                ']' -> {
                    index++
                    break
                }

                else -> throw IllegalArgumentException(
                    "targetIds must contain only decimal integers",
                )
            }
        }

        skipWhitespace()
        require(index == value.length) { "targetIds must not contain trailing content" }
    }

    @TypeConverter
    fun confidenceBandToString(value: ConfidenceBand): String = value.name

    @TypeConverter
    fun stringToConfidenceBand(value: String): ConfidenceBand = ConfidenceBand.valueOf(value)

    @TypeConverter
    fun evidenceCodesToJson(value: Set<EvidenceCode>): String {
        val array = JSONArray()
        value.sortedBy(EvidenceCode::name).forEach { code -> array.put(code.name) }
        return array.toString()
    }

    @TypeConverter
    fun jsonToEvidenceCodes(value: String): Set<EvidenceCode> {
        val array = JSONArray(value)
        return buildSet(array.length()) {
            repeat(array.length()) { index ->
                add(EvidenceCode.valueOf(array.getString(index)))
            }
        }
    }

    @TypeConverter
    fun nameSegmentsToJson(value: List<NameSegment>): String {
        val array = JSONArray()
        value.forEach { segment ->
            array.put(
                JSONObject()
                    .put("text", segment.text)
                    .put("isDifference", segment.isDifference),
            )
        }
        return array.toString()
    }

    @TypeConverter
    fun jsonToNameSegments(value: String): List<NameSegment> {
        val array = JSONArray(value)
        return buildList(array.length()) {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    NameSegment(
                        text = item.getString("text"),
                        isDifference = item.getBoolean("isDifference"),
                    ),
                )
            }
        }
    }
}
