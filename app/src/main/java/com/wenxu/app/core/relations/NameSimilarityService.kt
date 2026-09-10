package com.wenxu.app.core.relations

import com.wenxu.app.core.model.DocumentRecord
import com.wenxu.app.core.model.NameGroup

class NameSimilarityService(
    private val normalizer: NameNormalizer,
    private val store: NameSimilarityStore? = null,
    private val scorer: NameSimilarityScorer = NameSimilarityScorer(),
    private val versionScorer: VersionSimilarityScorer = VersionSimilarityScorer(),
    private val clusterBuilder: StrictClusterBuilder = StrictClusterBuilder(),
) {
    fun group(documents: List<DocumentRecord>): List<NameGroup> = buildDrafts(documents)
        .map { draft ->
            NameGroup(
                baseName = draft.baseName,
                extension = draft.extension,
                memberIds = draft.members.map { member -> member.documentId },
            )
        }

    suspend fun rebuild(): List<NameGroup> {
        val requiredStore = checkNotNull(store) { "rebuild 需要 NameSimilarityStore" }
        val documents = requiredStore.activeDocuments()
        val drafts = buildDrafts(documents)
        requiredStore.replaceGroups(drafts)
        val groups = drafts.map { draft ->
            NameGroup(
                baseName = draft.baseName,
                extension = draft.extension,
                memberIds = draft.members.map { member -> member.documentId },
            )
        }
        return groups
    }

    suspend fun confirmCurrent(groupId: Long, documentId: Long) {
        val requiredStore = checkNotNull(store) { "confirmCurrent 需要 NameSimilarityStore" }
        require(requiredStore.isMember(groupId, documentId)) { "所选文档不属于该名称相似组" }
        requiredStore.setConfirmedCurrent(groupId, documentId)
    }

    private fun buildDrafts(documents: List<DocumentRecord>): List<NameGroupDraft> {
        if (documents.size < 2) return emptyList()
        val items = documents.map { document ->
            NameDocumentFeatures(document, normalizer.analyze(document.displayName))
        }
        val weights = NameCorpusWeights.from(items.map { item -> item.name })
        val acceptedPairs = boundedNameCandidatePairs(items, weights)
            .mapNotNull { pair ->
                val first = items[pair.first]
                val second = items[pair.second]
                val nameDecision = scorer.compare(first, second, weights)
                val decision = versionScorer.decide(
                    VersionCandidateSignals(
                        nameScore = nameDecision.score.takeIf { nameDecision.accepted } ?: 0.0,
                        sameCore = first.name.coreKey == second.name.coreKey,
                        hasVersionMarker = first.name.hasVersionEvidence || second.name.hasVersionEvidence,
                        sameFolder = reliableSameFolder(first.document, second.document),
                        modifiedGapMillis = modifiedGapMillis(first.document, second.document),
                        sizeRatio = sizeRatio(first.document, second.document),
                        sameExtension = first.document.extension.equals(
                            second.document.extension,
                            ignoreCase = true,
                        ),
                        content = ContentEvidence.Unavailable,
                        userAllowed = false,
                        conflicts = nameDecision.conflicts,
                    ),
                )
                if (decision.band == ConfidenceBand.HIGH) {
                    VersionDocumentPair.of(first.document.id, second.document.id) to decision.score
                } else {
                    null
                }
            }
            .toMap()
        if (acceptedPairs.isEmpty()) return emptyList()

        val itemsById = items.associateBy { item -> item.document.id }
        return clusterBuilder.build(acceptedPairs)
            .filter { cluster -> cluster.size > 1 }
            .map { cluster ->
                createDraft(cluster.mapTo(linkedSetOf()) { documentId -> itemsById.getValue(documentId) })
            }
            .sortedWith(compareBy<NameGroupDraft> { it.baseName }.thenBy { it.extension })
    }

    private fun createDraft(
        members: Set<NameDocumentFeatures>,
    ): NameGroupDraft {
        val representativeCore = members
            .groupingBy { item -> item.name.coreName }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { entry -> entry.value }
                    .thenBy { entry -> entry.key.length }
                    .thenBy { entry -> entry.key },
            )
            .first()
            .key
        return NameGroupDraft(
            baseName = representativeCore,
            extension = canonicalExtension(members.first().document.extension),
            members = members
                .sortedWith(
                    compareByDescending<NameDocumentFeatures> { item -> item.document.modifiedAt }
                        .thenBy { item -> item.document.id },
                )
                .map { item ->
                    NameGroupMemberDraft(
                        documentId = item.document.id,
                        differenceSegments = item.name.differenceSegments,
                    )
                },
        )
    }

    private fun modifiedGapMillis(first: DocumentRecord, second: DocumentRecord): Long? {
        if (first.modifiedAt <= 0L || second.modifiedAt <= 0L) return null
        return if (first.modifiedAt >= second.modifiedAt) {
            first.modifiedAt - second.modifiedAt
        } else {
            second.modifiedAt - first.modifiedAt
        }
    }

    private fun sizeRatio(first: DocumentRecord, second: DocumentRecord): Double? {
        val smaller = minOf(first.sizeBytes, second.sizeBytes)
        val larger = maxOf(first.sizeBytes, second.sizeBytes)
        if (smaller <= 0L) return null
        return larger.toDouble() / smaller.toDouble()
    }

    private fun canonicalExtension(extension: String): String =
        when (DocumentFamily.fromExtension(extension)) {
            DocumentFamily.PDF -> "pdf"
            DocumentFamily.WORD -> "docx"
            DocumentFamily.SHEET -> "xlsx"
            DocumentFamily.SLIDES -> "pptx"
            null -> extension.lowercase()
        }

}

