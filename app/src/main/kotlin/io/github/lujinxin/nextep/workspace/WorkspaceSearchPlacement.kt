package io.github.lujinxin.nextep.workspace

/** Reserve destinations before launching anything; never replace unrelated slots. */
internal object WorkspaceSearchPlacement {
    class NoSpace : IllegalStateException("Not enough empty slots")
    fun plan(packages: List<String>, slots: List<String?>, promotedSlot: Int? = null): List<Int> {
        require(packages.size in 2..5 && packages.distinct().size == packages.size)
        val available = slots.toMutableList()
        promotedSlot?.let { index ->
            require(index in available.indices && available[index] == packages.first())
            available[index] = null
        }
        val reserved = mutableSetOf<Int>()
        return packages.drop(1).map { name ->
            val existing = available.indexOfFirst { it == name }
            val index = if (existing >= 0) existing else available.indices.firstOrNull {
                available[it] == null && it !in reserved
            } ?: throw NoSpace()
            check(reserved.add(index))
            index
        }
    }
}
