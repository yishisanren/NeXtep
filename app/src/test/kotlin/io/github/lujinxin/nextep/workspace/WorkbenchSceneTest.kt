package io.github.lujinxin.nextep.workspace

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkbenchSceneTest {
    @Test fun scenesRoundTripThroughJsonReplaceByNameAndKeepTheNewestEight() {
        val work = WorkbenchScene("工作", "com.a.mail", listOf("com.a.chat", null, "com.a.notes", null))
        val play = WorkbenchScene("追剧", null, listOf(null, "com.b.video", null, null))
        assertEquals(listOf(work, play), WorkbenchScene.decode(WorkbenchScene.encode(listOf(work, play))))
        assertEquals(emptyList<WorkbenchScene>(), WorkbenchScene.decode(null))
        assertEquals(emptyList<WorkbenchScene>(), WorkbenchScene.decode("not json"))
        val replaced = WorkbenchScene.save(listOf(work, play), work.copy(main = "com.a.calendar"))
        assertEquals(listOf(play, work.copy(main = "com.a.calendar")), replaced)
        val many = (1..9).fold(emptyList<WorkbenchScene>()) { acc, i -> WorkbenchScene.save(acc, WorkbenchScene("s$i", null, List(4) { null })) }
        assertEquals((2..9).map { "s$it" }, many.map { it.name })
    }
}
