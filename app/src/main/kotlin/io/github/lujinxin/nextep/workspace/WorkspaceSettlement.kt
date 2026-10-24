package io.github.lujinxin.nextep.workspace

/** Wait for Shell to expose the new task surfaces without blocking its own Looper. */
internal class WorkspaceSettlement(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val now: () -> Long,
) {
    private var generation = 0

    fun cancel() { generation++ }

    fun begin(ready: () -> Boolean, timeoutMs: Long = 1_500L, completed: (Result<Unit>) -> Unit) {
        val token = ++generation
        val deadline = now() + timeoutMs
        fun poll() {
            if (token != generation) return
            val result = runCatching { ready() }
            when {
                result.isFailure -> completed(Result.failure(checkNotNull(result.exceptionOrNull())))
                result.getOrThrow() -> completed(Result.success(Unit))
                now() >= deadline -> completed(Result.failure(
                    java.util.concurrent.TimeoutException("Window exchange did not settle")))
                else -> schedule(32L, ::poll)
            }
        }
        schedule(0L, ::poll)
    }
}
