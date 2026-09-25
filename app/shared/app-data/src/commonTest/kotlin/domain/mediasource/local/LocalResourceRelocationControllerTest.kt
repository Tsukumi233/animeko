package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.database.dao.LibraryRelocationSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalResourceRelocationControllerTest {
    private fun plan(path: String) = LocalResourceRelocationPlan("old", path, false, emptyList(),
        LibraryRelocationSnapshot("disk", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        emptyList(), emptyList(), emptyList(), emptyList())

    @Test fun `preparation does not commit and repeated confirmation commits once`() = runTest {
        var commits = 0
        val gate = CompletableDeferred<Unit>()
        val controller = LocalResourceRelocationController(backgroundScope, { _, _, uri -> plan(uri) },
            { _, _, uri -> plan(uri) }, { commits++; gate.await() })
        controller.file("resource", "disk", "new")
        runCurrent()
        assertEquals("new", controller.state.value.plan!!.newLocation)
        assertEquals(0, commits)
        controller.confirm()
        controller.confirm()
        runCurrent()
        assertEquals(1, commits)
        controller.dismiss()
        assertTrue(controller.state.value.visible)
        gate.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.visible)
    }

    @Test fun `late canceled provider response cannot replace a newer preview`() = runTest {
        val first = CompletableDeferred<Unit>()
        val controller = LocalResourceRelocationController(backgroundScope, { _, _, uri ->
            if (uri == "first") withContext(NonCancellable) { first.await() }
            plan(uri)
        }, { _, _, uri -> plan(uri) }, {})
        controller.file("resource", "disk", "first")
        runCurrent()
        controller.file("resource", "disk", "second")
        runCurrent()
        first.complete(Unit)
        runCurrent()
        assertEquals("second", controller.state.value.plan!!.newLocation)
    }

    @Test fun `dismissed preparation cannot reopen the confirmation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = LocalResourceRelocationController(backgroundScope, { _, _, uri ->
            withContext(NonCancellable) { gate.await() }
            plan(uri)
        }, { _, _, uri -> plan(uri) }, {})
        controller.file("resource", "disk", "new")
        runCurrent()
        controller.dismiss()
        gate.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.visible)
    }

    @Test fun `failed confirmation requires a fresh preview`() = runTest {
        var attempts = 0
        val controller = LocalResourceRelocationController(backgroundScope, { _, _, uri -> plan(uri) },
            { _, _, uri -> plan(uri) }, { attempts++; error("changed") })
        controller.directory("root", "disk", "new")
        runCurrent()
        controller.confirm()
        runCurrent()
        controller.confirm()
        runCurrent()
        assertEquals(1, attempts)
        assertEquals("changed", controller.state.value.error!!.message)
    }
}