internal fun boundedNameCandidatePairs(
    items: List<NameDocumentFeatures>,
    weights: NameCorpusWeights,
): Set<NameCandidatePair> = buildSet {
    val candidateCounts = IntArray(items.size)

    fun addCandidate(pair: NameCandidatePair) {
        if (size >= MAX_NAME_CANDIDATE_PAIRS) return
        if (candidateCounts[pair.first] >= MAX_NAME_CANDIDATES_PER_DOCUMENT ||
            candidateCounts[pair.second] >= MAX_NAME_CANDIDATES_PER_DOCUMENT
        ) {
            return
        }
        if (add(pair)) {
            candidateCounts[pair.first] += 1
            candidateCounts[pair.second] += 1
        }
    }

    fun drain(iterator: Iterator<NameCandidatePair>) {
        while (size < MAX_NAME_CANDIDATE_PAIRS && iterator.hasNext()) {
            addCandidate(iterator.next())
        }
    }

    val buckets = candidateSourceBuckets(items, weights)

    // Phase 1: exact-core coverage cannot be displaced by weaker candidate sources. Buckets
    // share the budget round-robin when the mathematical coverage demand exceeds the global cap.
    drain(fairPairIterator(buckets.exact.map(::exactCoverageIterator)))

    // Phase 2: exact buckets small enough for every legal edge are completed before weak sources.
    val completeExact = buckets.exact.filter(::canCompareExactBucketCompletely)
    drain(fairPairIterator(completeExact.map(::exactExpansionIterator)))

    // Phase 3: large exact metadata-neighbour expansion and weak sources share only the remainder.
    val sources = listOf(
        fairPairIterator(
            buckets.exact.filterNot(::canCompareExactBucketCompletely)
                .map(::exactExpansionIterator),
        ),
        fairBucketPairIterator(buckets.protected),
        fairBucketPairIterator(buckets.token),
    ).toMutableList()
    while (size < MAX_NAME_CANDIDATE_PAIRS && sources.isNotEmpty()) {
        val round = sources.listIterator()
        while (size < MAX_NAME_CANDIDATE_PAIRS && round.hasNext()) {
            val source = round.next()
            if (source.hasNext()) {
                addCandidate(source.next())
            } else {
                round.remove()
            }
        }
    }
}

