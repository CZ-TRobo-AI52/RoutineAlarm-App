package com.cztr.routinealarm.r52.skill

data class SkillRequest(
    val tokens: Set<String>,
    val requiredCapabilities: Set<String>,
    val excludedSkillIds: Set<String> = emptySet(),
)

data class SkillCandidate(
    val descriptor: SkillDescriptor,
    val score: Int,
    val matchedPositiveTriggers: Set<String>,
    val matchedCapabilities: Set<String>,
)

data class SkillComposition(
    val selected: List<SkillCandidate>,
    val coveredCapabilities: Set<String>,
    val uncoveredCapabilities: Set<String>,
)

class SkillRouter(private val registry: SkillRegistry) {
    /**
     * Resolve the complete dependency closure before a skill can be advertised.
     * Dependencies need not match positive triggers: they are prerequisites, not entry points.
     * Missing, excluded, below-state, negatively triggered or cyclic prerequisites fail closed.
     * The returned order is topological, with the root as the final element.
     */
    private fun executableOrder(
        root: SkillDescriptor,
        request: SkillRequest,
        minState: SkillLifecycleState,
    ): List<SkillDescriptor>? {
        val tokens = request.tokens.normalized()
        val visiting = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val order = mutableListOf<SkillDescriptor>()

        fun visit(id: String): Boolean {
            if (id in completed) return true
            if (id in visiting) return false
            if (id in request.excludedSkillIds) return false
            val descriptor = registry.get(id) ?: return false
            if (!descriptor.state.isAtLeast(minState)) return false
            if (descriptor.negativeTriggers.normalized().any(tokens::contains)) return false
            visiting += id
            for (dependency in descriptor.dependencies.sorted()) {
                if (!visit(dependency)) return false
            }
            visiting -= id
            completed += id
            order += descriptor
            return true
        }
        return if (visit(root.id)) order else null
    }

    fun rank(
        request: SkillRequest,
        minState: SkillLifecycleState = SkillLifecycleState.IMPLEMENTED,
    ): List<SkillCandidate> {
        val tokens = request.tokens.normalized()
        val requiredCapabilities = request.requiredCapabilities.normalized()

        return registry.all()
            .asSequence()
            .filter { it.id !in request.excludedSkillIds }
            .filter { it.state.isAtLeast(minState) }
            .mapNotNull { descriptor ->
                val negative = descriptor.negativeTriggers.normalized()
                if (negative.any(tokens::contains)) return@mapNotNull null
                if (executableOrder(descriptor, request, minState) == null) return@mapNotNull null

                val positiveMatches = descriptor.positiveTriggers.normalized().intersect(tokens)
                val capabilityMatches = descriptor.capabilities.normalized().intersect(requiredCapabilities)
                val score = positiveMatches.size * TRIGGER_WEIGHT + capabilityMatches.size * CAPABILITY_WEIGHT
                if (score <= 0) return@mapNotNull null

                SkillCandidate(
                    descriptor = descriptor,
                    score = score,
                    matchedPositiveTriggers = positiveMatches,
                    matchedCapabilities = capabilityMatches,
                )
            }
            .sortedWith(
                compareByDescending<SkillCandidate> { it.score }
                    .thenByDescending { it.descriptor.version }
                    .thenBy { it.descriptor.id },
            )
            .toList()
    }

    /**
     * Retain C010 greedy behavior when it covers the goal; otherwise apply a bounded
     * dependency-aware lookahead. An incomplete search is never allowed to degrade it.
     * This plans only: execution must revalidate registry state before side effects.
     */
    fun compose(
        request: SkillRequest,
        maxSkills: Int = 3,
        minState: SkillLifecycleState = SkillLifecycleState.IMPLEMENTED,
    ): SkillComposition {
        val baseline = greedyCompose(request, maxSkills, minState)
        if (baseline.uncoveredCapabilities.isEmpty()) {
            return compactCompletePlan(request, minState, baseline)
        }
        val alternative = lookaheadCompose(request, maxSkills, minState)
        return when {
            alternative.coveredCapabilities.size > baseline.coveredCapabilities.size -> alternative
            alternative.coveredCapabilities.size == baseline.coveredCapabilities.size &&
                alternative.selected.size < baseline.selected.size -> alternative
            else -> baseline
        }
    }

