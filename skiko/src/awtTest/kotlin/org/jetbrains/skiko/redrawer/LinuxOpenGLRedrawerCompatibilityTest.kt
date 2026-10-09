package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.LinuxDrawingSurface
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.context.OpenGLContextHandler
import org.jetbrains.skiko.renderer.AwtRenderer
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxOpenGLRedrawerCompatibilityTest {
    @Test
    fun declaresLegacyMediampInteropMethods() {
        assertEquals(
            AwtRenderer::class.java,
            SkiaLayer::class.java.getMethod("getRedrawer\$skiko").returnType
        )
        assertEquals(
            Void.TYPE,
            Class.forName("org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawerKt")
                .getMethod(
                    "access\$makeCurrent",
                    LinuxDrawingSurface::class.java,
                    Long::class.javaPrimitiveType
                ).returnType
        )
    }

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
