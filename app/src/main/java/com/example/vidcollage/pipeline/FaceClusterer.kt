package com.example.vidcollage.pipeline

/**
 * Agglomerative (average-linkage) clustering of appearances into people.
 *
 * Two extras on top of textbook average linkage:
 *  - clustering runs on appearance centroids, not single faces, so one bad frame cannot split a person;
 *  - appearances that are on screen at the same moment get a cannot-link constraint, because two faces
 *    visible in the same frame are by definition two different people.
 */
object FaceClusterer {

    /**
     * Cosine similarity above which two appearances are considered the same person.
     *
     * FaceNet embeddings put same-person pairs comfortably above this and different-person pairs
     * below it; averaging over a whole appearance widens that gap further.
     */
    const val MERGE_SIMILARITY = 0.55f

    fun cluster(
        appearances: List<Appearance>,
        mergeSimilarity: Float = MERGE_SIMILARITY,
    ): List<List<Appearance>> {
        if (appearances.isEmpty()) return emptyList()

        val clusters = appearances.map { mutableListOf(it) }.toMutableList()

        while (clusters.size > 1) {
            var bestSimilarity = mergeSimilarity
            var bestA = -1
            var bestB = -1

            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    if (sharesAMoment(clusters[i], clusters[j])) continue
                    val similarity = averageLinkage(clusters[i], clusters[j])
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity
                        bestA = i
                        bestB = j
                    }
                }
            }

            if (bestA < 0) break
            clusters[bestA].addAll(clusters[bestB])
            clusters.removeAt(bestB)
        }

        return clusters
            .onEach { members -> members.sortBy { it.startMs } }
            .sortedBy { members -> members.first().startMs }
    }

    private fun averageLinkage(a: List<Appearance>, b: List<Appearance>): Float {
        var total = 0f
        for (x in a) {
            for (y in b) total += Embeddings.cosineSimilarity(x.centroid, y.centroid)
        }
        return total / (a.size * b.size)
    }

    /** True when any appearance of [a] is on screen at the same time as any appearance of [b]. */
    private fun sharesAMoment(a: List<Appearance>, b: List<Appearance>): Boolean =
        a.any { first -> b.any { second -> first.overlaps(second) } }
}