    /**
     * C012: A complete greedy solution can still waste execution slots. Inspect at most
     * 32 ranked roots and their valid dependency closures for a strictly shorter
     * one-root or two-root plan, then at most 560 three-root combinations when a
     * four-or-more-skill baseline remains. This is a bounded advisory optimization,
     * not a globally optimal set-cover claim or an authorization to execute effects.
     */
    private fun compactCompletePlan(
        request: SkillRequest,
        minState: SkillLifecycleState,
        baseline: SkillComposition,
    ): SkillComposition {
        if (baseline.selected.size <= 2) return baseline
        val required = request.requiredCapabilities.normalized()
        val ranked = rank(request, minState)
        val byId = ranked.associateBy { it.descriptor.id }
        val closures = ranked.asSequence().take(COMPACT_ROOT_LIMIT).mapNotNull { root ->
            executableOrder(root.descriptor, request, minState)?.let { root to it }
        }.toList()
        var best = baseline

        fun proposal(order: List<SkillDescriptor>): SkillComposition? {
            if (order.size >= best.selected.size) return null
            val covered = order.asSequence().flatMap { it.capabilities.normalized().asSequence() }
                .toSet().intersect(required)
            if (covered != required) return null
            val selected = order.map { descriptor ->
                byId[descriptor.id] ?: SkillCandidate(descriptor, 0, emptySet(), emptySet())
            }
            return SkillComposition(selected, covered, emptySet())
        }

        for ((_, closure) in closures) {
            proposal(closure)?.let { best = it }
            if (best.selected.size == 1) return best
        }
        if (best.selected.size == 2) return best
        for (i in closures.indices) {
            val first = closures[i].second
            val firstIds = first.mapTo(mutableSetOf()) { it.id }
            for (j in i + 1 until closures.size) {
                val combined = first + closures[j].second.filterNot { it.id in firstIds }
                proposal(combined)?.let { best = it }
                if (best.selected.size == 2) return best
            }
        }
        // C014: independently falsified 4->3 compaction gap. Restrict this third level
        // to the top 16 eligible roots (at most C(16,3)=560 triples), and execute it
        // only if the best complete plan still uses four or more skills.
        if (best.selected.size > 3) {
            val tripleRoots = closures.take(TRIPLE_ROOT_LIMIT).map { it.second }
            for (i in tripleRoots.indices) {
                val first = tripleRoots[i]
                val firstIds = first.mapTo(mutableSetOf()) { it.id }
                for (j in i + 1 until tripleRoots.size) {
                    val second = first + tripleRoots[j].filterNot { it.id in firstIds }
                    if (second.size >= best.selected.size) continue
                    val secondIds = second.mapTo(mutableSetOf()) { it.id }
                    for (k in j + 1 until tripleRoots.size) {
                        val combined = second + tripleRoots[k].filterNot { it.id in secondIds }
                        proposal(combined)?.let { best = it }
                        if (best.selected.size == 3) return best
                    }
                }
            }
        }
        // C031: bounded exact compaction closes the remaining 5->4 gap.
        // Only a small registry (<=12 eligible roots) can enter this branch;
        // all previous one-, two- and three-root checks already ran.
        // Subset exploration is advisory and can only replace a complete plan
        // with a strictly smaller complete plan. Never authorizes execution.
        if (best.selected.size > 4 && closures.size <= EXACT_COMPACT_ROOT_LIMIT) {
            val orders = closures.map { it.second }
            for (mask in 1 until (1 shl orders.size)) {
                if (Integer.bitCount(mask) >= best.selected.size) continue
                val seen = mutableSetOf<String>()
                val combined = mutableListOf<SkillDescriptor>()
                for (i in orders.indices) {
                    if ((mask and (1 shl i)) == 0) continue
                    for (descriptor in orders[i]) {
                        if (seen.add(descriptor.id)) combined += descriptor
                    }
                }
                proposal(combined)?.let { best = it }
                // All three-root combinations have been checked for this
                // small registry, so 4 is the shortest remaining solution.
                if (best.selected.size == 4) return best
            }
        }
        return best
    }

    private data class LookaheadPlan(
        val selected: List<SkillCandidate>,
        val ids: Set<String>,
        val covered: Set<String>,
        val rootScore: Int,
    )

