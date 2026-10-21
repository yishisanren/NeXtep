package io.github.lujinxin.nextep.workspace

/** An Activity launch is accepted before its task exists. Move it only after
 * observing the requested foreground task, then start the next application. */
internal class WorkspaceLaunchSequence<T>(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val start: (T) -> Unit,
    private val ready: (T) -> Boolean,
    private val commit: (T) -> Unit,
    private val busy: (Boolean) -> Unit,
) {
    class Failure(val index: Int, val completed: Int, cause: Throwable) : Exception(cause)
    var running = false
        private set
    private var generation = 0
    private var completion: ((Result<Unit>) -> Unit)? = null

    fun begin(requests: List<T>, completed: (Result<Unit>) -> Unit): Result<Unit> {
        if (running) return Result.failure(IllegalStateException("An application launch is in progress"))
        return runCatching {
            require(requests.isNotEmpty())
            val pending = requests.toList()
            val token = ++generation
            running = true
            completion = completed
            busy(true)
            fun finish(result: Result<Unit>) {
                if (generation != token || !running) return
                running = false
                val callback = completion
                completion = null
                val release = runCatching { busy(false) }
                callback?.invoke(if (release.isFailure) Result.failure(checkNotNull(release.exceptionOrNull())) else result)
            }
            fun next(index: Int) {
                if (generation != token || !running) return
                val request = pending[index]
                fun fail(error: Throwable) = finish(Result.failure(Failure(index, index, error)))
                try { start(request) } catch (error: Throwable) { fail(error); return }
                fun poll(attempt: Int) {
                    if (generation != token || !running) return
                    try {
                        if (ready(request)) {
                            commit(request)
                            if (index == pending.lastIndex) finish(Result.success(Unit))
                            else schedule(100L) { next(index + 1) }
                        } else if (attempt >= 50) {
                            fail(java.util.concurrent.TimeoutException("Application did not produce a foreground task"))
                        } else schedule(100L) { poll(attempt + 1) }
                    } catch (error: Throwable) { fail(error) }
                }
                schedule(150L) { poll(0) }
            }
            schedule(0L) { next(0) }
        }.onFailure {
            running = false
            completion = null
            runCatching { busy(false) }
        }
    }

    fun cancel() {
        generation++
        if (!running) return
        running = false
        val callback = completion
        completion = null
        runCatching { busy(false) }
        callback?.invoke(Result.failure(java.util.concurrent.CancellationException()))
    }
}
