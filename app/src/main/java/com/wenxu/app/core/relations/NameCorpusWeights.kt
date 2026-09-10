package com.wenxu.app.core.relations

import kotlin.math.ln

class NameCorpusWeights private constructor(
    private val documentCount: Int,
    private val documentFrequency: Map<String, Int>,
) {
    fun weightOf(token: String): Double =
        ln((documentCount + 1.0) / ((documentFrequency[token] ?: 0) + 1.0)) + 1.0

    fun similarity(first: NameFeatures, second: NameFeatures): Double {
        if (first.coreKey == second.coreKey && first.coreKey.isNotBlank()) return 1.0
        val firstTokens = first.characterTokens
        val secondTokens = second.characterTokens
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0.0
        val sharedWeight = firstTokens.intersect(secondTokens).sumOf(::weightOf)
        val totalWeight = firstTokens.sumOf(::weightOf) + secondTokens.sumOf(::weightOf)
        return if (totalWeight == 0.0) 0.0 else (2.0 * sharedWeight) / totalWeight
    }

    fun strongestTokens(features: NameFeatures, limit: Int = 8): List<String> =
        features.characterTokens
            .sortedWith(compareByDescending<String>(::weightOf).thenBy { it })
            .take(limit)

    companion object {
        fun from(features: List<NameFeatures>): NameCorpusWeights {
            val frequencies = mutableMapOf<String, Int>()
            features.forEach { item ->
                item.characterTokens.forEach { token ->
                    frequencies[token] = (frequencies[token] ?: 0) + 1
                }
            }
            return NameCorpusWeights(features.size.coerceAtLeast(1), frequencies)
        }
    }
}
