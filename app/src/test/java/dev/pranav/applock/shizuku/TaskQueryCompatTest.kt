package dev.pranav.applock.shizuku

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class TaskQueryCompatTest {
    class Android8 { fun getTasks(max: Int, flags: Int) = listOf(max, flags) }
    class Android10 { fun getTasks(max: Int) = listOf(max) }
    class Android12 { fun getTasks(max: Int, visible: Boolean, extras: Boolean) = listOf(max, visible, extras) }
    class Android13 { fun getTasks(max: Int, visible: Boolean, extras: Boolean, display: Int) = listOf(max, visible, extras, display) }
    class PermissionDenied { fun getTasks(max: Int): Nothing = throw SecurityException("denied: $max") }

    @Test fun android8UsesActivityManagerSignature() {
        assertEquals(listOf(8, 0), TaskQueryCompat.query(Android8()))
    }
    @Test fun android10UsesSingleArgumentSignature() {
        assertEquals(listOf(8), TaskQueryCompat.query(Android10()))
    }
    @Test fun android12AndEarly13UseThreeArguments() {
        assertEquals(listOf(8, false, false), TaskQueryCompat.query(Android12()))
    }
    @Test fun laterAndroid13UsesDisplayArgument() {
        assertEquals(listOf(8, false, false, 0), TaskQueryCompat.query(Android13()))
    }
    @Test fun permissionErrorIsReportedRatherThanHiddenAsEmptyTasks() {
        try {
            TaskQueryCompat.query(PermissionDenied())
            fail("Expected a permission error")
        } catch (e: InvocationTargetException) {
            assertTrue(e.cause is SecurityException)
        }
    }
    @Test(expected = IllegalStateException::class)
    fun unsupportedInterfaceIsReported() { TaskQueryCompat.query(Any()) }
}
