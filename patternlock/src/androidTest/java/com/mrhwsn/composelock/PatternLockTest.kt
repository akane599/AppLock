package com.mrhwsn.composelock

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PatternLockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawingUsesCurrentCallbackAndResizedGeometry() {
        val size = mutableStateOf(240.dp)
        val phase = mutableStateOf(0)
        val results = mutableListOf<Pair<Int, List<Int>>>()
        compose.setContent {
            val capturedPhase = phase.value
            PatternLock(
                modifier = Modifier.size(size.value).testTag("pattern"),
                sensitivity = 20f,
                callback = object : LockCallback {
                    override fun onStart(dot: Dot) {}
                    override fun onDotConnected(dot: Dot) {}
                    override fun onResult(result: List<Dot>) {
                        results.add(capturedPhase to result.map { it.id })
                    }
                }
            )
        }
        fun draw() {
            compose.onNodeWithTag("pattern").performTouchInput {
                down(Offset(width / 4f, height / 4f))
                moveTo(Offset(width * 3 / 4f, height / 4f))
                up()
            }
            compose.waitForIdle()
        }
        draw()
        compose.runOnIdle { phase.value = 1 }
        draw()
        compose.runOnIdle { size.value = 320.dp; phase.value = 2 }
        draw()
        compose.runOnIdle {
            assertEquals(listOf(0, 1, 2), results.map { it.first })
            results.forEach { assertEquals(listOf(1, 4, 7), it.second) }
        }
    }
}
