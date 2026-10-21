package io.github.lujinxin.nextep.systemui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MediaControlView(
    context: Context,
    private val onSessionClicked: (MediaController) -> Unit,
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val artwork = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val title = mediaText(13f, Color.WHITE)
    private val artist = mediaText(10f, Color.rgb(188, 194, 204))
    private val previous = controlButton(PlaybackIcon.PREVIOUS, AppLanguage.string(context, R.string.media_previous)) { transport { skipToPrevious() } }
    private val playPause = controlButton(PlaybackIcon.PLAY, AppLanguage.string(context, R.string.media_play)) { togglePlayback() }
    private val next = controlButton(PlaybackIcon.NEXT, AppLanguage.string(context, R.string.media_next)) { transport { skipToNext() } }
    private val cardBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        orientation = GradientDrawable.Orientation.TL_BR
    }
    private var wallpaperLight = false
    private var artworkSource: Bitmap? = null
    private var artworkSourceGeneration = 0
    private var paletteGeneration = 0
    private var paletteRequest: Future<*>? = null
    private var albumColors: MediaArtworkPalette.Colors? = null
    private var mediaController: MediaController? = null
    private var listening = false
    private val periodicRefresh = object : Runnable {
        override fun run() {
            if (!listening) return
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        handler.post { bindBestController(sessions) }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
        override fun onPlaybackStateChanged(state: PlaybackState?) = render()
        override fun onSessionDestroyed() = refresh()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = cardBackground
        addView(artwork, LayoutParams(dp(46), dp(46)))
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(4), 0)
                addView(title, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                addView(artist, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        addView(previous, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        addView(playPause, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        addView(next, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        setOnClickListener { openSession() }
        renderNoMedia()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val compact = MeasureSpec.getSize(heightMeasureSpec) < dp(48)
            setPadding(dp(8), if (compact) 0 else dp(5), dp(8), if (compact) 0 else dp(5))
            artist.visibility = if (compact) GONE else VISIBLE
            artwork.visibility = if (compact) GONE else VISIBLE
            previous.visibility = if (compact) GONE else VISIBLE
            next.visibility = if (compact) GONE else VISIBLE
            val artworkSize = minOf(dp(46),
                (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(1))
            artwork.layoutParams.width = artworkSize
            artwork.layoutParams.height = artworkSize
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setWorkspaceVisible(visible: Boolean) {
        if (visible) startListening() else stopListening()
    }

    fun setWallpaperLight(light: Boolean) {
        if (wallpaperLight == light) return
        wallpaperLight = light
        applyCardAppearance(albumColors)
    }

    fun refresh() {
        if (!listening) return
        runCatching { bindBestController(sessionManager?.getActiveSessions(null)) }
            .onFailure {
                NeXtepLog.warn("media_control", "Unable to query active sessions", it)
                bindBestController(null)
            }
    }

    fun refreshLanguage() {
        previous.contentDescription = AppLanguage.string(context, R.string.media_previous)
        next.contentDescription = AppLanguage.string(context, R.string.media_next)
        render()
    }

    override fun onDetachedFromWindow() {
        stopListening()
        super.onDetachedFromWindow()
    }

    private fun startListening() {
        if (listening) {
            refresh()
            return
        }
        val manager = sessionManager ?: run {
            renderNoMedia()
            return
        }
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
            listening = true
            refresh()
            handler.removeCallbacks(periodicRefresh)
            handler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS)
        }.onFailure {
            NeXtepLog.warn("media_control", "Active-session listener unavailable", it)
            renderNoMedia()
        }
    }

    private fun stopListening() {
        if (listening) {
            runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        }
        listening = false
        handler.removeCallbacks(periodicRefresh)
        bindController(null)
    }

    private fun bindBestController(controllers: List<MediaController>?) {
        bindController(
            controllers.orEmpty()
                .filter(::isUsableController)
                .maxByOrNull(::controllerScore),
        )
    }

    private fun bindController(controller: MediaController?) {
        if (sameSession(mediaController, controller)) {
            render()
            return
        }
        mediaController?.let { current ->
            runCatching { current.unregisterCallback(controllerCallback) }
        }
        mediaController = controller
        controller?.let { current ->
            runCatching { current.registerCallback(controllerCallback, handler) }
                .onFailure { NeXtepLog.warn("media_control", "Callback registration failed", it) }
        }
        render()
    }

    private fun render() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(::render)
            return
        }
        val controller = mediaController ?: run {
            renderNoMedia()
            return
        }
        val metadata = runCatching { controller.metadata }.getOrNull()
        val state = runCatching { controller.playbackState }.getOrNull()
        val appLabel = applicationLabel(controller.packageName)
        title.text = firstText(
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            appLabel,
            AppLanguage.string(context, R.string.media_not_playing),
        )
        artist.text = firstText(
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            appLabel,
            AppLanguage.string(context, R.string.media_control_title),
        )
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val usableArt = art?.takeUnless { it.isRecycled }
        artwork.setImageDrawable(
            usableArt?.let { BitmapDrawable(resources, it) }
                ?: runCatching { context.packageManager.getApplicationIcon(controller.packageName) }
                    .getOrNull(),
        )
        updateArtworkPalette(usableArt)
        val actions = state?.actions ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING
        setControlEnabled(previous, actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        setControlEnabled(next, actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        setControlEnabled(
            playPause,
            actions and (
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE
                ) != 0L || state != null,
        )
        playPause.setIcon(if (playing) PlaybackIcon.PAUSE else PlaybackIcon.PLAY)
        playPause.contentDescription = if (playing) AppLanguage.string(context, R.string.media_pause) else AppLanguage.string(context, R.string.media_play)
        alpha = 1f
    }

    private fun renderNoMedia() {
        title.text = AppLanguage.string(context, R.string.media_not_playing)
        artist.text = AppLanguage.string(context, R.string.media_empty_hint)
        artwork.setImageDrawable(null)
        setControlEnabled(previous, false)
        setControlEnabled(playPause, false)
        setControlEnabled(next, false)
        playPause.setIcon(PlaybackIcon.PLAY)
        playPause.contentDescription = AppLanguage.string(context, R.string.media_play)
        resetArtworkPalette()
        alpha = 1f
    }

    private fun updateArtworkPalette(art: Bitmap?) {
        if (art == null) {
            resetArtworkPalette()
            return
        }
        val sourceGeneration = art.generationId
        if (artworkSource === art && artworkSourceGeneration == sourceGeneration) return
        paletteRequest?.cancel(false)
        val generation = ++paletteGeneration
        artworkSource = art
        artworkSourceGeneration = sourceGeneration
        // Keep the current palette while a replacement is sampled, avoiding a flash on each track.
        paletteRequest = artworkWorker.submit {
            val colors = runCatching { MediaArtworkPalette.extract(art) }.getOrNull()
            handler.post {
                if (generation != paletteGeneration || artworkSource !== art ||
                    art.isRecycled || art.generationId != sourceGeneration) return@post
                paletteRequest = null
                applyCardAppearance(colors)
            }
        }
    }

    private fun resetArtworkPalette() {
        ++paletteGeneration
        paletteRequest?.cancel(false)
        paletteRequest = null
        artworkSource = null
        artworkSourceGeneration = 0
        applyCardAppearance(null)
    }

    private fun applyCardAppearance(colors: MediaArtworkPalette.Colors?) {
        albumColors = colors
        val darkText = colors == null && wallpaperLight
        if (colors == null) {
            // The parent already draws the frosted wallpaper. Reveal it rather than blurring twice.
            cardBackground.colors = null
            cardBackground.setColor(if (darkText) Color.argb(48, 255, 255, 255)
                else Color.argb(70, 16, 24, 34))
        } else {
            cardBackground.colors = intArrayOf(colors.start, colors.end)
        }
        cardBackground.setStroke(dp(1), if (darkText) Color.argb(38, 27, 39, 54)
            else Color.argb(55, 255, 255, 255))
        val foreground = if (darkText) Color.rgb(26, 34, 46) else Color.WHITE
        title.setTextColor(foreground)
        artist.setTextColor(if (darkText) Color.rgb(73, 83, 98) else Color.rgb(220, 229, 238))
        previous.setGlyphColor(foreground)
        playPause.setGlyphColor(foreground)
        next.setGlyphColor(foreground)
    }

    private fun togglePlayback() {
        val controller = mediaController ?: return
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING ||
            controller.playbackState?.state == PlaybackState.STATE_BUFFERING
        runCatching {
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }.onFailure { NeXtepLog.warn("media_control", "Play/pause failed", it) }
    }

    private fun transport(block: MediaController.TransportControls.() -> Unit) {
        val controls = mediaController?.transportControls ?: return
        runCatching { controls.block() }
            .onFailure { NeXtepLog.warn("media_control", "Transport action failed", it) }
    }

    private fun openSession() {
        val controller = mediaController ?: return
        runCatching { onSessionClicked(controller) }
            .onFailure { NeXtepLog.warn("media_control", "Session Activity failed", it) }
    }

    private fun controllerScore(controller: MediaController): Int {
        val state = runCatching { controller.playbackState?.state }.getOrNull()
        val metadata = runCatching { controller.metadata }.getOrNull()
        val playbackScore = when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> 8
            PlaybackState.STATE_PAUSED -> 3
            PlaybackState.STATE_NONE, null -> 0
            else -> 1
        }
        val metadataScore = if (
            !metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
            !metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNullOrBlank()
        ) 4 else 0
        return playbackScore + metadataScore
    }

    private fun isUsableController(controller: MediaController): Boolean {
        val state = runCatching { controller.playbackState }.getOrNull() ?: return false
        if (state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_BUFFERING ||
            state.state == PlaybackState.STATE_CONNECTING ||
            state.state == PlaybackState.STATE_PAUSED ||
            state.state == PlaybackState.STATE_FAST_FORWARDING ||
            state.state == PlaybackState.STATE_REWINDING ||
            state.state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
            state.state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
            state.state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
        ) {
            return true
        }
        val transportActions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if (state.state == PlaybackState.STATE_ERROR ||
            state.actions and transportActions == 0L
        ) {
            return false
        }
        return state.state != PlaybackState.STATE_STOPPED ||
            isPackageVisible(controller.packageName)
    }

    private fun isPackageVisible(packageName: String): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            .orEmpty()
            .any { process ->
                process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
                    process.pkgList?.contains(packageName) == true
            }
    }.getOrDefault(false)

    private fun sameSession(left: MediaController?, right: MediaController?): Boolean =
        left === right || left != null && right != null && runCatching {
            left.packageName == right.packageName && left.sessionToken == right.sessionToken
        }.getOrDefault(false)

    private fun applicationLabel(packageName: String): String? = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrNull()

    private fun mediaText(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
    }

    private fun controlButton(
        icon: PlaybackIcon,
        description: String,
        action: () -> Unit,
    ) = PlaybackControlButton(context, icon).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun setControlEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.34f
    }

    private fun firstText(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class PlaybackIcon { PREVIOUS, PLAY, PAUSE, NEXT }

    /** Shared geometry keeps transport icons independent of fonts and text scaling. */
    private class PlaybackControlButton(context: Context, initialIcon: PlaybackIcon) : View(context) {
        private val density = resources.displayMetrics.density
        private val glyph = Path()
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            pathEffect = CornerPathEffect(0.6f)
        }
        private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(30, 255, 255, 255)
        }
        private var icon = initialIcon

        init {
            updateGlyph()
        }

        fun setIcon(value: PlaybackIcon) {
            if (icon == value) return
            icon = value
            updateGlyph()
            invalidate()
        }

        fun setGlyphColor(color: Int) {
            if (glyphPaint.color == color) return
            glyphPaint.color = color
            feedbackPaint.color = ColorUtils.setAlphaComponent(color, 30)
            invalidate()
        }

        private fun updateGlyph() {
            glyph.reset()
            when (icon) {
                PlaybackIcon.PLAY -> {
                    // Center the triangle by its visual weight, rather than its bounding box.
                    glyph.moveTo(8f, 4f)
                    glyph.lineTo(20f, 12f)
                    glyph.lineTo(8f, 20f)
                    glyph.close()
                }
                PlaybackIcon.PAUSE -> {
                    glyph.addRect(6.5f, 4f, 10f, 20f, Path.Direction.CW)
                    glyph.addRect(14f, 4f, 17.5f, 20f, Path.Direction.CW)
                }
                PlaybackIcon.NEXT, PlaybackIcon.PREVIOUS -> {
                    val previous = icon == PlaybackIcon.PREVIOUS
                    fun x(value: Float) = if (previous) 24f - value else value
                    glyph.moveTo(x(4.5f), 4f)
                    glyph.lineTo(x(15.5f), 12f)
                    glyph.lineTo(x(4.5f), 20f)
                    glyph.close()
                    val barLeft = if (previous) 3.5f else 17.5f
                    glyph.addRect(barLeft, 4f, barLeft + 3f, 20f, Path.Direction.CW)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = minOf(24f * density, width.toFloat(), height.toFloat())
            if (size <= 0f) return
            if (isEnabled && (isPressed || isFocused)) {
                canvas.drawCircle(width / 2f, height / 2f,
                    minOf(18f * density, width / 2f, height / 2f), feedbackPaint)
            }
            val save = canvas.save()
            canvas.translate((width - size) / 2f, (height - size) / 2f)
            canvas.scale(size / 24f, size / 24f)
            canvas.drawPath(glyph, glyphPaint)
            canvas.restoreToCount(save)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 1_500L
        val artworkWorker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "NeXtep-MediaPalette").apply { isDaemon = true }
        }
    }
}
