package io.github.lujinxin.nextep.workspace

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.concurrent.atomic.AtomicBoolean

object SystemServerWorkspaceBridge {
    private const val ACTIVE_KEY = "nextep_workspace_active_v1"
    private const val WIDTH_KEY = "nextep_workspace_screen_width_v1"
    private const val HEIGHT_KEY = "nextep_workspace_screen_height_v1"
    private const val SIDEBAR_SIDE_KEY = "nextep_workspace_sidebar_side_v1"
    private const val GEOMETRY_KEY = "nextep_workspace_geometry_v1"
    private val geometryObservationStarted = AtomicBoolean(false)
    @Volatile private var observedGeometry: WorkspaceGeometry? = null
    @Volatile private var privateTaskIds: Set<Int> = emptySet()
    private const val PRIVATE_TASKS_KEY = "nextep_workspace_private_task_ids_v1"
    private const val SCENE_KEY = "nextep_workspace_scene_request_v1"
    private const val WIDE_DISPLAY_KEY = "nextep_workspace_wide_display_v1"

    /** Subscribe once in system_server. WM animation callbacks then read only RAM. */
    fun startGeometryObservation(context: Context) {
        if (!geometryObservationStarted.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            runCatching {
                val resolver = context.contentResolver
                fun refresh() {
                    observedGeometry = readActiveGeometry(context)
                    privateTaskIds = if (observedGeometry == null) emptySet() else
                        Settings.Global.getString(resolver, PRIVATE_TASKS_KEY).orEmpty().split(',')
                            .mapNotNull(String::toIntOrNull).filter { it >= 0 }.toSet()
                }
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = refresh()
                }
                resolver.registerContentObserver(Settings.Global.getUriFor(ACTIVE_KEY), false, observer)
                resolver.registerContentObserver(Settings.Global.getUriFor(GEOMETRY_KEY), false, observer)
                resolver.registerContentObserver(Settings.Global.getUriFor(PRIVATE_TASKS_KEY), false, observer)
                refresh()
            }.onFailure {
                geometryObservationStarted.set(false)
                NeXtepLog.warn("system_server_bridge", "Geometry observation unavailable", it)
            }
        }
    }

    fun observedActiveGeometry(): WorkspaceGeometry? = observedGeometry

    fun privateTask(taskId: Int): Boolean = observedGeometry != null && taskId in privateTaskIds

    fun publishPrivateTasks(context: Context, taskIds: Set<Int>) {
        runCatching {
            val value = taskIds.filter { it >= 0 }.sorted().joinToString(",")
            if (Settings.Global.getString(context.contentResolver, PRIVATE_TASKS_KEY) != value)
                Settings.Global.putString(context.contentResolver, PRIVATE_TASKS_KEY, value)
        }.onFailure { NeXtepLog.warn("workspace_privacy", "Unable to publish private tasks", it) }
    }

    internal fun publishScene(context: Context, request: WorkspaceSceneRequest) {
        Settings.Global.putString(context.contentResolver, SCENE_KEY, request.encode())
    }

    internal fun scene(context: Context): WorkspaceSceneRequest? = runCatching {
        WorkspaceSceneRequest.decode(Settings.Global.getString(context.contentResolver, SCENE_KEY))
            ?.takeIf { it.fresh(android.os.SystemClock.uptimeMillis()) }
    }.getOrNull()

    internal fun clearScene(context: Context) {
        runCatching { Settings.Global.putString(context.contentResolver, SCENE_KEY, "") }
    }

    fun publishWideDisplay(context: Context, displayId: Int?) {
        runCatching { Settings.Global.putInt(context.contentResolver, WIDE_DISPLAY_KEY, displayId ?: -1) }
    }

    fun isWideDisplay(context: Context, displayId: Int): Boolean = displayId > 0 && runCatching {
        Settings.Global.getInt(context.contentResolver, WIDE_DISPLAY_KEY, -1) == displayId && isWorkspaceActive(context)
    }.getOrDefault(false)

    fun publishLayout(context: Context, profile: WorkspaceLayoutProfile) {
        runCatching {
            Settings.Global.putString(context.contentResolver, "nextep_workspace_layout_profile_v2", profile.encode())
        }.onFailure { NeXtepLog.warn("system_server_bridge", "Unable to persist recorded layout", it) }
    }

    fun publish(context: Context, active: Boolean, geometry: WorkspaceGeometry? = null) {
        runCatching {
            val resolver = context.contentResolver
            geometry?.let {
                Settings.Global.putInt(resolver, WIDTH_KEY, it.screenWidth)
                Settings.Global.putInt(resolver, HEIGHT_KEY, it.screenHeight)
                Settings.Global.putString(resolver, SIDEBAR_SIDE_KEY, it.sidebarSide.name)
                // Publish one complete snapshot so another process cannot combine
                // new navigation insets with an old rotation or control thickness.
                Settings.Global.putString(resolver, GEOMETRY_KEY, listOf(
                    it.screenWidth, it.screenHeight, it.topHeight, it.sidebarWidth, it.sidebarSide.name,
                    it.navigationInsets.left, it.navigationInsets.top,
                    it.navigationInsets.right, it.navigationInsets.bottom, it.displayRotation,
                ).joinToString(","))
            }
            Settings.Global.putInt(resolver, ACTIVE_KEY, if (active) 1 else 0)
        }.onFailure { error ->
            NeXtepLog.warn("system_server_bridge", "Unable to publish active=$active", error)
        }
    }

    fun activePhysicalAspectRatio(context: Context): Float? = runCatching {
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, ACTIVE_KEY, 0) != 1) return@runCatching null
        Settings.Global.getString(resolver, GEOMETRY_KEY)?.let(::decodeGeometry)?.let { recorded ->
            val width = recorded.availableWidth
            val height = recorded.availableHeight
            return@runCatching if (height > width) height.toFloat() / width else null
        }
        val width = Settings.Global.getInt(resolver, WIDTH_KEY, 0)
        val height = Settings.Global.getInt(resolver, HEIGHT_KEY, 0)
        if (width <= 0 || height <= width) null else height.toFloat() / width.toFloat()
    }.getOrNull()

    fun isWorkspaceActive(context: Context): Boolean =
        runCatching { readWorkspaceActive(context) }.getOrDefault(false)

    /** Register off the WM traversal thread; null keeps callers on their safe fallback. */
    fun observeWorkspaceActive(
        context: Context,
        handler: Handler,
        onChanged: (Boolean?) -> Unit,
    ): ContentObserver {
        fun refresh() {
            runCatching { readWorkspaceActive(context) }
                .onSuccess { onChanged(it) }
                .onFailure {
                    onChanged(null)
                    NeXtepLog.warn("system_server_bridge", "Unable to observe workspace activity", it)
                }
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
        // Subscribe before reading to avoid missing an activation during initialization.
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ACTIVE_KEY), false, observer,
        )
        refresh()
        return observer
    }

    private fun readWorkspaceActive(context: Context): Boolean {
        val resolver = context.contentResolver
        val uncachedValue = resolver.call(
            Settings.AUTHORITY,
            "GET_global",
            ACTIVE_KEY,
            null,
        )?.getString(Settings.NameValueTable.VALUE)
        return (uncachedValue?.toIntOrNull()
            ?: Settings.Global.getInt(resolver, ACTIVE_KEY, 0)) == 1
    }

    fun sidebarSide(context: Context): SidebarSide = runCatching {
        Settings.Global.getString(context.contentResolver, SIDEBAR_SIDE_KEY)
            ?.let(SidebarSide::valueOf)
            ?: SidebarSide.RIGHT
    }.getOrDefault(SidebarSide.RIGHT)

    fun activeGeometry(context: Context): WorkspaceGeometry? =
        if (geometryObservationStarted.get()) observedGeometry else readActiveGeometry(context)

    private fun readActiveGeometry(context: Context): WorkspaceGeometry? = runCatching {
        if (!isWorkspaceActive(context)) return@runCatching null
        val resolver = context.contentResolver
        Settings.Global.getString(resolver, GEOMETRY_KEY)?.let { encoded ->
            decodeGeometry(encoded)?.let { return@runCatching it }
        }
        val width = Settings.Global.getInt(resolver, WIDTH_KEY, 0)
        val height = Settings.Global.getInt(resolver, HEIGHT_KEY, 0)
        if (width <= 1 || height <= 1) return@runCatching null
        WorkspaceGeometry.forDisplay(width, height, sidebarSide(context), context.resources.displayMetrics.density,
            displayRotation = context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0)
    }.getOrNull()

    private fun decodeGeometry(encoded: String): WorkspaceGeometry? = runCatching {
        val fields = encoded.split(',')
        if (fields.size != 9 && fields.size != 10) return@runCatching null
        WorkspaceGeometry(
            screenWidth = fields[0].toInt(),
            screenHeight = fields[1].toInt(),
            topHeight = fields[2].toInt(),
            sidebarWidth = fields[3].toInt(),
            sidebarSide = SidebarSide.valueOf(fields[4]),
            navigationInsets = WorkspaceNavigationInsets(
                fields[5].toInt(), fields[6].toInt(), fields[7].toInt(), fields[8].toInt(),
            ),
            displayRotation = if (fields.size == 10) fields[9].toInt() else 0,
        )
    }.getOrNull()
}
