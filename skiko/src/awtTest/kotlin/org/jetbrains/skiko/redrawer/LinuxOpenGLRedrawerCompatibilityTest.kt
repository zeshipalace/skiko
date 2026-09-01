package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.context.OpenGLContextHandler
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxOpenGLRedrawerCompatibilityTest {
    @Test
    fun declaresLegacyMediampInteropFields() {
        val redrawerClass = LinuxOpenGLRedrawer::class.java

        assertEquals(
            OpenGLContextHandler::class.java,
            redrawerClass.getDeclaredField("contextHandler").type
        )
        assertEquals(
            Long::class.javaPrimitiveType,
            redrawerClass.getDeclaredField("context").type
        )
    }
}
