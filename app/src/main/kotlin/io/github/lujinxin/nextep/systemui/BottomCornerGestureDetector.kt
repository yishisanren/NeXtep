package io.github.lujinxin.nextep.systemui

class BottomCornerGestureDetector(density: Float) {
    enum class Result { IGNORED, OBSERVING, ABANDONED, CONFIRMED }

    private val startSize = 72f * density
    private val minimumDistance = 32f * density
    private val directionSlop = 12f * density
    private var observing = false
    private var fromLeft = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downWidth = 0
    private var downHeight = 0
    private var downRotation = 0

    val isObserving: Boolean get() = observing

    fun reset() { observing = false }

    fun observe(
        action: Int,
        x: Float,
        y: Float,
        pointerCount: Int,
        eventTime: Long,
        screenWidth: Int,
        screenHeight: Int,
        rotation: Int = 0,
    ): Result {
        if (pointerCount != 1 || !x.isFinite() || !y.isFinite() ||
            screenWidth <= 0 || screenHeight <= 0 ||
            x !in 0f..screenWidth.toFloat() || y !in 0f..screenHeight.toFloat()
        ) return finish(Result.ABANDONED)

        if (action == ACTION_DOWN) {
            val cornerWidth = minOf(startSize, screenWidth / 4f)
            val cornerHeight = minOf(startSize, screenHeight / 4f)
            observing = y >= screenHeight - cornerHeight &&
                (x <= cornerWidth || x >= screenWidth - cornerWidth)
            if (!observing) return Result.IGNORED
            fromLeft = x <= cornerWidth
            downX = x
            downY = y
            downTime = eventTime
            downWidth = screenWidth
            downHeight = screenHeight
            downRotation = rotation
            return Result.OBSERVING
        }
        if (!observing) return Result.IGNORED
        if (screenWidth != downWidth || screenHeight != downHeight || rotation != downRotation ||
            eventTime - downTime !in 0L..1_200L
        ) return finish(Result.ABANDONED)

        if (action == ACTION_MOVE || action == ACTION_UP) {
            val inward = if (fromLeft) x - downX else downX - x
            val upward = downY - y
            if (inward < -directionSlop || upward < -directionSlop) {
                return finish(Result.ABANDONED)
            }
            if (inward >= minimumDistance && upward >= minimumDistance &&
                inward <= upward * 2f && upward <= inward * 2f
            ) return finish(Result.CONFIRMED)
            if (maxOf(inward, upward) >= minimumDistance &&
                minOf(inward, upward) < maxOf(inward, upward) / 3f
            ) return finish(Result.ABANDONED)
        }
        return if (action == ACTION_MOVE) Result.OBSERVING else finish(Result.ABANDONED)
    }

    private fun finish(result: Result): Result {
        if (!observing) return Result.IGNORED
        observing = false
        return result
    }

    private companion object {
        // Android MotionEvent action values keep recognition testable without a device.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }
}
