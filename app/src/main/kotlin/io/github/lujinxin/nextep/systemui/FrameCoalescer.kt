package io.github.lujinxin.nextep.systemui

/** The UI thread keeps the latest touch point; only one update is posted per frame. */
internal class FrameCoalescer(
    private val post: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val update: () -> Unit,
) {
    private var pending = false
    private val frame = Runnable {
        if (pending) {
            pending = false
            update()
        }
    }

    fun request() {
        if (pending) return
        pending = true
        post(frame)
    }

    fun cancel() {
        if (!pending) return
        pending = false
        remove(frame)
    }
}