    /** Beam search is bounded in candidate count, width and productive depth. */
    private fun lookaheadCompose(
        request: SkillRequest,
        maxSkills: Int,
        minState: SkillLifecycleState,
    ): SkillComposition {
        val required = request.requiredCapabilities.normalized()
        val candidates = rank(request, minState).take(LOOKAHEAD_ROOT_LIMIT)
        val byId = candidates.associateBy { it.descriptor.id }
        val bundles = candidates.mapNotNull { candidate ->
            executableOrder(candidate.descriptor, request, minState)
                ?.let { candidate to it }
        }
        val ordering = compareBy<LookaheadPlan> { it.covered.size }
            .thenBy { -it.selected.size }
            .thenBy { it.rootScore }
            .thenByDescending { plan -> plan.selected.joinToString("|") { it.descriptor.id } }
        val empty = LookaheadPlan(emptyList(), emptySet(), emptySet(), 0)
        var best = empty
        var frontier = listOf(empty)

        // Each productive expansion must gain at least one required capability.
        repeat(minOf(required.size, maxSkills)) {
            val expansions = mutableListOf<LookaheadPlan>()
            for (plan in frontier) {
                for ((root, closure) in bundles) {
                    val additions = closure.filterNot { it.id in plan.ids }
                    if (additions.isEmpty() || plan.selected.size + additions.size > maxSkills) continue
                    val gain = additions.flatMap { it.capabilities.normalized() }
                        .toSet().intersect(required - plan.covered)
                    if (gain.isEmpty()) continue
                    val selected = plan.selected + additions.map { descriptor ->
                        byId[descriptor.id] ?: SkillCandidate(descriptor, 0, emptySet(), emptySet())
                    }
                    expansions += LookaheadPlan(
                        selected, plan.ids + additions.map { it.id }, plan.covered + gain,
                        plan.rootScore + root.score,
                    )
                }
            }
            if (expansions.isEmpty()) return@repeat
            frontier = expansions
                .groupBy { it.ids.sorted() }
                .values.map { group -> group.maxWith(ordering) }
                .sortedWith(ordering.reversed())
                .take(LOOKAHEAD_BEAM_WIDTH)
            val roundBest = frontier.first()
            if (ordering.compare(roundBest, best) > 0) best = roundBest
        }
        return SkillComposition(best.selected, best.covered, required - best.covered)
    }

    private fun greedyCompose(
        request: SkillRequest,
        maxSkills: Int = 3,
        minState: SkillLifecycleState = SkillLifecycleState.IMPLEMENTED,
    ): SkillComposition {
        require(maxSkills > 0) { "maxSkills must be positive" }
        val required = request.requiredCapabilities.normalized()
        val candidates = rank(request, minState).toMutableList()
        val selected = mutableListOf<SkillCandidate>()
        val selectedIds = linkedSetOf<String>()
        val covered = linkedSetOf<String>()

        while (selected.size < maxSkills) {
            val missing = required - covered
            if (missing.isEmpty()) break

            data class Option(val candidate: SkillCandidate, val additions: List<SkillDescriptor>, val gain: Set<String>)
            val best = candidates.mapNotNull { candidate ->
                val order = executableOrder(candidate.descriptor, request, minState) ?: return@mapNotNull null
                val additions = order.filterNot { it.id in selectedIds }
                if (selected.size + additions.size > maxSkills) return@mapNotNull null
                val gain = additions.flatMap { it.capabilities.normalized() }.toSet().intersect(missing)
                if (gain.isEmpty()) return@mapNotNull null
                Option(candidate, additions, gain)
            }.maxWithOrNull(
                compareBy<Option> { it.gain.size }
                    .thenBy { -it.additions.size }
                    .thenBy { it.candidate.score }
                    .thenBy { it.candidate.descriptor.version }
                    .thenByDescending { it.candidate.descriptor.id },
            ) ?: break

            for (descriptor in best.additions) {
                val existing = candidates.firstOrNull { it.descriptor.id == descriptor.id }
                selected += existing ?: SkillCandidate(descriptor, 0, emptySet(), emptySet())
                selectedIds += descriptor.id
                covered += descriptor.capabilities.normalized().intersect(required)
            }
            candidates.removeAll { it.descriptor.id in selectedIds }
        }

        return SkillComposition(
            selected = selected,
            coveredCapabilities = covered.toSet(),
            uncoveredCapabilities = required - covered,
        )
    }

    private fun Set<String>.normalized(): Set<String> =
        asSequence().map(String::trim).filter(String::isNotEmpty).map(String::lowercase).toSet()

    private companion object {
        const val TRIGGER_WEIGHT = 10
        const val CAPABILITY_WEIGHT = 4
        const val LOOKAHEAD_ROOT_LIMIT = 128
        const val COMPACT_ROOT_LIMIT = 32
        const val TRIPLE_ROOT_LIMIT = 16
        const val EXACT_COMPACT_ROOT_LIMIT = 12
        const val LOOKAHEAD_BEAM_WIDTH = 64
    }
}
