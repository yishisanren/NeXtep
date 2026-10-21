package io.github.lujinxin.nextep.systemui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.UserHandle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.display.SlotState
import io.github.lujinxin.nextep.display.TaskSwitcherView
import io.github.lujinxin.nextep.framework.WorkspaceIntentResolver
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.WorkspaceLaunchSequence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchFlowUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)!!
    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test fun compactHeaderHasOnlyComparePayAndMoreAndAlwaysShowsADate() {
        val tapped = mutableListOf<String>()
        val strip = TopAppStripView(context, {}, {}, { _, _ -> false }, { _, _ -> false },
            { tapped += "pay" }, { tapped += "more" }, { tapped += "compare" })
        strip.setWorkspaceVisible(false)
        val density = context.resources.displayMetrics.density
        for (landscape in listOf(false, true)) {
            strip.setLandscape(landscape, landscape)
            layout(strip, (360 * density).toInt(), (142 * density).toInt())
            val compare = field(strip, "compareButton") as TextView
            val pay = field(strip, "paymentButton") as TextView
            val more = field(strip, "toolsButton") as TextView
            val row = compare.parent as LinearLayout
            assertEquals(4, row.childCount) // Heading/date plus exactly three controls.
            assertSame(compare, row.getChildAt(1))
            assertSame(pay, row.getChildAt(2))
            assertSame(more, row.getChildAt(3))
            assertTrue(compare.right <= pay.left && pay.right <= more.left)
            assertTrue(more.right <= row.width)
            assertTrue(compare.height >= (48 * density).toInt())
            val title = field(strip, "titleView") as TextView
            assertTrue(title.text.any(Char::isDigit))
            assertTrue((field(strip, "heading") as TextView).text.isNotBlank())
            val player = field(strip, "mediaHost") as View
            assertEquals(View.GONE, player.visibility)
            assertEquals(0, player.layoutParams.height)
            compare.performClick(); pay.performClick(); more.performClick()
        }
        assertEquals(listOf("compare", "pay", "more", "compare", "pay", "more"), tapped)
        for (theme in WorkbenchAppearance.entries) {
            assertTrue(Color.blue(theme.accent) > Color.red(theme.accent))
        }
    }

    private fun exported(intent: Intent, packageName: String = checkNotNull(intent.`package`)): ResolveInfo {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.Router"
                enabled = true
                exported = true
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    enabled = true
                }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, info)
        return info
    }

    @Test fun anAndroidResolverCannotBeMistakenForTheRequestedSearchApp() {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("test-search://products?q=milk"))
            .setPackage("test.shop")
        exported(intent, "android")
        exported(intent, "test.shop")
        val resolved = checkNotNull(WorkspaceIntentResolver.resolve(context.packageManager, intent))
        assertEquals("test.shop", resolved.component?.packageName)
        assertEquals(intent.data, resolved.data)
        assertNull(WorkspaceIntentResolver.resolve(context.packageManager, Intent(Intent.ACTION_VIEW)))
    }

    private fun tools(search: (List<Intent>, (Result<Unit>) -> Unit) -> Result<Unit>,
        theme: () -> Unit = {}, side: () -> SidebarSide = { SidebarSide.RIGHT },
        setSide: (SidebarSide) -> Unit = {}, cancel: () -> Unit = {}) = WorkbenchToolsController(
        context, { WorkbenchAppearance.DARK }, { false }, {}, { false }, {},
        { Result.success(Unit) }, search, { false }, {}, {}, theme, side, setSide, cancel,
    )

    @Test fun searchInputActuallySubmitsAllResolvedAppsAndWaitsForTaskPlacement() {
        val query = "牛奶 & 500ml"
        val expected = WorkbenchIntents.searches(WorkbenchIntents.Search.DELIVERY, query)
        expected.forEach { exported(it.intent) }
        var submitted = emptyList<Intent>()
        var complete: ((Result<Unit>) -> Unit)? = null
        val tools = tools({ apps, done -> submitted = apps; complete = done; Result.success(Unit) })
        tools.showSearch()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(query)
        val button = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        button.performClick()
        assertEquals(expected.map { it.intent.data }, submitted.map { it.data })
        assertTrue(dialog.isShowing)
        assertFalse(button.isEnabled)
        assertFalse(input.isEnabled)
        complete!!.invoke(Result.success(Unit))
        assertFalse(dialog.isShowing)
    }

    @Test fun partialSearchFailureNamesTheAppAndDoesNotPretendToComplete() {
        val query = "milk"
        val apps = WorkbenchIntents.searches(WorkbenchIntents.Search.DELIVERY, query)
        apps.forEach { exported(it.intent) }
        var complete: ((Result<Unit>) -> Unit)? = null
        val tools = tools({ _, done -> complete = done; Result.success(Unit) })
        tools.showSearch()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText(query)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        complete!!.invoke(Result.failure(WorkspaceLaunchSequence.Failure(1, 1,
            java.util.concurrent.TimeoutException())))
        assertTrue(dialog.isShowing)
        assertTrue(input.error.toString().contains(apps[1].name))
        assertTrue(input.error.toString().contains("1/3"))
        assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(input.isEnabled)
        tools.dismiss()
    }

    @Test fun dismissingAnInProgressSearchCancelsRemainingLaunches() {
        WorkbenchIntents.searches(WorkbenchIntents.Search.DELIVERY, "milk").forEach { exported(it.intent) }
        var cancelled = 0
        val tools = tools({ _, _ -> Result.success(Unit) }, cancel = { cancelled++ })
        tools.showSearch()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("milk")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        tools.dismiss()
        assertEquals(1, cancelled)
    }

    @Test fun moreContainsThemeAndSideSelectionAndNoMediaControls() {
        var theme = 0
        var side = SidebarSide.RIGHT
        val tools = tools({ _, _ -> Result.success(Unit) }, { theme++ }, { side }, { side = it })
        tools.showMore()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        val items = (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }
        assertTrue(items[0].contains("light", true) || items[0].contains("外观"))
        assertFalse(items.any { it == context.getString(R.string.media_control_title) })
        dialog.listView.performItemClick(null, 0, 0)
        assertEquals(1, theme)
        tools.showMore(); dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 1)
        assertEquals(SidebarSide.LEFT, side)
        tools.dismiss()
    }

    @Test fun anAppTileDragReachesTheRequestedWindowAndPreservesItsUser() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val destination = TaskSwitcherView(activity, 0).apply { showState(SlotState.Ready(20)) }
        var dropped: WorkspaceInternalDragController.Payload.App? = null
        var target: WorkspaceInternalDragController.Target? = null
        val drag = WorkspaceInternalDragController(activity,
            { WorkspaceGeometry.forDisplay(360, 800, density = 1f) }, { listOf(destination) }, {},
            { payload, where -> dropped = payload as? WorkspaceInternalDragController.Payload.App; target = where; true }, {})
        val strip = TopAppStripView(activity, {}, {},
            { view, intent -> drag.start(view, WorkspaceInternalDragController.Payload.App(intent)) }, drag::touch)
        root.addView(strip, FrameLayout.LayoutParams(360, 142))
        root.addView(destination, FrameLayout.LayoutParams(80, 160).apply { leftMargin = 270; topMargin = 160 })
        layout(root, 360, 600)
        val user = UserHandle.getUserHandleForUid(999 * 100_000)
        val source = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("test.shop", "test.shop.Main"))
            .putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER, user)
        val render = strip.javaClass.getDeclaredMethod("renderApps", List::class.java).apply { isAccessible = true }
        render.invoke(strip, listOf(TopAppRepository.AppEntry("shop", "Shop", ColorDrawable(Color.BLUE), source, 999)))
        layout(root, 360, 600)
        val tile = (field(strip, "appRow") as LinearLayout).getChildAt(0)
        assertTrue(tile.performLongClick())
        val bounds = android.graphics.Rect()
        assertTrue(destination.getScreenVisibleRect(bounds))
        val event = MotionEvent.obtain(0, 100, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try { assertTrue(tile.dispatchTouchEvent(event)) } finally { event.recycle() }
        assertEquals(WorkspaceInternalDragController.Target.Slot(0), target)
        assertEquals(source.component, dropped?.intent?.component)
        assertEquals(user, dropped?.intent?.getParcelableExtra(TriggerBroadcastContract.EXTRA_TARGET_USER, UserHandle::class.java))
        drag.cancel()
        activity.finish()
    }

    @Test fun plusActuallyStartsHomeAndASecondTapCancelsTheTargetSlot() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        exported(home, "test.launcher")
        val slots = List(4) { io.github.lujinxin.nextep.display.VirtualDisplaySlot(context, it) }
        val slot = slots.first()
        val coordinator = io.github.lujinxin.nextep.display.SlotTaskCoordinator(context, slots,
            io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator(context),
            { WorkspaceGeometry.forDisplay(360, 800, density = 1f) })
        coordinator.javaClass.getDeclaredField("active").apply { isAccessible = true }.set(coordinator, true)
        coordinator.onDisplayReady(0, 42)
        assertTrue(slot.view.performClick())
        assertTrue(coordinator.selectingDesktopApp)
        val requested = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, requested.action)
        assertTrue(requested.hasCategory(Intent.CATEGORY_HOME))
        assertEquals("test.launcher", requested.`package`)
        assertTrue(slot.view.performClick())
        assertFalse(coordinator.selectingDesktopApp)
        assertNull(shadowOf(context).nextStartedActivity)
        slot.view.performClick()
        assertTrue(coordinator.selectingDesktopApp)
        shadowOf(context).nextStartedActivity
        coordinator.openExternalInMain(Intent(Intent.ACTION_VIEW).setPackage("missing.payment"))
        assertFalse(coordinator.selectingDesktopApp)
    }

    @Test fun selectingAnAppAfterItsTargetDisplayChangedCannotOpenItInTheWrongWindow() {
        exported(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "test.launcher")
        val slots = List(4) { io.github.lujinxin.nextep.display.VirtualDisplaySlot(context, it) }
        val slot = slots.first()
        val coordinator = io.github.lujinxin.nextep.display.SlotTaskCoordinator(context, slots,
            io.github.lujinxin.nextep.task.MainTaskPresentationCoordinator(context),
            { WorkspaceGeometry.forDisplay(360, 800, density = 1f) })
        coordinator.javaClass.getDeclaredField("active").apply { isAccessible = true }.set(coordinator, true)
        coordinator.onDisplayReady(0, 42)
        slot.view.performClick()
        shadowOf(context).nextStartedActivity
        val app = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("test.app", "test.app.Main"))
        assertTrue(coordinator.openFromLauncher(app).isFailure)
        assertFalse(coordinator.selectingDesktopApp)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
