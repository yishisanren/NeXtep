package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.config.TopContentMode
import io.github.lujinxin.nextep.config.WorkspaceConfigClient
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SidebarButtonLayout
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import java.lang.reflect.Proxy
import java.util.Date

class TopAppStripView(
    context: Context,
    initialSidebarSide: SidebarSide,
    private val onAppClicked: (Intent) -> Unit,
    private val onMediaClicked: (android.media.session.MediaController) -> Unit,
    private val onSidebarSideRequested: (SidebarSide) -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
    private val onFrostStrengthChanged: (Int) -> Unit,
    private val onAppDragStarting: (View, Intent) -> Boolean,
    private val onAppDragTouch: (View, android.view.MotionEvent) -> Boolean,
    private val onPaymentRequested: () -> Unit = {},
    private val onThemeRequested: () -> Unit = {},
    private val onToolsRequested: () -> Unit = {},
    private val onCompareRequested: () -> Unit = {},
) : FrameLayout(context) {
    private var landscape = false
    private var controlOnLeft = false

    fun setLandscape(value: Boolean, controlOnLeft: Boolean) {
        landscape = value
        this.controlOnLeft = controlOnLeft
    }

    private val repository = TopAppRepository(context)
    private val mediaControl = MediaControlView(context, onMediaClicked)
    private val mediaHost = FrameLayout(context).apply {
        addView(mediaControl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private var wideSlot: View? = null

    internal fun setWideSlot(view: View?) {
        if (wideSlot === view) return
        wideSlot?.let { mediaHost.removeView(it) }
        wideSlot = view
        mediaControl.visibility = if (view == null) VISIBLE else GONE
        view?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            mediaHost.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        requestLayout()
    }

    internal fun mediaPanel(): View = MediaControlView(context, onMediaClicked).apply {
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = setWorkspaceVisible(true)
            override fun onViewDetachedFromWindow(v: View) = setWorkspaceVisible(false)
        })
    }
    private val titleView = LoopingTitleView(context).apply {
        visibility = View.GONE
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val heading = TextView(context).apply {
        text = AppLanguage.string(context, R.string.workbench_title)
        textSize = 14f
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val titleIcon = ImageView(context).apply {
        contentDescription = "NeXtep"
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        runCatching {
            val packageName = "io.github.lujinxin.nextep"
            val moduleResources = context.packageManager.getResourcesForApplication(packageName)
            val iconId = moduleResources.getIdentifier("ic_launcher_artwork", "drawable", packageName)
            val artwork = android.graphics.BitmapFactory.decodeResource(moduleResources, iconId)
            // The original circular artwork has a one-sixth margin on each side.
            // Use that artwork, rather than masking the square adaptive launcher icon.
            val roundArtwork = android.graphics.Bitmap.createBitmap(
                artwork, artwork.width / 6, artwork.height / 6,
                artwork.width * 2 / 3, artwork.height * 2 / 3,
            )
            setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, roundArtwork))
        }.onFailure { error ->
            NeXtepLog.warn("top_apps", "Unable to load default title icon", error)
            setImageDrawable(context.packageManager.getDefaultActivityIcon())
        }
    }
    private val appRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
    }
    private val compareButton = TextView(context).apply {
        text = AppLanguage.string(context, R.string.workbench_compare)
        contentDescription = text
        textSize = 12f
        gravity = Gravity.CENTER
        isFocusable = true
        setOnClickListener { onCompareRequested() }
    }
    private val appScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(compareButton, LinearLayout.LayoutParams(dp(APP_TILE_WIDTH_DP), dp(44)))
            addView(appRow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val leftButton = SideSelectionButton(context, SidebarSide.LEFT).apply {
        contentDescription = AppLanguage.string(context, R.string.window_side_left)
        setOnClickListener { onSidebarSideRequested(SidebarSide.LEFT) }
    }
    private val rightButton = SideSelectionButton(context, SidebarSide.RIGHT).apply {
        contentDescription = AppLanguage.string(context, R.string.window_side_right)
        setOnClickListener { onSidebarSideRequested(SidebarSide.RIGHT) }
    }
    private var sidebarSide = initialSidebarSide
    private val exitButton = ExitButton(context).apply {
        contentDescription = AppLanguage.string(context, R.string.window_exit)
        setOnClickListener { onExitRequested() }
    }
    private val settingsButton = GearButton(context).apply {
        contentDescription = AppLanguage.string(context, R.string.open_settings)
        setOnClickListener { onSettingsRequested() }
    }
    private val paymentButton = toolButton("¥", R.string.workbench_payment, onPaymentRequested)
    private val themeButton = toolButton("☀", R.string.workbench_theme, onThemeRequested)
    private val toolsButton = toolButton("•••", R.string.workbench_more, onToolsRequested)
    private var appearance = WorkbenchAppearance.DARK
    private val dividerPaint = Paint().apply { color = Color.argb(90, 255, 255, 255) }
    private var loading = false
    private var refreshPending = false
    private var lastRefreshAt = 0L
    private var refreshGeneration = 0
    private var refreshRetryCount = 0
    private val refreshRetry = Runnable { refresh(force = true) }
    private var touchableInsetsListener: Any? = null
    private var internalAppDragActive = false
    private var deferredApps: List<TopAppRepository.AppEntry>? = null
    private val appTiles = linkedMapOf<String, AppTile>()
    private val dismissHint = TextView(context).apply {
        text = AppLanguage.string(context, R.string.window_drop_background)
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(230, 28, 77, 90))
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), 0, dp(7), dp(WorkspaceControlMetrics.CONTENT_BOTTOM_PADDING_DP))
                addView(
                    actionRow(),
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(WorkspaceControlMetrics.ACTION_HEIGHT_DP)),
                )
                addView(
                    mediaHost,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(WorkspaceControlMetrics.MEDIA_HEIGHT_DP)),
                )
                addView(
                    appScroll,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
            },
        )
        addView(dismissHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
        })
        updateSideButtons()
        installTouchableStatusBarPassThrough()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val mediaHeight = WorkspaceControlMetrics.mediaHeightPixels(
                MeasureSpec.getSize(heightMeasureSpec), resources.displayMetrics.density,
            )
            mediaHost.visibility = if (wideSlot == null && mediaHeight == 0) GONE else VISIBLE
            mediaHost.layoutParams.height = if (wideSlot == null) mediaHeight else {
                val fixed = WorkspaceControlMetrics.COMPACT_HEIGHT_DP - WorkspaceControlMetrics.MEDIA_HEIGHT_DP
                (MeasureSpec.getSize(heightMeasureSpec) - dp(fixed)).coerceAtLeast(1)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private val configChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read configuration through the explicit module receiver; do not trust broadcast extras.
            if (intent.action == io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED ||
                intent.action == android.content.Intent.ACTION_LOCALE_CHANGED) {
                refreshRetryCount = 0
                refresh(force = true)
            }
        }
    }
    private var topConfig = WorkspaceTopConfig()
    private var workspaceVisible = false
    private val titleTick = object : Runnable {
        override fun run() {
            renderTitle()
            if (isAttachedToWindow && workspaceVisible &&
                topConfig.contentMode in listOf(TopContentMode.TIME, TopContentMode.DATE)) {
                postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
            }
        }
    }

    private fun updateTitle() {
        heading.text = AppLanguage.string(context, R.string.workbench_title)
        paymentButton.contentDescription = AppLanguage.string(context, R.string.workbench_payment)
        themeButton.contentDescription = AppLanguage.string(context, R.string.workbench_theme)
        toolsButton.contentDescription = AppLanguage.string(context, R.string.workbench_more)
        compareButton.text = AppLanguage.string(context, R.string.workbench_compare)
        compareButton.contentDescription = compareButton.text
        leftButton.contentDescription = AppLanguage.string(context, R.string.window_side_left)
        rightButton.contentDescription = AppLanguage.string(context, R.string.window_side_right)
        exitButton.contentDescription = AppLanguage.string(context, R.string.window_exit)
        settingsButton.contentDescription = AppLanguage.string(context, R.string.open_settings)
        dismissHint.text = AppLanguage.string(context, R.string.window_drop_background)
        mediaControl.refreshLanguage()
        updateSideButtons()
        removeCallbacks(titleTick)
        titleTick.run()
    }

    private fun renderTitle() {
        val mode = topConfig.contentMode
        val custom = mode == TopContentMode.TEXT
        val scroll = custom && topConfig.textScroll
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView.setScrolling(scroll, workspaceVisible)
        titleView.gravity = Gravity.CENTER
        titleView.textSize = if (custom) topConfig.textSizeSp.toFloat() else 16f
        titleView.typeface = if (custom) io.github.lujinxin.nextep.config.TopTextStyle.typeface(
            topConfig.textFontFamily, topConfig.textBold,
        ) else android.graphics.Typeface.DEFAULT
        titleIcon.visibility = if (mode == TopContentMode.ICON) View.VISIBLE else View.GONE
        titleView.visibility = if (mode == TopContentMode.ICON || mode == TopContentMode.EMPTY) View.GONE else View.VISIBLE
        val displayTitle = when (mode) {
            TopContentMode.TIME -> {
                val skeleton = if (DateFormat.is24HourFormat(context)) {
                    if (topConfig.showSeconds) "Hms" else "Hm"
                } else if (topConfig.showSeconds) "hms" else "hm"
                java.text.SimpleDateFormat(
                    DateFormat.getBestDateTimePattern(AppLanguage.locale(context), skeleton), AppLanguage.locale(context),
                ).format(Date())
            }
            TopContentMode.DATE -> {
                val locale = AppLanguage.locale(context)
                java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMEd"), locale).format(Date())
            }
            else -> topConfig.title
        }
        // App-strip refreshes must not restart a long marquee before its tail
        // becomes visible. Only changing the content should reset its position.
        if (!android.text.TextUtils.equals(titleView.text, displayTitle)) titleView.text = displayTitle
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(configChangedReceiver,
            android.content.IntentFilter(io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED).apply {
                addAction(android.content.Intent.ACTION_LOCALE_CHANGED)
            },
            Context.RECEIVER_EXPORTED)
        updateTitle()
        if (workspaceVisible && appRow.childCount == 0) refresh(force = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(titleTick)
        removeCallbacks(refreshRetry)
        context.unregisterReceiver(configChangedReceiver)
        super.onDetachedFromWindow()
    }

    fun setWorkspaceVisible(visible: Boolean) {
        if (visible && !workspaceVisible) refreshRetryCount = 0
        workspaceVisible = visible
        if (!visible) removeCallbacks(refreshRetry)
        else if (appRow.childCount == 0) refresh(force = true)
        updateTitle()
        mediaControl.setWorkspaceVisible(visible)
    }

    fun setMediaWallpaperLight(light: Boolean) {
        mediaControl.setWallpaperLight(light)
    }

    fun setSidebarSide(side: SidebarSide) {
        sidebarSide = side
        updateSideButtons()
        exitButton.scaleX = if (side == SidebarSide.LEFT) -1f else 1f
    }

    fun setDismissTargetHighlighted(highlighted: Boolean) {
        dismissHint.visibility = if (highlighted) VISIBLE else GONE
    }

    fun finishInternalAppDrag() {
        internalAppDragActive = false
        deferredApps?.let { entries -> deferredApps = null; renderApps(entries) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val thickness = resources.displayMetrics.density
        canvas.drawRect(0f, height - thickness, width.toFloat(), height.toFloat(), dividerPaint)
    }

    fun refresh(force: Boolean = false) {
        removeCallbacks(refreshRetry)
        mediaControl.refresh()
        val now = System.currentTimeMillis()
        if (loading) {
            refreshPending = refreshPending || force
            return
        }
        if (!force && now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        loading = true
        val generation = ++refreshGeneration
        Thread({
            val config = WorkspaceConfigClient.queryOrNull(context)
            if (config != null) AppLanguage.updateHostLanguage(config.languageTag)
            val result = config?.let {
                runCatching {
                    repository.load(
                        if (config.manualAppOrder) config.appComponents else emptyList(),
                        manualOrder = config.manualAppOrder,
                    )
                }
            }
            post {
                if (generation != refreshGeneration) return@post
                loading = false
                if (config != null) {
                    onFrostStrengthChanged(config.frostStrength)
                    topConfig = config
                    updateTitle()
                    result?.onSuccess { entries ->
                        refreshRetryCount = 0
                        lastRefreshAt = System.currentTimeMillis()
                        renderApps(entries)
                    }?.onFailure { error ->
                        NeXtepLog.error("top_apps", "Unable to load App strip", error)
                        // A temporary catalogue failure must not erase the
                        // last successfully rendered user selection.
                        scheduleRefreshRetry()
                    }
                } else {
                    // A failed IPC query says nothing about the user's preferences.
                    // Retain both the title mode and the already rendered app order.
                    NeXtepLog.warn("top_apps", "Config unavailable; retaining previous top content and App order")
                    scheduleRefreshRetry()
                }
                if (refreshPending) {
                    refreshPending = false
                    refresh(force = true)
                }
            }
        }, "NeXtep-TopApps").start()
    }

    private fun scheduleRefreshRetry() {
        if (!isAttachedToWindow || !workspaceVisible) return
        removeCallbacks(refreshRetry)
        // A cold or temporarily frozen module can outlast the initial retries.
        // Keep the last successful selection and retry while the panel is visible.
        val delay = minOf(1_000L * (1L shl refreshRetryCount), REFRESH_INTERVAL_MS)
        refreshRetryCount = (refreshRetryCount + 1).coerceAtMost(4)
        postDelayed(refreshRetry, delay)
    }

    internal fun setAppearance(value: WorkbenchAppearance) {
        appearance = value
        background = value.background()
        titleView.setTextColor(value.text)
        heading.setTextColor(value.text)
        compareButton.setTextColor(value.accent)
        compareButton.background = value.background(dp(12).toFloat(), dp(1))
        listOf(paymentButton, themeButton, toolsButton).forEach {
            it.setTextColor(value.accent)
            it.background = value.background(dp(13).toFloat(), dp(1))
        }
        rightButton.glyphColor = value.text
        rightButton.background = value.background(dp(13).toFloat(), dp(1))
        setMediaWallpaperLight(value == WorkbenchAppearance.LIGHT)
    }

    private fun toolButton(glyph: String, label: Int, action: () -> Unit) = TextView(context).apply {
        text = glyph
        textSize = 23f
        gravity = Gravity.CENTER
        contentDescription = AppLanguage.string(context, label)
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun actionRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val buttonSize = dp(ACTION_BUTTON_SIZE_DP)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(heading, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
                addView(FrameLayout(context).apply {
                    addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    addView(titleIcon, LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            },
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            },
        )
        addView(themeButton, LinearLayout.LayoutParams(buttonSize, buttonSize))
        addView(paymentButton, LinearLayout.LayoutParams(buttonSize, buttonSize))
        rightButton.setOnClickListener {
            onSidebarSideRequested(if (sidebarSide == SidebarSide.LEFT) SidebarSide.RIGHT else SidebarSide.LEFT)
        }
        addView(rightButton, LinearLayout.LayoutParams(buttonSize, buttonSize))
        addView(toolsButton, LinearLayout.LayoutParams(buttonSize, buttonSize))
    }

    private fun renderApps(entries: List<TopAppRepository.AppEntry>) {
        // Keep the touch-owning tile attached until its drag finishes.
        if (internalAppDragActive) { deferredApps = entries; return }
        val scrollX = appScroll.scrollX
        var structureChanged = false
        val keys = entries.mapTo(hashSetOf()) { it.key }
        val obsolete = appTiles.entries.iterator()
        while (obsolete.hasNext()) {
            val tile = obsolete.next()
            if (tile.key !in keys) {
                appRow.removeView(tile.value)
                obsolete.remove()
                structureChanged = true
            }
        }
        if (entries.isEmpty()) {
            appRow.removeAllViews()
            appRow.addView(messageView(AppLanguage.string(context, R.string.apps_none_available)))
        } else {
            if (appTiles.isEmpty()) appRow.removeAllViews()
            entries.forEachIndexed { index, entry ->
                val tile = appTiles.getOrPut(entry.key) { AppTile() }
                tile.bind(entry)
                if (appRow.getChildAt(index) !== tile) {
                    appRow.removeView(tile)
                    appRow.addView(tile, index)
                    structureChanged = true
                }
            }
        }
        if (structureChanged) appScroll.post { appScroll.scrollTo(scrollX, 0) }
        NeXtepLog.info("top_apps", "Rendered ${entries.size} launcher activities")
    }

    private inner class AppTile : LinearLayout(context) {
        private lateinit var entry: TopAppRepository.AppEntry
        private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

        init {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            isClickable = true
            isLongClickable = true
            setPadding(dp(5), 0, dp(5), dp(WorkspaceControlMetrics.APP_BOTTOM_PADDING_DP))
            addView(
                icon,
                LinearLayout.LayoutParams(dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP), dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP)),
            )
            layoutParams = LinearLayout.LayoutParams(dp(APP_TILE_WIDTH_DP), LayoutParams.MATCH_PARENT)
            setOnClickListener { onAppClicked(Intent(entry.launchIntent)) }
            setOnLongClickListener { tile ->
                val started = onAppDragStarting(tile, Intent(entry.launchIntent))
                internalAppDragActive = started
                NeXtepLog.info(
                    "top_apps",
                    "Internal drag started=$started component=${entry.launchIntent.component}",
                )
                started
            }
            setOnTouchListener { tile, event -> onAppDragTouch(tile, event) }
        }

        fun bind(value: TopAppRepository.AppEntry) {
            entry = value
            if (!android.text.TextUtils.equals(contentDescription, value.label)) contentDescription = value.label
            if (icon.drawable !== value.icon) icon.setImageDrawable(value.icon)
        }
    }

    private fun updateSideButtons() {
        leftButton.isSideSelected = sidebarSide == SidebarSide.LEFT
        rightButton.isSideSelected = true
        rightButton.representedSide = sidebarSide
        rightButton.contentDescription = AppLanguage.string(context,
            if (sidebarSide == SidebarSide.LEFT) R.string.window_side_right else R.string.window_side_left)
    }

    private fun installTouchableStatusBarPassThrough() {
        runCatching {
            val listenerClass = Class.forName(
                "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener",
            )
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, arguments ->
                if (method.name == "onComputeInternalInsets") {
                    val info = arguments?.firstOrNull() ?: return@newProxyInstance null
                    info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                        .invoke(info, TOUCHABLE_INSETS_REGION)
                    val region = info.javaClass.getField("touchableRegion").get(info) as Region
                    if (landscape) {
                        // Exclude the physical-top status band in window-local axes.
                        val status = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP).coerceIn(0, height)
                        if (controlOnLeft) region.set(status, 0, height, width)
                        else region.set(0, 0, height - status, width)
                    } else {
                        region.set(0, dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP), width, height)
                    }
                }
                null
            }
            viewTreeObserver.javaClass
                .getMethod("addOnComputeInternalInsetsListener", listenerClass)
                .invoke(viewTreeObserver, listener)
            touchableInsetsListener = listener
        }.onFailure { error ->
            NeXtepLog.warn(
                "top_apps",
                "Unable to pass the status band through to SystemUI",
                error,
            )
        }
    }

    private fun messageView(message: String) = TextView(context).apply {
        text = message
        setTextColor(Color.LTGRAY)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val APP_TILE_WIDTH_DP = 50
        const val ACTION_BUTTON_SIZE_DP = 48
        const val REFRESH_INTERVAL_MS = 15_000L
        const val TOUCHABLE_INSETS_REGION = 3
    }
}

