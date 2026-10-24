package io.github.lujinxin.nextep.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkbenchSceneTest {
    private val none = List(4) { null as String? }

    @Test fun scenesRoundTripReplaceInPlaceAndStopAtSix() {
        val work = WorkbenchScene("工作", "com.a.mail", listOf("com.a.chat", null, "com.a.notes", null), icon = "work")
        val play = WorkbenchScene("追剧", null, listOf(null, "com.b.video", null, null))
        val tools = WorkbenchScene.builtins()
        val all = tools + work + play
        assertEquals(all, WorkbenchScene.decode(WorkbenchScene.encode(all)))
        assertEquals(emptyList<WorkbenchScene>(), WorkbenchScene.decode(null))
        assertEquals(emptyList<WorkbenchScene>(), WorkbenchScene.decode("not json"))
        // Scenes saved before icons existed decode as text scenes; an unknown icon falls back to text.
        assertEquals(listOf(WorkbenchScene("旧", "com.c", none)),
            WorkbenchScene.decode("""[{"name":"旧","main":"com.c","slots":[null,null,null,null]}]"""))
        assertNull(WorkbenchScene.decode("""[{"name":"x","main":null,"slots":[null,null,null,null],"icon":"rocket"}]""")[0].icon)

        val replaced = WorkbenchScene.save(listOf(work, play), work.copy(main = "com.a.calendar"))
        assertEquals(listOf(work.copy(main = "com.a.calendar"), play), replaced)
        val full = (1..6).fold(emptyList<WorkbenchScene>()) { acc, i -> WorkbenchScene.save(acc, WorkbenchScene("s$i", null, none)) }
        assertEquals((1..6).map { "s$it" }, full.map { it.name })
        assertEquals(full, WorkbenchScene.save(full, WorkbenchScene("s7", null, none)))
    }

    @Test fun defaultsAreSeededOnceRestoredWithinTheLimitAndNamesStayUnique() {
        val mine = WorkbenchScene("游戏", "com.g", none, icon = "game")
        val seeded = WorkbenchScene.seeded(listOf(mine))
        assertEquals(listOf(WorkbenchScene.BUILTIN_COMPARE, WorkbenchScene.BUILTIN_PAYMENT, null), seeded.map { it.builtin })
        assertEquals(seeded, WorkbenchScene.seeded(seeded))
        val withoutCompare = seeded.filterNot { it.builtin == WorkbenchScene.BUILTIN_COMPARE }
        assertEquals(seeded.map { it.name }.sorted(), WorkbenchScene.restoreBuiltins(withoutCompare).map { it.name }.sorted())
        val fullOfMine = (1..6).map { WorkbenchScene("m$it", "com.m$it", none) }
        assertEquals(fullOfMine, WorkbenchScene.restoreBuiltins(fullOfMine))
        assertEquals("游戏 2", WorkbenchScene.uniqueName(listOf(mine), "游戏"))
        assertEquals("工作", WorkbenchScene.uniqueName(listOf(mine), "工作"))
        assertEquals(4, WorkbenchScene.textUnits("上班"))
        assertEquals(4, WorkbenchScene.textUnits("Work"))
        assertEquals("上班", WorkbenchScene.textLabel("上班模式"))
        assertEquals("Work", WorkbenchScene.textLabel("Workday"))
        assertEquals("A读", WorkbenchScene.textLabel("A读书"))
    }

    @Test fun activeSceneIsTheMostSpecificLayoutWhoseAppsAreAllInPlace() {
        val reading = WorkbenchScene("读", "com.read", listOf(null, null, null, null))
        val reader = WorkbenchScene("读写", "com.read", listOf("com.notes", null, null, null))
        val scenes = WorkbenchScene.builtins() + reading + reader
        assertEquals(reader, WorkbenchScene.active(scenes, "com.read", listOf("com.notes", "com.x", null, null)))
        assertEquals(reading, WorkbenchScene.active(scenes, "com.read", listOf("com.other", null, null, null)))
        assertNull(WorkbenchScene.active(scenes, null, listOf(null, null, null, null)))
    }
}
