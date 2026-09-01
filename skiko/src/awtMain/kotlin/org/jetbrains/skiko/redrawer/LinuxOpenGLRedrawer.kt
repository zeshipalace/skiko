package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.LinuxDrawingSurface
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.context.OpenGLContextHandler
import org.jetbrains.skiko.renderer.LinuxOpenGLRenderer
import org.jetbrains.skiko.renderer.makeLinuxOpenGLContextCurrent

/**
 * Keeps the pre-#1273 Linux renderer class and context fields available to mediamp's GLX interop
 * while the frame loop itself uses the upstream renderer/driver architecture
 */
internal class LinuxOpenGLRedrawer(
    layer: SkiaLayer,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : LinuxOpenGLRenderer(layer, analytics, properties) {
    private val contextHandler = OpenGLContextHandler()

    override fun onGlContextChanged(context: DirectContext?) {
        contextHandler.context = context
    }
}

/**
 * Retains the bytecode entry point used by Voxzen's Skia GPU cache controller
 */
@JvmName("access\$makeCurrent")
internal fun makeCurrentForInterop(surface: LinuxDrawingSurface, context: Long) {
    makeLinuxOpenGLContextCurrent(surface, context)
}
