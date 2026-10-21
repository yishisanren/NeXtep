package io.github.lujinxin.nextep.workspace

/** Capture permission is deliberately session-only, never a saved preference. */
internal class WorkspaceCapturePolicy {
    var captureAllowed = false
        private set

    fun allowForSession() { captureAllowed = true }
    fun reset() { captureAllowed = false }
}
