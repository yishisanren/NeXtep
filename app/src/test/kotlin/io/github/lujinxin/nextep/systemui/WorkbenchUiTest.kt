package io.github.lujinxin.nextep.systemui

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import io.github.lujinxin.nextep.display.ContentDropCompat
import io.github.lujinxin.nextep.display.TaskSwitcherView
import io.github.lujinxin.nextep.framework.VirtualDisplayFlagsCompat
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)!!

    @Test fun paymentIsInTheTopRightControlRowAndNeverInTheAppCatalogue() {
        var opened = 0
        val view = TopAppStripView(context, {}, {},
            { _, _ -> false }, { _, _ -> false }, onPaymentRequested = { opened++ })
        val width = (411 * context.resources.displayMetrics.density).toInt()
        val height = (190 * context.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val payment = field(view, "paymentButton") as View
        val bounds = Rect()
        payment.getDrawingRect(bounds)
        view.offsetDescendantRectToMyCoords(payment, bounds)
        assertTrue(bounds.left > width / 2)
        assertTrue(bounds.bottom <= (128 * context.resources.displayMetrics.density).toInt())
        val heading = field(view, "heading") as View
        val headingBounds = Rect()
        heading.getDrawingRect(headingBounds)
        view.offsetDescendantRectToMyCoords(heading, headingBounds)
        assertTrue(headingBounds.top >= (80 * context.resources.displayMetrics.density).toInt())
        val apps = field(view, "appScroll") as View
        assertTrue(apps.height >= (43 * context.resources.displayMetrics.density).toInt())
        assertNotSame(field(view, "appRow"), payment.parent)
        assertTrue(payment.performClick())
        assertEquals(1, opened)
    }

    @Test fun lightAndDarkAppearancesActuallyRecolourTheDrawnControlIcons() {
        val view = TopAppStripView(context, {}, {},
            { _, _ -> false }, { _, _ -> false })
        for (theme in WorkbenchAppearance.entries) {
            view.setAppearance(theme)
            for (name in listOf("paymentButton", "compareButton", "toolsButton")) {
                val button = field(view, name) as android.widget.ImageButton
                assertEquals(theme.accent, button.imageTintList!!.defaultColor)
                val icon = button.drawable as WorkbenchIcon
                assertEquals(theme.accent, icon.color)
                val image = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, 48, 48)
                icon.draw(android.graphics.Canvas(image))
                val painted = (0 until 48).flatMap { x -> (0 until 48).map { y -> image.getPixel(x, y) } }
                    .filter { android.graphics.Color.alpha(it) == 255 }
                assertTrue(name, painted.isNotEmpty())
                // Light icons must not stay white on the light panel (±4 covers premultiply rounding).
                fun near(a: Int, b: Int) = listOf(16, 8, 0).all { kotlin.math.abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 4 }
                assertTrue(name, painted.all { near(it, theme.accent) })
                val background = button.background as android.graphics.drawable.RippleDrawable
                assertEquals(theme.card, (background.getDrawable(0) as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
            }
        }
        assertEquals(context.getString(io.github.lujinxin.nextep.R.string.open_settings),
            (field(view, "toolsButton") as View).contentDescription)
    }

    @Test fun emptySlotBadgeIsSmallerThanBeforeAndFollowsTheAppearance() {
        val view = TaskSwitcherView(context, 1)
        val density = context.resources.displayMetrics.density
        val badge = field(view, "statusView") as View
        val size = (28 * density).toInt()
        assertEquals(size, badge.layoutParams.width)
        assertEquals(size, badge.layoutParams.height)
        for (theme in WorkbenchAppearance.entries) {
            view.setAppearance(theme)
            view.showState(io.github.lujinxin.nextep.display.SlotState.Empty)
            assertEquals(View.VISIBLE, badge.visibility)
            assertNull(badge.foreground)
            val layers = badge.background as android.graphics.drawable.LayerDrawable
            val plus = layers.getDrawable(1) as WorkbenchIcon
            assertEquals(theme.secondary, plus.color)
            val image = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            layers.setBounds(0, 0, size, size)
            layers.draw(android.graphics.Canvas(image))
            val inset = (7 * density).toInt()
            // The plus stays inside its inset; the disc keeps the whole badge under 28dp.
            assertTrue(android.graphics.Color.alpha(image.getPixel(size / 2, inset + 1)) > 0)
            assertNotEquals(theme.secondary, image.getPixel(size / 2, 1) or 0xFF000000.toInt())
            val disc = (layers.getDrawable(0) as android.graphics.drawable.GradientDrawable).color!!.defaultColor
            assertTrue(android.graphics.Color.alpha(disc) < 160)
        }
    }


    @Test fun captureProtectionDoesNotRequirePermissionToCreateASecureDisplay() {
        val view = TaskSwitcherView(context, 3)
        assertEquals(SurfaceView::class.java, view.textureView.javaClass)
        assertTrue(view.clipToOutline)
        assertNull(view.capturePreview())
        VirtualDisplayFlagsCompat.resolveCandidates().forEach {
            // SystemUI on the user's device lacks CAPTURE_SECURE_VIDEO_OUTPUT.
            // Every attempted configuration must work without that permission.
            assertEquals(0, it.value and DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE)
            assertNotEquals(0, it.value and DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            assertFalse("SECURE" in it.names)
        }
        assertEquals(DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            VirtualDisplayFlagsCompat.resolveCandidates().last().value)
    }

    @Test fun liveOutputIsFittedWithoutStretchingOrCroppingAppControls() {
        val view = TaskSwitcherView(context, 0)
        view.setOutputSize(1080, 2354)
        view.measure(View.MeasureSpec.makeMeasureSpec(215, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(468, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 215, 468)
        val output = view.textureView
        assertTrue(output.width <= 215 && output.height <= 468)
        assertEquals(1080f / 2354, output.width.toFloat() / output.height, .003f)
    }

    @Test fun exchangedLiveSlotHasNoSecondScaleAnimationWhenPreviewReveals() {
        val view = TaskSwitcherView(context, 0)
        view.setBusy(true)
        view.showState(io.github.lujinxin.nextep.display.SlotState.Occupied(20, 42,
            android.content.ComponentName("example.app", "example.app.Main")))
        assertEquals(1f, view.textureView.scaleX, 0f)
        assertEquals(1f, view.textureView.scaleY, 0f)
        assertEquals(1f, view.textureView.alpha, 0f)
        view.setBusy(false)
        assertEquals(1f, view.textureView.scaleX, 0f)
    }

    @Test fun everySearchKeepsUnicodeAndSeparatorsInsideOneQuery() {
        val query = "牛奶 & #/ 500ml + 50% 😀"
        val targets = WorkbenchIntents.searches(WorkbenchIntents.Search.SHOPPING, query)
        assertEquals(4, targets.size)
        val pdd = targets[0].intent.data!!
        val dy = targets[1].intent.data!!
        val jd = JSONObject(targets[2].intent.data!!.getQueryParameter("params")!!)
        val tb = targets[3].intent.data!!
        assertEquals(query, pdd.getQueryParameter("search_key"))
        assertEquals(query, dy.getQueryParameter("keyword"))
        assertEquals("search", jd.getString("from"))
        assertEquals(query, java.net.URLDecoder.decode(jd.getString("keyWord"), "UTF-8"))
        assertEquals("commodity_center", dy.getQueryParameter("search_style"))
        assertEquals("1", dy.getQueryParameter("hideMiddlePage"))
        assertNull(dy.getQueryParameter("search_type"))
        assertEquals(query, tb.getQueryParameter("q"))
        assertEquals(query, WorkbenchIntents.searches(WorkbenchIntents.Search.DELIVERY, query)[2]
            .intent.data!!.getQueryParameter("query"))
        assertThrows(IllegalArgumentException::class.java) { WorkbenchIntents.searches(WorkbenchIntents.Search.SHOPPING, "  ") }
        assertThrows(IllegalArgumentException::class.java) { WorkbenchIntents.searches(WorkbenchIntents.Search.SHOPPING, "a".repeat(201)) }
    }

    @Test fun droppedTextAndFilesUseStandardTargetedReadOnlySharing() {
        val text = ContentDropCompat.intent(ClipData.newPlainText("source", "hello"), "target.app")
        assertEquals(Intent.ACTION_SEND, text.action)
        assertEquals("hello", text.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("target.app", text.`package`)
        val files = ClipData(ClipDescription("images", arrayOf("image/png")),
            ClipData.Item(Uri.parse("content://source/one"))).apply {
            addItem(ClipData.Item(Uri.parse("content://source/two")))
        }
        val share = ContentDropCompat.intent(files, "target.app")
        assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action)
        assertEquals("image/png", share.type)
        assertEquals(2, share.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)!!.size)
        assertNotEquals(0, share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertSame(files, share.clipData)
    }

    @Test fun instantSearchUsesDedicatedResultPagesAndPreservesNestedQueryEncoding() {
        val query = "牛奶 & #/ 500ml 😀"
        val targets = WorkbenchIntents.searches(WorkbenchIntents.Search.DELIVERY, query)
        val jd = JSONObject(targets[0].intent.data!!.getQueryParameter("params")!!)
        assertEquals("m", jd.getString("des"))
        val jdPage = Uri.parse(jd.getString("url"))
        assertEquals("daojia.jd.com", jdPage.host)
        val fragment = Uri.parse("https://daojia.jd.com${jdPage.encodedFragment}")
        assertEquals("/IndexSearch", fragment.path)
        assertEquals(query, fragment.getQueryParameter("keyword"))
        assertEquals(query, fragment.getQueryParameter("real_keyword"))
        val tb = targets[1].intent.data!!
        assertEquals("tb.ele.me", tb.host)
        assertEquals("/app/eleme/et-search/result", tb.path)
        assertEquals(query, tb.getQueryParameter("keyword"))
        val mt = targets[2].intent.data!!
        assertEquals("/takeout/poi/search", mt.path)
        assertEquals(query, mt.getQueryParameter("query"))
    }

    @Test fun arbitraryIntentsAndFilePathsAreRejectedInsteadOfExecutedOrReadBySystemUi() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentDropCompat.intent(ClipData.newIntent("intent", Intent("malicious.action")), "target.app")
        }
        val file = ClipData(ClipDescription("file", arrayOf("application/octet-stream")),
            ClipData.Item(Uri.parse("file:///data/private/file")))
        assertThrows(IllegalArgumentException::class.java) { ContentDropCompat.intent(file, "target.app") }
    }

    @Test fun mainAppAndMiniProgramStayDistinctWhenTheyShareAPackage() {
        val main = android.content.ComponentName("test.chat", "test.chat.Main")
        val chat = android.content.ComponentName("test.chat", "test.chat.Conversation")
        val mini = android.content.ComponentName("test.chat", "test.chat.MiniProgram")
        fun task(id: Int, top: android.content.ComponentName, base: android.content.ComponentName) =
            io.github.lujinxin.nextep.task.TaskRepository.TaskSnapshot(id, 0, top, Rect(), 1, 1, 480, null, 0,
                baseComponent = base)
        val app = task(1, chat, main)
        val program = task(2, mini, mini)
        val repository = io.github.lujinxin.nextep.task.TaskRepository(context)
        assertSame(app, repository.selectForLaunch(listOf(program, app), main, true))
        assertSame(program, repository.selectForLaunch(listOf(program, app), mini, false))
        assertNull(repository.selectForLaunch(listOf(program), main, true))
        assertNull(repository.selectForLaunch(listOf(program, app), android.content.ComponentName("test.chat", "test.chat.Share"), false))
    }

    @Test fun publishedGeometryCarriesTheControlEdgeToOtherProcesses() {
        val bridge = io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
        val decode = bridge.javaClass.getDeclaredMethod("decodeGeometry", String::class.java).apply { isAccessible = true }
        for (bottom in listOf(true, false)) {
            val geometry = WorkspaceGeometry.forDisplay(1216, 2640, SidebarSide.LEFT, 3f, controlAtBottom = bottom)
            bridge.publish(context, true, geometry)
            val encoded = android.provider.Settings.Global.getString(context.contentResolver, "nextep_workspace_geometry_v1")
            assertEquals(geometry, decode.invoke(bridge, encoded))
        }
        // Records written by the previous release still decode with the control at the top.
        assertEquals(WorkspaceGeometry.forDisplay(1216, 2640, SidebarSide.RIGHT, 3f, displayRotation = 0),
            decode.invoke(bridge, "1216,2640,531,244,RIGHT,0,0,0,0,0"))
    }

    @Test fun roundedMainFrameMasksOnlyCornersAndLeavesAppContentVisible() {
        val image = android.graphics.Bitmap.createBitmap(120, 200, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        val frame = WorkbenchFrameDrawable(android.graphics.Color.BLACK, android.graphics.Color.YELLOW, 20f, 2f)
        frame.setBounds(0, 0, 120, 200)
        frame.draw(android.graphics.Canvas(image))
        assertEquals(android.graphics.Color.BLACK, image.getPixel(0, 0))
        assertEquals(android.graphics.Color.BLUE, image.getPixel(60, 100))
        assertEquals(android.graphics.Color.YELLOW, image.getPixel(60, 0))
    }
}
