package com.wenxu.app.core.relations

import com.wenxu.app.core.model.NameSegment
import com.wenxu.app.core.model.NormalizedName
import java.text.Normalizer as UnicodeNormalizer
import java.util.Locale

class NameNormalizer {
    private data class MarkerRule(val pattern: Regex, val kind: NameMarkerKind)

    private data class IdentifierRule(
        val pattern: Regex,
        val kind: ProtectedIdentifierKind,
        val valueGroup: Int = 0,
    )

    private val markerRules = listOf(
        MarkerRule(Regex("[（(]\\d+[）)]$"), NameMarkerKind.COPY),
        MarkerRule(Regex("(?i)(?:^|[-_ .])(?:v|rev)\\d+(?:\\.\\d+)*$"), NameMarkerKind.VERSION),
        MarkerRule(Regex("[-_ ]?第[一二三四五六七八九十百\\d]+版$"), NameMarkerKind.VERSION),
        MarkerRule(
            Regex("[-_ ]?(?:最终修改版|最终版|修改版|修订版|最新版|初稿|二稿|三稿|终稿|定稿|草稿)$"),
            NameMarkerKind.STATUS,
        ),
        MarkerRule(Regex("[-_ ]?(?:副本|复制)$"), NameMarkerKind.COPY),
        MarkerRule(Regex("(?i)(?:^|[-_ ])copy$"), NameMarkerKind.COPY),
        MarkerRule(Regex("(?i)(?:^|[-_ ])(?:draft|final|revised)$"), NameMarkerKind.STATUS),
        MarkerRule(
            Regex("[-_ ]?(?:[\\p{IsHan}]{1,4}(?:老师|导师))?(?:修改|批注|批改)(?:版)?$"),
            NameMarkerKind.REVIEW,
        ),
        MarkerRule(
            Regex("[-_ ]?(?:20\\d{6}|20\\d{2}[-_.]\\d{1,2}[-_.]\\d{1,2})$"),
            NameMarkerKind.DATE,
        ),
    )

    private val identifierRules = listOf(
        IdentifierRule(
            Regex("(?<!\\d)((?:19|20)\\d{2})(?=(?:年度?|学年|届|春季|秋季|上半年|下半年))"),
            ProtectedIdentifierKind.YEAR,
            valueGroup = 1,
        ),
        IdentifierRule(
            Regex("第([一二三四五六七八九十百\\d]+)章"),
            ProtectedIdentifierKind.CHAPTER,
            valueGroup = 1,
        ),
        IdentifierRule(
            Regex("(?:作业|习题|实验)[-_ ]?([一二三四五六七八九十百\\d]+)"),
            ProtectedIdentifierKind.ASSIGNMENT,
            valueGroup = 1,
        ),
    )

    fun normalize(displayName: String): NormalizedName {
        val features = analyze(displayName)
        return NormalizedName(
            baseName = features.coreName,
            differenceSegments = features.differenceSegments,
        )
    }

    fun analyze(displayName: String): NameFeatures {
        val rawExtensionSeparator = displayName.lastIndexOfAny(charArrayOf('.', '．'))
        val rawStem = if (rawExtensionSeparator >= 0) {
            displayName.substring(0, rawExtensionSeparator).trim()
        } else {
            displayName.trim()
        }
        val analysisName = UnicodeNormalizer.normalize(displayName, UnicodeNormalizer.Form.NFKC)
        val analysisStem = analysisName
            .substringBeforeLast('.', missingDelimiterValue = analysisName)
            .trim()
        var base = analysisStem
        val removedFromEnd = mutableListOf<NameMarker>()

        while (base.isNotEmpty()) {
            val match = markerRules
                .mapNotNull { rule -> rule.pattern.find(base)?.let { result -> rule to result } }
                .minByOrNull { (_, result) -> result.range.first }
                ?: break
            val (rule, result) = match
            removedFromEnd += NameMarker(result.value, rule.kind)
            base = base.removeRange(result.range).trim(' ', '-', '_', '.')
        }

        val coreName = base
            .replace(Regex("[\\s_.\\-—]+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
        val coreKey = coreName.filter { character -> character.isLetterOrDigit() }
        val markers = removedFromEnd.reversed()
        val identifierMatches = identifierRules
            .flatMap { rule ->
                rule.pattern.findAll(coreName).map { result ->
                    val valueMatch = checkNotNull(result.groups[rule.valueGroup])
                    IdentifierMatch(
                        identifier = ProtectedIdentifier(
                            kind = rule.kind,
                            value = normalizeProtectedIdentifier(valueMatch.value),
                        ),
                        protectedNumericRanges = Regex("\\d+")
                            .findAll(valueMatch.value)
                            .map { number ->
                                val start = valueMatch.range.first + number.range.first
                                start..(start + number.value.lastIndex)
                            }
                            .toList(),
                    )
                }
            }
        val protectedIdentifiers = identifierMatches.mapTo(linkedSetOf()) { match -> match.identifier }
        val protectedNumericRanges = identifierMatches.flatMap { match -> match.protectedNumericRanges }
        val numericMatches = Regex("\\d+").findAll(coreName).toList()
        return NameFeatures(
            rawStem = rawStem,
            coreName = coreName,
            coreKey = coreKey,
            markers = markers,
            differenceSegments = markers.map { marker ->
                NameSegment(marker.text, isDifference = true)
            },
            characterTokens = characterTokens(coreKey),
            protectedIdentifiers = protectedIdentifiers,
            unprotectedNumericIdentifiers = numericMatches
                .filterNot { number ->
                    protectedNumericRanges.any { range ->
                        number.range.first >= range.first && number.range.last <= range.last
                    }
                }
                .map { number -> number.value },
            numericIdentifiers = numericMatches.mapTo(hashSetOf()) { result -> result.value },
        )
    }

    private data class IdentifierMatch(
        val identifier: ProtectedIdentifier,
        val protectedNumericRanges: List<IntRange>,
    )

    private fun normalizeProtectedIdentifier(value: String): String {
        value.toLongOrNull()?.let { return it.toString() }
        return chineseNumberValue(value)?.toString() ?: value
    }

    private fun chineseNumberValue(value: String): Int? {
        if (value.isEmpty()) return null

        val digits = mapOf(
            '一' to 1,
            '二' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '七' to 7,
            '八' to 8,
            '九' to 9,
        )
        val units = mapOf('十' to 10, '百' to 100)
        var total = 0
        var pendingDigit: Int? = null

        for (character in value) {
            val digit = digits[character]
            if (digit != null) {
                pendingDigit = digit
                continue
            }

            val unit = units[character] ?: return null
            total += (pendingDigit ?: 1) * unit
            pendingDigit = null
        }
        return total + (pendingDigit ?: 0)
    }
}
