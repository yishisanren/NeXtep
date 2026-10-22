package io.github.lujinxin.nextep.workspace

/** Reserve existing matches first, then empty or replaceable windows. */
internal object WorkspaceSearchPlacement {
    class NoSpace : IllegalStateException("Not enough empty slots")
    fun plan(packages: List<String>, slots: List<String?>, promotedSlot: Int? = null,
        unavailable: Set<Int> = emptySet()): List<Int> {
        require(packages.size in 1..5 && packages.distinct().size == packages.size)
        val available = slots.toMutableList()
        promotedSlot?.let { index ->
            require(index in available.indices && available[index] == packages.first())
            available[index] = null
        }
        val targets = packages.drop(1)
        val matches = targets.associateWith { name ->
            available.indices.firstOrNull { it !in unavailable && available[it] == name }
        }
        val reserved = matches.values.filterNotNull().toMutableSet()
        return targets.map { name ->
            matches[name]?.let { return@map it }
            val candidates = available.indices.filter { it !in reserved && it !in unavailable }
            val index = candidates.firstOrNull { available[it] == null }
                ?: candidates.firstOrNull() ?: throw NoSpace()
            check(reserved.add(index))
            index
        }
    }
}
