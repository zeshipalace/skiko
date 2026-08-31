package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.*
import org.jetbrains.skia.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.OpenGLContextHandler
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal class LinuxOpenGLRedrawer(
    layer: SkiaLayer,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties
) : AbstractOpenGLRedrawer(layer, analytics), Redrawer {
    init {
        loadOpenGLLibrary()
    }

    // 顶层 Redrawer 必须保持本类名与 contextHandler/context 字段,这是 mediamp
    // (SkiaOpenGLInterop) 按 Skiko 0.9.37.4 布局反射 GLX 互操作环境的契约;
    // 帧循环行为不变,全部委托给内部的 OnScreenRedrawer
    private val frameLoop = OnScreenRedrawer(layer, this)
    private val contextHandler = OpenGLContextHandler()

    private var context = 0L
    private val swapInterval = if (properties.isVsyncEnabled) 1 else 0

    init {
        layer.backedLayer.lockLinuxDrawingSurface {
            context = it.createContext(layer.transparency)
            if (context == 0L) {
                throw RenderException("Cannot create Linux GL context")
            }
            it.makeCurrent(context)
            adapterName.also { adapterName ->
                if (adapterName != null && !isVideoCardSupported(
                        GraphicsApi.OPENGL,
                        hostOs,
                        adapterName
                    )
                ) {
                    throw RenderException("Cannot create Linux GL context")
                }
            }
            onDeviceChosen(adapterName)
            it.setSwapInterval(swapInterval)
        }
        onContextInit()
    }

    private val frameJob = Job()

    @Volatile
    private var frameLimit = 0.0
    private val frameLimiter = layerFrameLimiter(
        CoroutineScope(frameJob),
        layer.backedLayer,
        onNewFrameLimit = { frameLimit = it }
    )

    private suspend fun limitFramesIfNeeded() {
        // Some Linuxes don't turn vsync on, so we apply additional frame limit (which should be no longer than enabled vsync)
        if (properties.isVsyncEnabled) {
            try {
                frameLimiter.awaitNextFrame()
            } catch (e: CancellationException) {
                // ignore
            }
        }
    }

    override val schedulesOwnFrames: Boolean get() = true

    private lateinit var frameHost: FrameHost

    override fun attachFrameHost(host: FrameHost) {
        frameHost = host
    }

    override fun onFrameRequested(throttledToVsync: Boolean) {
        toRedraw.add(this)
        frameDispatcher.scheduleFrame()
    }

    override fun releaseResources() {
        frameJob.cancel()
        layer.backedLayer.lockLinuxDrawingSurface {
            // makeCurrent is mandatory to destroy context, otherwise, OpenGL will destroy wrong context (from another window).
            // see the official example: https://www.khronos.org/opengl/wiki/Tutorial:_OpenGL_3.0_Context_Creation_(GLX)
            it.makeCurrent(context)
            disposeGlResources()
            it.destroyContext(context)
        }
    }

    override fun onGlContextChanged(context: DirectContext?) {
        contextHandler.context = context
    }

    // Redrawer 委托:帧循环仍由内部 OnScreenRedrawer 承载,行为与 #1234 重构后一致
    // setVisible 与 AWTRedrawer 成员同签名且两边都有默认实现,必须显式消歧;
    // 不能委托给 frameLoop,否则 OnScreenRedrawer 回调 renderer.setVisible 形成死循环
    override fun setVisible(isVisible: Boolean) = super<AbstractOpenGLRedrawer>.setVisible(isVisible)
    override fun dispose() = frameLoop.dispose()
    override fun needRender(throttledToVsync: Boolean) = frameLoop.needRender(throttledToVsync)
    override fun renderImmediately() = frameLoop.renderImmediately()
    override fun syncBoundsFromPlatformComponent() = frameLoop.syncBoundsFromPlatformComponent()
    override fun update(nanoTime: Long) = frameLoop.update(nanoTime)
    override fun onLayerComponentResized() = frameLoop.onLayerComponentResized()

    override suspend fun renderFrame(scope: LayerDrawScope, immediate: Boolean) {
        layer.backedLayer.lockLinuxDrawingSurface {
            it.makeCurrent(context)
            with(scope) { drawFrame() }
            val turnOfVsync =
                properties.isVsyncEnabled && !SkikoProperties.linuxWaitForVsyncOnRedrawImmediately
            if (turnOfVsync) {
                it.setSwapInterval(0)
            }
            it.swapBuffers()
            OpenGLApi.instance.glFlush()
            if (turnOfVsync) {
                it.setSwapInterval(swapInterval)
            }
        }
    }

    private fun drawInBatch() {
        frameHost.inFrame { scope -> with(scope) { drawFrame() } }
    }

    companion object {
        /**
         * 多窗口并行交换所用的守护线程池，见 frameDispatcher 中的说明
         */
        private val swapExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "SkikoLinuxVsyncSwap").apply { isDaemon = true }
        }

        private val toRedraw = mutableSetOf<LinuxOpenGLRedrawer>()
        private val toRedrawCopy = mutableSetOf<LinuxOpenGLRedrawer>()
        private val toRedrawVisible = toRedrawCopy
            .asSequence()
            .filterNot(LinuxOpenGLRedrawer::isDisposed)
            .filter { it.layer.isShowing }

        private val frameDispatcher = FrameDispatcher(MainUIDispatcher) {
            toRedrawCopy.addAll(toRedraw)
            toRedraw.clear()

            // we should wait for the window with the maximum frame limit to avoid bottleneck when there is a window on a slower monitor
            toRedrawVisible.maxByOrNull { it.frameLimit }?.limitFramesIfNeeded()

            val nanoTime = System.nanoTime()
            for (redrawer in toRedrawVisible) {
                try {
                    redrawer.frameHost.updateIfRequested(nanoTime)
                } catch (e: CancellationException) {
                    // continue
                }
            }

            val drawingSurfaces =
                toRedrawVisible.associateWith { lockLinuxDrawingSurface(it.layer.backedLayer) }
            try {
                for (redrawer in toRedrawVisible) {
                    drawingSurfaces[redrawer]!!.makeCurrent(redrawer.context)
                    redrawer.drawInBatch()
                }

                // TODO(demin): How can we properly synchronize multiple windows with multiple displays?
                //  I checked, and without vsync there is no tearing. Is it only my case (Ubuntu, Nvidia, X11),
                //  or Ubuntu write all the screen content into an intermediate buffer? If so, then we probably only
                //  need a frame limiter.

                val vsyncRedrawers =
                    toRedrawVisible.filter { it.properties.isVsyncEnabled }.toList()

                // NVIDIA 闭源驱动的 XWayland 环境下，interval=0 的立即呈现会把仍在显示的缓冲
                // 交还给应用重绘，透明窗口每帧的 clear(Color.TRANSPARENT) 因此直接可见（整窗闪透明），
                // 因此所有开启 vsync 的窗口都以 interval 1 呈现。为避免在单个线程上串行等待
                // vsync（N 个窗口就要等 N 次 vblank），多窗口时交换阶段分发到独立线程并行阻塞，
                // 各窗口互不影响地对齐各自 vblank
                for (redrawer in toRedrawVisible.filter { !it.properties.isVsyncEnabled }) {
                    drawingSurfaces[redrawer]!!.makeCurrent(redrawer.context)
                    drawingSurfaces[redrawer]!!.setSwapInterval(0)
                    drawingSurfaces[redrawer]!!.swapBuffers()
                    OpenGLApi.instance.glFlush()
                }

                if (vsyncRedrawers.size == 1) {
                    // 单窗口是绝大多数时刻的实际形态，无需跨线程
                    val redrawer = vsyncRedrawers[0]
                    drawingSurfaces[redrawer]!!.makeCurrent(redrawer.context)
                    drawingSurfaces[redrawer]!!.setSwapInterval(1)
                    drawingSurfaces[redrawer]!!.swapBuffers()
                    OpenGLApi.instance.glFlush()
                } else if (vsyncRedrawers.isNotEmpty()) {
                    // GLX 上下文同一时间只能绑定一个线程：先解除 EDT 上的绑定，
                    // 工作线程交换完成后各自解除，供下一批绘制在 EDT 上重新绑定
                    drawingSurfaces.values.forEach { it.releaseCurrent() }

                    val swapTasks = vsyncRedrawers.map { redrawer ->
                        Callable {
                            val surface = drawingSurfaces[redrawer]!!
                            surface.makeCurrent(redrawer.context)
                            surface.setSwapInterval(1)
                            surface.swapBuffers()
                            OpenGLApi.instance.glFlush()
                            surface.releaseCurrent()
                        }
                    }
                    swapExecutor.invokeAll(swapTasks).forEach { it.get() }
                }
            } finally {
                drawingSurfaces.values.forEach(::unlockLinuxDrawingSurface)
            }

            // Without clearing we will have a memory leak
            toRedrawCopy.clear()
        }
    }
}

private fun LinuxDrawingSurface.createContext(transparency: Boolean) =
    createContext(display, window, transparency)

private fun LinuxDrawingSurface.destroyContext(context: Long) = destroyContext(display, context)
private fun LinuxDrawingSurface.makeCurrent(context: Long) = makeCurrent(display, window, context)
private fun LinuxDrawingSurface.releaseCurrent() = releaseCurrent(display)
private fun LinuxDrawingSurface.swapBuffers() = swapBuffers(display, window)
private fun LinuxDrawingSurface.setSwapInterval(interval: Int) =
    setSwapInterval(display, window, interval)

private external fun makeCurrent(display: Long, window: Long, context: Long)
private external fun releaseCurrent(display: Long)
private external fun createContext(display: Long, window: Long, transparency: Boolean): Long
private external fun destroyContext(display: Long, context: Long)
private external fun setSwapInterval(display: Long, window: Long, interval: Int)
private external fun swapBuffers(display: Long, window: Long)