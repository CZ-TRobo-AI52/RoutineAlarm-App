package com.cztr.routinealarm.r52.skill

enum class SkillLifecycleState {
    PROPOSED,
    IMPLEMENTED,
    TESTED,
    BENCHMARKED,
    DEPLOYED,
    VERIFIED;

    fun isAtLeast(other: SkillLifecycleState): Boolean = ordinal >= other.ordinal
}

data class SkillVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SkillVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "skill version parts must be non-negative" }
    }

    override fun compareTo(other: SkillVersion): Int =
        compareValuesBy(this, other, SkillVersion::major, SkillVersion::minor, SkillVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

data class SkillProvenance(
    val parentVersion: SkillVersion?,
    val hypothesisId: String?,
    val evalIds: Set<String>,
    val changeReason: String,
) {
    init {
        require(changeReason.isNotBlank()) { "changeReason must not be blank" }
    }
}

data class SkillDescriptor(
    val id: String,
    val version: SkillVersion,
    val purpose: String,
    val positiveTriggers: Set<String>,
    val negativeTriggers: Set<String>,
    val capabilities: Set<String>,
    val dependencies: Set<String>,
    val state: SkillLifecycleState,
    val provenance: SkillProvenance,
) {
    init {
        require(id.isNotBlank()) { "skill id must not be blank" }
        require(purpose.isNotBlank()) { "skill purpose must not be blank" }
        require(id !in dependencies) { "skill cannot depend on itself" }
    }
}