private class SideSelectionButton(
    context: Context,
    representedSide: SidebarSide,
) : View(context) {
    var representedSide = representedSide
        set(value) { field = value; invalidate() }
    var glyphColor = Color.WHITE
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.15f)
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    var isSideSelected: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init { isClickable = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val iconWidth = minOf(dp(24f), width * 0.6f)
        val iconHeight = iconWidth
        val iconLeft = (width - iconWidth) / 2f
        val iconTop = (height - iconHeight) / 2f
        val scale = iconWidth / 24f
        val strokeInset = paint.strokeWidth / 2f
        fun rect(left: Float, top: Float, right: Float, bottom: Float) = android.graphics.RectF(
            iconLeft + left * scale + strokeInset,
            iconTop + top * scale + strokeInset,
            iconLeft + right * scale - strokeInset,
            iconTop + bottom * scale - strokeInset,
        )
        val diagramSide = SidebarButtonLayout.sideInPanel(representedSide)
        val mainRect = if (diagramSide == SidebarSide.LEFT) {
            rect(8.5f, 1f, 22f, 23f)
        } else {
            rect(2f, 1f, 15.5f, 23f)
        }
        val stackLeft = if (diagramSide == SidebarSide.LEFT) {
            2f
        } else {
            17f
        }
        paint.color = glyphColor
        paint.alpha = if (isSideSelected) 255 else 150
        paint.style = if (isSideSelected) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawRect(mainRect, paint)
        repeat(io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT) { index ->
            val top = 1f + index * 6f
            canvas.drawRect(rect(stackLeft, top, stackLeft + 5f, top + 4f), paint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private abstract class ActionGlyphButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init { isClickable = true }

    final override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGlyph(canvas, paint, width / 2f, height / 2f, minOf(width, height) * 0.28f)
    }

    abstract fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private class GearButton(context: Context) : ActionGlyphButton(context) {
    override fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float) {
        val gearRadius = radius * 0.82f
        paint.style = Paint.Style.FILL
        repeat(8) { index ->
            canvas.save()
            canvas.rotate(index * 45f, cx, cy)
            canvas.drawRoundRect(
                cx - gearRadius * 0.17f,
                cy - gearRadius,
                cx + gearRadius * 0.17f,
                cy - gearRadius * 0.56f,
                gearRadius * 0.08f,
                gearRadius * 0.08f,
                paint,
            )
            canvas.restore()
        }
        canvas.drawCircle(cx, cy, gearRadius * 0.68f, paint)
        paint.color = Color.rgb(18, 54, 82)
        canvas.drawCircle(cx, cy, gearRadius * 0.28f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
    }
}

private class ExitButton(context: Context) : ActionGlyphButton(context) {
    override fun drawGlyph(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float) {
        val scale = radius * 1.15f / 12f
        fun x(value: Float) = cx + (value - 12f) * scale
        fun y(value: Float) = cy + (value - 12f) * scale
        paint.strokeWidth = resources.displayMetrics.density * 1.35f

        val box = android.graphics.Path().apply {
            moveTo(x(9f), y(7f))
            lineTo(x(5f), y(7f))
            lineTo(x(5f), y(21f))
            lineTo(x(17f), y(21f))
            lineTo(x(17f), y(15f))
        }
        canvas.drawPath(box, paint)

        val arrow = android.graphics.Path().apply {
            moveTo(x(8f), y(14f))
            lineTo(x(11f), y(17f))
            lineTo(x(17f), y(11f))
            lineTo(x(19f), y(13f))
            lineTo(x(21f), y(3f))
            lineTo(x(11f), y(5f))
            lineTo(x(13f), y(7f))
            close()
        }
        canvas.drawPath(arrow, paint)
    }
}
