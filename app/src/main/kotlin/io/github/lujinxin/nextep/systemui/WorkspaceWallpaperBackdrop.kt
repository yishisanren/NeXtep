package io.github.lujinxin.nextep.systemui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.PowerManager
import androidx.core.graphics.ColorUtils
import io.github.lujinxin.nextep.logging.NeXtepLog

object WorkspaceWallpaperBackdrop {
    fun canCapture(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }.getOrDefault(false)

    // This code executes inside the hooked SystemUI process, whose Context owns wallpaper access.
    @SuppressLint("MissingPermission")
    fun identity(context: Context): String? = runCatching {
        val manager = WallpaperManager.getInstance(context)
        "${manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}:${manager.wallpaperInfo?.component}"
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun capture(context: Context, width: Int, height: Int): Bitmap? = runCatching {
        // The wallpaper surface on keyguard may be a different lock-screen image.
        // Never replace the desktop cache with it or with a screen-off frame.
        if (!canCapture(context)) return@runCatching null
        // ColorOS 17 uses a live service even for some desktop wallpaper presets.
        // WallpaperManager.drawable can then contain the previous static wallpaper.
        // Capture only the wallpaper surface, never the screen's apps or overlays.
        captureRenderedWallpaper()?.let { snapshot ->
            try {
                val software = if (snapshot.config == Bitmap.Config.HARDWARE) {
                    snapshot.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
                } else snapshot
                try {
                    return@runCatching fit(software, width, height)
                } finally {
                    if (software !== snapshot) software.recycle()
                }
            } finally {
                snapshot.recycle()
            }
        }
        val manager = WallpaperManager.getInstance(context)
        // A live service may need a few frames after FLAG_SHOW_WALLPAPER is set.
        // Retain the last rendered frame while the controller retries, rather than
        // displaying WallpaperManager's unrelated previous static image.
        if (manager.wallpaperInfo != null) return@runCatching null
        manager.forgetLoadedWallpaper()
        val wallpaper = manager.drawable?.constantState?.newDrawable(context.resources)?.mutate()
            ?: return@runCatching null
        val sampleWidth = width.coerceAtLeast(1)
        val sampleHeight = height.coerceAtLeast(1)
        val sampled = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        val sourceWidth = wallpaper.intrinsicWidth.takeIf { it > 0 } ?: width
        val sourceHeight = wallpaper.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = maxOf(
            sampleWidth.toFloat() / sourceWidth,
            sampleHeight.toFloat() / sourceHeight,
        )
        val drawWidth = (sourceWidth * scale).toInt()
        val drawHeight = (sourceHeight * scale).toInt()
        val left = (sampleWidth - drawWidth) / 2
        val top = (sampleHeight - drawHeight) / 2
        wallpaper.setBounds(left, top, left + drawWidth, top + drawHeight)
        wallpaper.draw(Canvas(sampled))
        sampled
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Unable to capture desktop wallpaper", error)
    }.getOrNull()

    fun fit(source: Bitmap, width: Int, height: Int): Bitmap? = runCatching {
        val fitted = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val scale = maxOf(fitted.width.toFloat() / source.width, fitted.height.toFloat() / source.height)
            val canvas = Canvas(fitted)
            canvas.translate((fitted.width - source.width * scale) / 2f, (fitted.height - source.height * scale) / 2f)
            canvas.scale(scale, scale)
            canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            fitted
        } catch (error: Throwable) {
            fitted.recycle()
            throw error
        }
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Unable to fit wallpaper snapshot", error)
    }.getOrNull()

