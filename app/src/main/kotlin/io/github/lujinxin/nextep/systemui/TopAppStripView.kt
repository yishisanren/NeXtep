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
    private var controlAtBottom = false

    /** The status/gesture reserve and pass-through band sit on the display's outer edge. */
    fun setLandscape(value: Boolean, controlOnLeft: Boolean, controlAtBottom: Boolean = false) {
        landscape = value
        this.controlOnLeft = controlOnLeft
        if (this.controlAtBottom != controlAtBottom) {
            this.controlAtBottom = controlAtBottom
            listOf(controls, dismissHint, mediaHost).forEach { view ->
                (view.layoutParams as LayoutParams).apply {
                    topMargin = if (controlAtBottom) 0 else dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
                    bottomMargin = if (controlAtBottom) dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP) else 0
                }
                view.requestLayout()
            }
        }
    }

    private val repository = TopAppRepository(context)
    private val mediaHost = FrameLayout(context).apply { visibility = GONE }
    private var wideSlot: View? = null
    private lateinit var controls: LinearLayout

    internal fun setWideSlot(view: View?) {
        if (wideSlot === view) return
        NeXtepLog.info("wide_layout", "Wide slot host ${if (view == null) "cleared" else "set"} strip=${width}x$height")
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
    private val compareButton = toolButton(WorkbenchIcon.Kind.COMPARE, R.string.workbench_compare, onCompareRequested)
    private val appScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(appRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val paymentButton = toolButton(WorkbenchIcon.Kind.PAYMENT, R.string.workbench_payment, onPaymentRequested)
    private val toolsButton = toolButton(WorkbenchIcon.Kind.SETTINGS, R.string.open_settings, onToolsRequested)
    private var appearance = WorkbenchAppearance.DARK
    private val dividerPaint = Paint().apply { color = WorkbenchAppearance.DARK.outline }
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
                // Title row and app row sit as one block, centred in the space below the status band.
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), 0, dp(7), dp(WorkspaceControlMetrics.CONTENT_BOTTOM_PADDING_DP))
                addView(
                    actionRow(),
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(WorkspaceControlMetrics.ACTION_HEIGHT_DP)),
                )
                addView(
                    appScroll,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        dp(WorkspaceControlMetrics.APP_ICON_SIZE_DP + WorkspaceControlMetrics.APP_BOTTOM_PADDING_DP)),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
            },
        )
        // Video keeps the status/camera strip free like the controls; its player chrome
        // (back button, title) must stay tappable under the system bar.
        addView(mediaHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
        })
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
        toolsButton.contentDescription = AppLanguage.string(context, R.string.open_settings)
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
        // The divider faces the content, whichever edge the control area occupies.
        if (controlAtBottom) canvas.drawRect(0f, 0f, width.toFloat(), thickness, dividerPaint)
        else canvas.drawRect(0f, height - thickness, width.toFloat(), height.toFloat(), dividerPaint)
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
                    repository.load(config.appComponents)
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
        dividerPaint.color = value.outline
        listOf(paymentButton, compareButton, toolsButton).forEach {
            it.imageTintList = android.content.res.ColorStateList.valueOf(value.accent)
            // Buttons share the empty-slot card tone so the whole panel reads as one surface.
            it.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(value.accent and 0x33ffffff),
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(value.card)
                    setStroke(dp(1), value.outline)
                }, null)
        }
        invalidate()
    }

    private fun toolButton(kind: WorkbenchIcon.Kind, label: Int, action: () -> Unit) = android.widget.ImageButton(context).apply {
        setImageDrawable(WorkbenchIcon(kind, Color.WHITE))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(9), dp(9), dp(9), dp(9))
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
                gravity = Gravity.CENTER_VERTICAL
                addView(heading, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
                addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            },
        )
        // Three equal 40dp buttons with an 8dp gap read as one group beside the 18sp title.
        listOf(compareButton, paymentButton, toolsButton).forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                if (index > 0) marginStart = dp(ACTION_BUTTON_GAP_DP)
            })
        }
    }

    private fun renderApps(entries: List<TopAppRepository.AppEntry>) {
        // Keep the touch-owning tile attached until its drag finishes.
        if (internalAppDragActive) { deferredApps = entries; return }
        val scrollX = appScroll.scrollX
        var structureChanged = false
        val visible = entries.take(10)
        val keys = visible.mapTo(hashSetOf()) { it.key }
        val obsolete = appTiles.entries.iterator()
        while (obsolete.hasNext()) {
            val tile = obsolete.next()
            if (tile.key !in keys) {
                appRow.removeView(tile.value)
                obsolete.remove()
                structureChanged = true
            }
        }
        if (visible.isEmpty()) {
            appRow.removeAllViews()
        } else {
            if (appTiles.isEmpty()) appRow.removeAllViews()
            visible.forEachIndexed { index, entry ->
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
                        val status = if (wideSlot != null) 0 else dp(WorkspaceControlMetrics.STATUS_HEIGHT_DP)
                        if (controlAtBottom) region.set(0, 0, width, height - status)
                        else region.set(0, status, width, height)
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
        const val ACTION_BUTTON_SIZE_DP = 40
        const val ACTION_BUTTON_GAP_DP = 8
        const val REFRESH_INTERVAL_MS = 15_000L
        const val TOUCHABLE_INSETS_REGION = 3
    }
}
