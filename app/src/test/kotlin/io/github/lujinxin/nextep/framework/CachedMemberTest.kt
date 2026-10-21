package io.github.lujinxin.nextep.framework

import org.junit.Assert.*
import org.junit.Test

class CachedMemberTest {
    private open class Parent { private var value = 1; fun change(value: Int) { this.value = value } }
    private class Child : Parent()
    private class Getter { var value = 2; private fun readValue() = value }
    private class NullField { private val value: Int? = null; private fun readValue() = 99 }
    private class Broken { private fun broken(): Int = error("transient"); private fun working() = 7 }

    @Test fun inheritedFieldsReturnLiveValuesAndDoNotCrossContaminateInstances() {
        val member = CachedMember(listOf("absent", "value"))
        val first = Child()
        val second = Child().apply { change(9) }
        assertEquals(1, member.read(first))
        assertEquals(9, member.read(second))
        first.change(5)
        assertEquals(5, member.read(first))
    }

    @Test fun methodFallbackWorksForDifferentRuntimeClasses() {
        val member = CachedMember(listOf("missing"), listOf("missingMethod", "readValue"))
        val getter = Getter()
        assertEquals(2, member.read(getter))
        getter.value = 8
        assertEquals(8, member.read(getter))
        repeat(1000) { assertNull(member.read(Any())) }
        assertEquals(8, member.read(getter))
    }

    @Test fun nullFieldPreservesTheOriginalNoFallbackSemantics() {
        assertNull(CachedMember(listOf("value"), listOf("readValue")).read(NullField()))
    }

    @Test fun failingGetterStillUsesTheNextCandidateAndReportsFailures() {
        var failures = 0
        val member = CachedMember(emptyList(), listOf("broken", "working")) { _, _ -> failures++ }
        val source = Broken()
        assertEquals(7, member.read(source))
        assertEquals(7, member.read(source))
        assertEquals(2, failures)
    }
}