    private fun captureRenderedWallpaper(): Bitmap? = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val service = global.getDeclaredMethod("getWindowManagerService").apply {
            isAccessible = true
        }.invoke(null) ?: return@runCatching null
        Class.forName("android.view.IWindowManager")
            .getDeclaredMethod("screenshotWallpaper")
            .apply { isAccessible = true }
            .invoke(service) as? Bitmap
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Wallpaper surface capture unavailable", error)
    }.getOrNull()

    fun crop(bitmap: Bitmap?, originX: Int, originY: Int, strength: Int,
        previous: Drawable? = null): Drawable =
        (previous as? WallpaperCropDrawable ?: WallpaperCropDrawable()).apply {
            update(bitmap, originX, originY, strength.coerceIn(0, 100))
        }

    fun isLight(bitmap: Bitmap?, originX: Int, originY: Int, width: Int, height: Int): Boolean =
        runCatching {
            if (bitmap == null || bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) {
                return@runCatching false
            }
            var luminance = 0.0
            // The cached frame is software-backed; a small grid avoids copying or scanning it.
            for (y in 0 until 4) {
                for (x in 0 until 8) {
                    val sampleX = (originX + (x + 0.5f) * width / 8).toInt().coerceIn(0, bitmap.width - 1)
                    val sampleY = (originY + (y + 0.5f) * height / 4).toInt().coerceIn(0, bitmap.height - 1)
                    luminance += ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(
                        bitmap.getPixel(sampleX, sampleY), 255,
                    ))
                }
            }
            luminance / 32 > 0.3
        }.getOrDefault(false)

}

private class WallpaperCropDrawable : Drawable() {
    private var bitmap: Bitmap? = null
    private var originX = 0
    private var originY = 0
    private var strength = -1
    private var density = 0f
    private val sourceRect = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = null
    }

    private var blurredWallpaper: android.graphics.RenderNode? = null

    fun update(source: Bitmap?, x: Int, y: Int, blur: Int) {
        val imageChanged = bitmap !== source
        val blurChanged = strength != blur
        val updatedDensity = android.content.res.Resources.getSystem().displayMetrics.density
        val densityChanged = density != updatedDensity
        if (!imageChanged && !blurChanged && !densityChanged && originX == x && originY == y) return
        bitmap = source
        originX = x
        originY = y
        strength = blur
        density = updatedDensity
        if (source == null || blur == 0) {
            blurredWallpaper?.discardDisplayList()
            blurredWallpaper = null
        } else {
            val newNode = blurredWallpaper == null
            val node = blurredWallpaper ?: android.graphics.RenderNode("NeXtep-FrostedWallpaper")
                .also { blurredWallpaper = it }
            if (imageChanged || newNode) {
                node.setPosition(0, 0, source.width, source.height)
                val recording = node.beginRecording(source.width, source.height)
                try { recording.drawBitmap(source, 0f, 0f, null) }
                finally { node.endRecording() }
            }
            // Strength changes update only the effect, retaining the recorded wallpaper.
            if (blurChanged || newNode || densityChanged) {
                val fraction = strength / 100f
                val radius = 32f * fraction * fraction * density
                node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    radius.coerceAtLeast(0.01f), radius.coerceAtLeast(0.01f), android.graphics.Shader.TileMode.CLAMP,
                ))
            }
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val source = bitmap ?: run {
            // Until the first capture, let the requested system wallpaper show
            // through instead of covering the control area and empty slots in black.
            drawTint(canvas)
            return
        }
        val left = originX.coerceIn(0, source.width)
        val top = originY.coerceIn(0, source.height)
        val right = (left + bounds.width()).coerceAtMost(source.width)
        val bottom = (top + bounds.height()).coerceAtMost(source.height)
        if (right <= left || bottom <= top) return
        val node = blurredWallpaper
        if (node != null && canvas.isHardwareAccelerated) {
            val save = canvas.save()
            canvas.clipRect(bounds)
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat() / (right - left), bounds.height().toFloat() / (bottom - top))
            canvas.translate(-left.toFloat(), -top.toFloat())
            node.alpha = paint.alpha / 255f
            canvas.drawRenderNode(node)
            canvas.restoreToCount(save)
        } else {
            sourceRect.set(left, top, right, bottom)
            canvas.drawBitmap(source, sourceRect, bounds, paint)
        }
        drawTint(canvas)
    }

    private fun drawTint(canvas: Canvas) {
        // Neutral translucent layers soften contrast without blurring foreground content.
        if (strength > 0) {
            canvas.drawColor(Color.argb(strength * 32 / 100, 16, 23, 34))
            canvas.drawColor(Color.argb(strength * 20 / 100, 235, 241, 248))
        }
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha == alpha) return
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
