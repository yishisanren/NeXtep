package io.github.lujinxin.nextep.framework

/** Commits native transition writes and the currently owned workspace transform together. */
internal class SurfaceTransactionGuard<T : Any>(
    private val onFailure: (Throwable) -> Unit = {},
    private val shouldCompose: () -> Boolean = { true },
    private val appendOwnedTransforms: (T) -> Unit,
) {
    private val composing = ThreadLocal.withInitial { false }

    fun <R> intercept(transaction: T, proceed: () -> R): R {
        if (!shouldCompose() || composing.get() == true) return proceed()
        return ownedTransaction {
            // An unsupported native transaction must still commit. Nested apply overloads
            // and our own animation/restore transactions pass through without composition.
            runCatching { appendOwnedTransforms(transaction) }
                .onFailure { runCatching { onFailure(it) } }
            proceed()
        }
    }

    fun <R> ownedTransaction(block: () -> R): R {
        val previous = composing.get() == true
        composing.set(true)
        return try { block() } finally { composing.set(previous) }
    }
}