internal data class StableCandidateBucket(
    val source: CandidateSourceKind,
    val key: String,
    val indices: List<Int>,
    val featuresByIndex: Map<Int, NameDocumentFeatures> = emptyMap(),
)

private data class CandidateSourceBuckets(
    val exact: List<StableCandidateBucket>,
    val protected: List<StableCandidateBucket>,
    val token: List<StableCandidateBucket>,
)

internal enum class CandidateSourceKind {
    EXACT_CORE,
    PROTECTED_CORE,
    TOKEN,
}

private fun candidateSourceBuckets(
    items: List<NameDocumentFeatures>,
    weights: NameCorpusWeights,
): CandidateSourceBuckets {
    val families = items.indices
        .mapNotNull { index ->
            DocumentFamily.fromExtension(items[index].document.extension)?.let { family -> family to index }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .toSortedMap(compareBy { family -> family.ordinal })

    val exact = mutableListOf<StableCandidateBucket>()
    val protected = mutableListOf<StableCandidateBucket>()
    val token = mutableListOf<StableCandidateBucket>()
    families.forEach { (family, familyIndices) ->
        familyIndices.groupBy { index -> items[index].name.coreKey }
            .filterKeys(String::isNotBlank)
            .toSortedMap()
            .forEach { (key, indices) ->
                addStableBucket(
                    destination = exact,
                    source = CandidateSourceKind.EXACT_CORE,
                    key = "exact:${family.name}:$key",
                    indices = indices,
                    items = items,
                    skipSingleCore = false,
                )
            }

        familyIndices.groupBy { index -> protectedNeutralCoreKey(items[index].name) }
            .filterKeys(String::isNotBlank)
            .toSortedMap()
            .forEach { (key, indices) ->
                addStableBucket(
                    destination = protected,
                    source = CandidateSourceKind.PROTECTED_CORE,
                    key = "protected:${family.name}:$key",
                    indices = indices,
                    items = items,
                )
            }

        val tokenIndices = mutableMapOf<String, MutableList<Int>>()
        familyIndices.forEach { index ->
            weights.strongestTokens(items[index].name).forEach { value ->
                tokenIndices.getOrPut(value, ::mutableListOf).add(index)
            }
        }
        tokenIndices.toSortedMap().forEach { (key, indices) ->
            addStableBucket(
                destination = token,
                source = CandidateSourceKind.TOKEN,
                key = "token:${family.name}:$key",
                indices = indices,
                items = items,
            )
        }
    }
    return CandidateSourceBuckets(exact, protected, token)
}

private fun addStableBucket(
    destination: MutableList<StableCandidateBucket>,
    source: CandidateSourceKind,
    key: String,
    indices: List<Int>,
    items: List<NameDocumentFeatures>,
    skipSingleCore: Boolean = true,
) {
    if (indices.size < 2) return
    if (skipSingleCore && indices.mapTo(hashSetOf()) { index -> items[index].name.coreKey }.size == 1) return
    val ordered = indices.sortedWith(
        compareBy<Int> { index -> stableOrderKey(stableFileIdentity(items[index]), key) }
            .thenBy { index -> stableFileIdentity(items[index]) },
    )
    val selected = if (source == CandidateSourceKind.EXACT_CORE) {
        ordered
    } else {
        ordered.take(MAX_NAME_CANDIDATE_DOCUMENTS_PER_BUCKET)
    }
    if (selected.size >= 2) {
        destination += StableCandidateBucket(
            source = source,
            key = key,
            indices = selected,
            featuresByIndex = selected.associateWith { index -> items[index] },
        )
    }
}

private fun fairBucketPairIterator(buckets: List<StableCandidateBucket>): Iterator<NameCandidatePair> =
    fairPairIterator(buckets.sortedBy { bucket -> bucket.key }.map(::stableBucketPairIterator))

private fun fairPairIterator(
    sourceIterators: List<Iterator<NameCandidatePair>>,
): Iterator<NameCandidatePair> = sequence {
        val iterators = sourceIterators
            .toMutableList()
        while (iterators.isNotEmpty()) {
            val round = iterators.listIterator()
            while (round.hasNext()) {
                val iterator = round.next()
                if (iterator.hasNext()) {
                    yield(iterator.next())
                } else {
                    round.remove()
                }
            }
        }
    }.iterator()

private fun canCompareExactBucketCompletely(bucket: StableCandidateBucket): Boolean {
    if (bucket.source != CandidateSourceKind.EXACT_CORE) return false
    val size = bucket.indices.size.toLong()
    val pairCount = size * (size - 1L) / 2L
    return size - 1L <= MAX_NAME_CANDIDATES_PER_DOCUMENT &&
        pairCount <= MAX_NAME_CANDIDATE_PAIRS
}

private fun exactCoverageIterator(bucket: StableCandidateBucket): Iterator<NameCandidatePair> = sequence {
    require(bucket.source == CandidateSourceKind.EXACT_CORE)
    var position = 0
    while (position + 1 < bucket.indices.size) {
        yield(NameCandidatePair.of(bucket.indices[position], bucket.indices[position + 1]))
        position += 2
    }
    if (bucket.indices.size % 2 == 1) {
        yield(NameCandidatePair.of(bucket.indices.last(), bucket.indices.first()))
    }
}.iterator()

private fun exactExpansionIterator(bucket: StableCandidateBucket): Iterator<NameCandidatePair> {
    require(bucket.source == CandidateSourceKind.EXACT_CORE)
    val coverage = exactCoverageIterator(bucket).asSequence().toHashSet()
    return if (canCompareExactBucketCompletely(bucket)) {
        allExactPairs(bucket).asSequence().filterNot(coverage::contains).iterator()
    } else {
        prioritizedExactPairsWithDiagnostics(bucket).pairs.asSequence()
            .filterNot(coverage::contains)
            .iterator()
    }
}

private fun allExactPairs(bucket: StableCandidateBucket): List<NameCandidatePair> = buildList {
    for (firstPosition in 0 until bucket.indices.lastIndex) {
        for (secondPosition in firstPosition + 1 until bucket.indices.size) {
            add(NameCandidatePair.of(bucket.indices[firstPosition], bucket.indices[secondPosition]))
        }
    }
}.sortedWith(exactMetadataComparator(bucket))

internal data class ExactMetadataCandidateDiagnostics(
    val bucketKey: String,
    val parentCandidates: Int,
    val modifiedCandidates: Int,
    val sizeCandidates: Int,
    val stableIdentityCandidates: Int,
)

private data class PrioritizedExactCandidateResult(
    val pairs: List<NameCandidatePair>,
    val diagnostics: ExactMetadataCandidateDiagnostics,
)

internal fun exactMetadataCandidateDiagnostics(
    items: List<NameDocumentFeatures>,
    weights: NameCorpusWeights,
): List<ExactMetadataCandidateDiagnostics> = candidateSourceBuckets(items, weights).exact
    .filterNot(::canCompareExactBucketCompletely)
    .map { bucket -> prioritizedExactPairsWithDiagnostics(bucket).diagnostics }

private fun prioritizedExactPairsWithDiagnostics(
    bucket: StableCandidateBucket,
): PrioritizedExactCandidateResult {
    val candidates = linkedSetOf<NameCandidatePair>()
    val features = bucket.featuresByIndex

    class SignalBudget {
        var added: Int = 0
            private set

        val exhausted: Boolean
            get() = added >= MAX_EXACT_METADATA_CANDIDATES_PER_SIGNAL

        fun add(pair: NameCandidatePair) {
            if (!exhausted && candidates.add(pair)) added += 1
        }
    }

    fun addNeighbourPairs(ordered: List<Int>, budget: SignalBudget) {
        for (distance in 1..MAX_EXACT_METADATA_NEIGHBOR_DISTANCE) {
            for (firstPosition in 0 until ordered.size - distance) {
                if (budget.exhausted) return
                budget.add(NameCandidatePair.of(ordered[firstPosition], ordered[firstPosition + distance]))
            }
        }
    }

    val parentBudget = SignalBudget()
    features.keys.groupBy { index ->
        features.getValue(index).document.parentUri.trim().trimEnd('/')
    }
        .filterKeys(String::isNotBlank)
        .toSortedMap()
        .values
        .forEach { group ->
            addNeighbourPairs(
                group.sortedWith(
                    compareBy<Int> { index -> unknownLast(features.getValue(index).document.modifiedAt) }
                        .thenBy { index -> unknownLast(features.getValue(index).document.sizeBytes) }
                        .thenBy { index -> stableFileIdentity(features.getValue(index)) },
                ),
                parentBudget,
            )
        }
    val modifiedBudget = SignalBudget()
    addNeighbourPairs(
        bucket.indices.sortedWith(
            compareBy<Int> { index -> unknownLast(features.getValue(index).document.modifiedAt) }
                .thenBy { index -> stableFileIdentity(features.getValue(index)) },
        ),
        modifiedBudget,
    )
    val sizeBudget = SignalBudget()
    addNeighbourPairs(
        bucket.indices.sortedWith(
            compareBy<Int> { index -> unknownLast(features.getValue(index).document.sizeBytes) }
                .thenBy { index -> stableFileIdentity(features.getValue(index)) },
        ),
        sizeBudget,
    )
    val stableIdentityBudget = SignalBudget()
    addNeighbourPairs(bucket.indices, stableIdentityBudget)
    return PrioritizedExactCandidateResult(
        pairs = candidates.sortedWith(exactMetadataComparator(bucket)),
        diagnostics = ExactMetadataCandidateDiagnostics(
            bucketKey = bucket.key,
            parentCandidates = parentBudget.added,
            modifiedCandidates = modifiedBudget.added,
            sizeCandidates = sizeBudget.added,
            stableIdentityCandidates = stableIdentityBudget.added,
        ),
    )
}

private fun exactMetadataComparator(bucket: StableCandidateBucket): Comparator<NameCandidatePair> {
    val features = bucket.featuresByIndex
    return compareByDescending<NameCandidatePair> { pair ->
        reliableSameFolder(
            features.getValue(pair.first).document,
            features.getValue(pair.second).document,
        )
    }.thenBy { pair ->
        modifiedGapOrMaximum(
            features.getValue(pair.first),
            features.getValue(pair.second),
        )
    }.thenBy { pair ->
        sizeRatioOrMaximum(
            features.getValue(pair.first),
            features.getValue(pair.second),
        )
    }.thenBy { pair -> stablePairIdentity(pair, features) }
}

private fun modifiedGapOrMaximum(first: NameDocumentFeatures, second: NameDocumentFeatures): Long {
    val firstModified = first.document.modifiedAt
    val secondModified = second.document.modifiedAt
    if (firstModified <= 0L || secondModified <= 0L) return Long.MAX_VALUE
    return if (firstModified >= secondModified) firstModified - secondModified else secondModified - firstModified
}

private fun sizeRatioOrMaximum(first: NameDocumentFeatures, second: NameDocumentFeatures): Double {
    val smaller = minOf(first.document.sizeBytes, second.document.sizeBytes)
    if (smaller <= 0L) return Double.MAX_VALUE
    return maxOf(first.document.sizeBytes, second.document.sizeBytes).toDouble() / smaller.toDouble()
}

private fun stablePairIdentity(
    pair: NameCandidatePair,
    features: Map<Int, NameDocumentFeatures>,
): String {
    val first = stableFileIdentity(features.getValue(pair.first))
    val second = stableFileIdentity(features.getValue(pair.second))
    return if (first <= second) "$first\u0000$second" else "$second\u0000$first"
}

private fun unknownLast(value: Long): Long = if (value > 0L) value else Long.MAX_VALUE

internal fun stableBucketPairIterator(bucket: StableCandidateBucket): Iterator<NameCandidatePair> = sequence {
    require(bucket.source != CandidateSourceKind.EXACT_CORE)
    var emitted = 0
    val seen = hashSetOf<NameCandidatePair>()

    suspend fun SequenceScope<NameCandidatePair>.emit(firstPosition: Int, secondPosition: Int) {
        if (emitted >= MAX_NAME_CANDIDATE_PAIRS_PER_BUCKET) return
        val pair = NameCandidatePair.of(
            bucket.indices[firstPosition],
            bucket.indices[secondPosition],
        )
        if (seen.add(pair)) {
            yield(pair)
            emitted += 1
        }
    }

    var matchingPosition = 0
    while (matchingPosition + 1 < bucket.indices.size && emitted < MAX_NAME_CANDIDATE_PAIRS_PER_BUCKET) {
        emit(matchingPosition, matchingPosition + 1)
        matchingPosition += 2
    }
    if (bucket.indices.size % 2 == 1 && emitted < MAX_NAME_CANDIDATE_PAIRS_PER_BUCKET) {
        emit(bucket.indices.lastIndex, 0)
    }

    for (distance in 1 until bucket.indices.size) {
        for (firstPosition in bucket.indices.indices) {
            if (emitted >= MAX_NAME_CANDIDATE_PAIRS_PER_BUCKET) return@sequence
            val secondPosition = (firstPosition + distance) % bucket.indices.size
            if (firstPosition >= secondPosition) continue
            emit(firstPosition, secondPosition)
        }
    }
}.iterator()

private fun stableFileIdentity(item: NameDocumentFeatures): String =
    "${item.document.sourceId}\u0000${item.document.uri}"

private fun stableOrderKey(identity: String, salt: String): Long = stableHash64("$salt\u0000$identity")

private fun stableHash64(value: String): Long {
    var hash = -3_750_763_034_362_895_579L
    value.forEach { character ->
        hash = (hash xor character.code.toLong()) * 1_099_511_628_211L
    }
    return hash
}

internal data class NameCandidatePair(
    val first: Int,
    val second: Int,
) {
    init {
        require(first < second) { "名称候选索引必须按升序且不能相同" }
    }

    companion object {
        fun of(first: Int, second: Int): NameCandidatePair {
            require(first != second) { "名称候选不能引用同一文档两次" }
            return if (first < second) {
                NameCandidatePair(first, second)
            } else {
                NameCandidatePair(second, first)
            }
        }
    }
}

private fun protectedNeutralCoreKey(features: NameFeatures): String =
    features.protectedIdentifiers.fold(features.coreKey) { key, identifier ->
        when (identifier.kind) {
            ProtectedIdentifierKind.YEAR -> key
                .replace("${identifier.value}年度", "")
                .replace("${identifier.value}年", "")
                .replace(identifier.value, "")
            else -> key.replace(identifier.value, "")
        }
    }

internal const val MAX_NAME_CANDIDATE_DOCUMENTS_PER_BUCKET = 80
internal const val MAX_NAME_CANDIDATE_PAIRS = 10_000
internal const val MAX_NAME_CANDIDATES_PER_DOCUMENT = 128
internal const val MAX_NAME_CANDIDATE_PAIRS_PER_BUCKET = 512
private const val MAX_EXACT_METADATA_NEIGHBOR_DISTANCE = 8
internal const val MAX_EXACT_METADATA_CANDIDATES_PER_SIGNAL = 10_000
