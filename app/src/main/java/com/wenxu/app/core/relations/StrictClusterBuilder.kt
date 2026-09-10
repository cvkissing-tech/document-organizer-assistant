package com.wenxu.app.core.relations

class StrictClusterBuilder {
    fun build(acceptedPairs: Map<VersionDocumentPair, Int>): List<Set<Long>> {
        val groups = mutableListOf<MutableSet<Long>>()
        acceptedPairs.entries
            .sortedWith(
                compareByDescending<Map.Entry<VersionDocumentPair, Int>> { entry -> entry.value }
                    .thenBy { entry -> entry.key.first }
                    .thenBy { entry -> entry.key.second },
            )
            .forEach { (pair, _) ->
                val firstGroupIndex = groups.indexOfFirst { group -> pair.first in group }
                val secondGroupIndex = groups.indexOfFirst { group -> pair.second in group }
                when {
                    firstGroupIndex < 0 && secondGroupIndex < 0 -> {
                        groups += linkedSetOf(pair.first, pair.second)
                    }
                    firstGroupIndex >= 0 && secondGroupIndex < 0 -> {
                        if (groups[firstGroupIndex].all { member ->
                                VersionDocumentPair.of(member, pair.second) in acceptedPairs
                            }
                        ) {
                            groups[firstGroupIndex] += pair.second
                        }
                    }
                    firstGroupIndex < 0 && secondGroupIndex >= 0 -> {
                        if (groups[secondGroupIndex].all { member ->
                                VersionDocumentPair.of(member, pair.first) in acceptedPairs
                            }
                        ) {
                            groups[secondGroupIndex] += pair.first
                        }
                    }
                    firstGroupIndex != secondGroupIndex -> {
                        val firstGroup = groups[firstGroupIndex]
                        val secondGroup = groups[secondGroupIndex]
                        val canMerge = firstGroup.all { first ->
                            secondGroup.all { second ->
                                VersionDocumentPair.of(first, second) in acceptedPairs
                            }
                        }
                        if (canMerge) {
                            firstGroup += secondGroup
                            groups.removeAt(secondGroupIndex)
                        }
                    }
                }
            }
        return groups.map { group -> group.toSet() }
    }
}
