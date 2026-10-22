package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Region
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.config.WorkspaceConfigClient
import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import java.lang.reflect.Proxy
import java.util.Date

class TopAppStripView(
    context: Context,
    private val onAppClicked: (Intent) -> Unit,
    private val onFrostStrengthChanged: (Int) -> Unit,
    private val onAppDragStarting: (View, Intent) -> Boolean,
    private val onAppDragTouch: (View, android.view.MotionEvent) -> Boolean,
    private val onPaymentRequested: () -> Unit = {},
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
    private val mediaHost = FrameLayout(context).apply { visibility = GONE }
    private var wideSlot: View? = null
    private lateinit var controls: LinearLayout

    internal fun setWideSlot(view: View?) {
        if (wideSlot === view) return
        wideSlot?.let { mediaHost.removeView(it) }
        wideSlot = view
        controls.visibility = if (view == null) VISIBLE else GONE
        mediaHost.visibility = if (view == null) GONE else VISIBLE
        view?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            mediaHost.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        requestLayout()
    }

    private val titleView = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val heading = TextView(context).apply {
        text = AppLanguage.string(context, R.string.workbench_title)
        textSize = 18f
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val appRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
    }
    private val compareButton = TextView(context).apply {
        text = AppLanguage.string(context, R.string.workbench_compare_short)
        contentDescription = AppLanguage.string(context, R.string.workbench_compare)
        textSize = 14f
        gravity = Gravity.CENTER
        isFocusable = true
        setOnClickListener { onCompareRequested() }
    }
    private val appScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(appRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val paymentButton = toolButton(AppLanguage.string(context, R.string.workbench_pay_short), R.string.workbench_payment, onPaymentRequested)
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
                controls = this
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), 0, dp(7), dp(WorkspaceControlMetrics.CONTENT_BOTTOM_PADDING_DP))
                addView(
                    actionRow(),
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(WorkspaceControlMetrics.ACTION_HEIGHT_DP)),
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
        // Video owns the complete top region; toolbar padding/status reserve
        // belongs only to controls, never to the video container.
        addView(mediaHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dismissHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
        })
        installTouchableStatusBarPassThrough()
    }

    private val configChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read configuration through the explicit module receiver; do not trust broadcast extras.
            if (intent.action == io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED ||
                intent.action == android.content.Intent.ACTION_LOCALE_CHANGED) {
                refreshRetryCount = 0
                refresh(force = true)
            } else updateTitle()
        }
    }
    private var topConfig = WorkspaceTopConfig()
    private var workspaceVisible = false
    private val titleTick = object : Runnable {
        override fun run() {
            renderTitle()
            if (isAttachedToWindow && workspaceVisible) {
                postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }

    private fun updateTitle() {
        heading.text = AppLanguage.string(context, R.string.workbench_title)
        paymentButton.contentDescription = AppLanguage.string(context, R.string.workbench_payment)
        paymentButton.text = AppLanguage.string(context, R.string.workbench_pay_short)
        toolsButton.contentDescription = AppLanguage.string(context, R.string.workbench_more)
        compareButton.text = AppLanguage.string(context, R.string.workbench_compare_short)
        compareButton.contentDescription = AppLanguage.string(context, R.string.workbench_compare)
        dismissHint.text = AppLanguage.string(context, R.string.window_drop_background)
        removeCallbacks(titleTick)
        titleTick.run()
    }

    private fun renderTitle() {
        val locale = AppLanguage.locale(context)
        val date = java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMEd"), locale).format(Date())
        if (!android.text.TextUtils.equals(titleView.text, date)) titleView.text = date
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(configChangedReceiver,
            android.content.IntentFilter(io.github.lujinxin.nextep.config.WorkspaceConfigContract.ACTION_CHANGED).apply {
                addAction(android.content.Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
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
        listOf(paymentButton, compareButton, toolsButton).forEach {
            it.setTextColor(value.accent)
            it.background = value.background(dp(13).toFloat(), dp(1))
        }
    }

    private fun toolButton(glyph: String, label: Int, action: () -> Unit) = TextView(context).apply {
        text = glyph
        textSize = if (glyph.length > 1 && glyph != "•••") 14f else 23f
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
                setPadding(0, dp(8), 0, 0)
                addView(heading, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
                addView(FrameLayout(context).apply {
                    addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            },
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            },
        )
        addView(compareButton, LinearLayout.LayoutParams(buttonSize, buttonSize))
        addView(paymentButton, LinearLayout.LayoutParams(buttonSize, buttonSize))
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
                        val status = if (wideSlot != null) 0 else dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP).coerceIn(0, height)
                        if (controlOnLeft) region.set(status, 0, height, width)
                        else region.set(0, 0, height - status, width)
                    } else {
                        region.set(0, if (wideSlot != null) 0 else dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP), width, height)
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
