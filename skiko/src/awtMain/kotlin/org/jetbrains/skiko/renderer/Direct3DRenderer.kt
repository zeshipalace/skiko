package org.jetbrains.skiko.renderer

import kotlinx.coroutines.withContext
import org.jetbrains.skia.*
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skiko.*
import java.awt.Container
import java.awt.Dimension
import java.awt.Window
import java.lang.ref.Reference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

internal class Direct3DRenderer(
    layer: SkiaLayer,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties
) : AwtRenderer(layer, analytics, GraphicsApi.DIRECT3D) {

    private var drawLock = Any()
    private var isSwapChainInitialized = false
    private val synchronousLiveResizeRequested =
        layer.fillsWindow && SkikoProperties.direct3DSynchronousLiveResize

    // Native LiveResizeState, 0 if the hook isn't installed.
    private var liveResizeHandle: Long = 0L
    private val liveResizeInstalled: Boolean
        get() = liveResizeHandle != 0L

    private var device: Long = 0L
        get() {
            if (field == 0L) {
                throw RenderException("DirectX12 device is not initialized or already disposed")
            }
            return field
        }

    val adapterName: String
    val adapterMemorySize: Long

    init {
        val adapter = chooseAdapter(properties.adapterPriority.ordinal)
        if (adapter == 0L) {
            throw RenderException("Failed to choose DirectX12 adapter.")
        }
        adapterName = getAdapterName(adapter)
        adapterMemorySize = getAdapterMemorySize(adapter)
        onDeviceChosen(adapterName)
        // A DirectComposition target bound to the AWT child HWND is clipped by that child's asynchronously updated
        // visible region. During a fast grow this can expose the top-level Acrylic background at the right/bottom
        // edge even though the swap-chain buffer already has the new size. A fill-window synchronous layer can bind
        // its visual directly to the top-level HWND and avoid that independent child clipping boundary.
        val compositionTargetHandle = if (layer.transparency && synchronousLiveResizeRequested) {
            layer.windowHandle
        } else {
            layer.contentHandle
        }
        device = createDirectXDevice(
            adapter,
            layer.contentHandle,
            compositionTargetHandle,
            layer.transparency
        )
            .takeIf { it != 0L } ?: throw RenderException("Failed to create DirectX12 device.")

        if (synchronousLiveResizeRequested) {
            liveResizeHandle = installLiveResizeHook(
                layer.windowHandle,
                layer.contentHandle,
                SkikoProperties.direct3DSynchronousLiveMove
            )
        }
    }

    override val renderInfo: String
        get() = renderInfoHeader(layer.renderApi) +
                "Video card: $adapterName\n" +
                "Total VRAM: ${adapterMemorySize / 1024 / 1024} MB\n"

    override val presentsOnResize: Boolean get() = true

    private var context: DirectContext? = null
    private val bufferCount = 2
    private val surfaces: Array<Surface?> = arrayOfNulls(bufferCount)
    private var surface: Surface? = null
    private var canvas: Canvas? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var hasPreparedLiveResizeFrame = false
    private val liveResizeGeneration = AtomicInteger()
    private fun isSurfacesNull() = surfaces.all { it == null }

    init {
        onContextInit()
    }

    override fun releaseResources() = synchronized(drawLock) {
        if (liveResizeInstalled) {
            uninstallLiveResizeHook(liveResizeHandle)
            liveResizeHandle = 0L
        }
        disposeSurfaces()
        context?.close()
        context = null
        disposeDevice(device)
        device = 0L
    }

    // An async EDT present would race the synchronous render on the toolkit thread.
    override fun requestPlatformDrivenFrame() = postLiveResizeRender(liveResizeHandle)

    override suspend fun LayerDrawScope.renderFrame(immediate: Boolean) {
        if (immediate) {
            drawAndSwap(withVsync = SkikoProperties.windowsWaitForVsyncOnRedrawImmediately)
        } else {
            withContext(dispatcherToBlockOn) {
                drawAndSwap(withVsync = properties.isVsyncEnabled)
            }
        }
    }

    private fun LayerDrawScope.drawAndSwap(withVsync: Boolean, waitForComposition: Boolean = false) {
        synchronized(drawLock) {
            if (isDisposed) {
                return
            }
            // A final resize proposal can be prepared immediately before WM_EXITSIZEMOVE. It must be submitted
            // before a regular EDT frame asks DXGI for the next back buffer, otherwise that call waits for a fence
            // which is signalled only by the skipped Present.
            presentPreparedLiveResizeFrameLocked()
            // A frame-latency waitable swap chain must be waited before rendering every frame, including its first
            // frame. Waiting after Present can consume the initially-signalled state without pacing that Present,
            // which lets the window geometry briefly outrun the first live-resize frame.
            if (liveResizeInstalled && isSwapChainInitialized) {
                waitForNextFrame(device)
            }
            drawFrame()
            swap(withVsync)
            if (waitForComposition) {
                waitForComposition(device)
            }
        }
    }

    private fun LayerDrawScope.drawFrame() {
        if (!ensureContext()) {
            throw RenderException("Cannot init graphic Direct3D context")
        }
        initSurface()
        canvas?.runRestoringState {
            clear(Color.TRANSPARENT)
            layer.draw(this)
        }
        flushFrame()
    }

    private fun ensureContext(): Boolean {
        if (context == null) {
            try {
                val newContext = DirectContext(makeDirectXContext(device))
                context = newContext
                onContextInitialized(newContext, layer.properties.gpuResourceCacheLimit) { renderInfo }
            } catch (e: Exception) {
                Logger.warn(e) { "Failed to create Skia Direct3D context!" }
                return false
            }
        }
        return true
    }

    private fun LayerDrawScope.initSurface() {
        val context = context ?: return

        // Direct3D can't work with zero size.
        // Don't rewrite code to skipping, as we need the whole pipeline in zero case too
        // (drawing -> flushing -> swapping -> waiting for vsync)
        val width = scaledLayerWidth.coerceAtLeast(1)
        val height = scaledLayerHeight.coerceAtLeast(1)

        if (isSizeChanged(width, height) || isSurfacesNull()) {
            disposeSurfaces()
            context.flush()

            val justInitialized = changeSize(width, height)
            try {
                val surfaceProps = SurfaceProps(pixelGeometry = pixelGeometry)
                for (bufferIndex in 0 until bufferCount) {
                    surfaces[bufferIndex] = makeSurface(
                        context = getPtr(context),
                        width = width,
                        height = height,
                        surfaceProps = surfaceProps,
                        index = bufferIndex
                    )
                }
            } finally {
                Reference.reachabilityFence(context)
            }

            if (justInitialized) {
                initFence(device)
            }
        }
        surface = surfaces[getBufferIndex(device)]
        canvas = surface!!.canvas
    }

    private fun isSizeChanged(width: Int, height: Int): Boolean {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            return true
        }
        return false
    }

    private fun flushFrame() {
        val context = context ?: return
        val surface = surface ?: return
        try {
            flush(getPtr(context), getPtr(surface))
        } finally {
            Reference.reachabilityFence(context)
            Reference.reachabilityFence(surface)
        }
    }

    private fun disposeSurfaces() {
        for (bufferIndex in 0 until bufferCount) {
            surfaces[bufferIndex]?.close()
            surfaces[bufferIndex] = null
        }
        surface = null
        canvas = null
    }

    private fun makeSurface(context: Long, width: Int, height: Int, surfaceProps: SurfaceProps, index: Int): Surface {
        return interopScope {
            Surface(makeDirectXSurface(device, context, width, height, toInterop(surfaceProps.packToIntArray()), index))
        }
    }

    private fun changeSize(width: Int, height: Int): Boolean {
        return if (!isSwapChainInitialized) {
            initSwapChain(
                device = device,
                width = width,
                height = height,
                transparency = layer.transparency,
                synchronousLiveResize = liveResizeInstalled
            )
            isSwapChainInitialized = true
            true
        } else {
            resizeBuffers(device, width, height)
            false
        }
    }

    private fun swap(withVsync: Boolean) {
        if (!isSwapChainInitialized) {
            return
        }
        swap(device, withVsync)
    }

    // Called from native code
    @Suppress("unused")
    private fun isAdapterSupported(name: String) = isVideoCardSupported(GraphicsApi.DIRECT3D, hostOs, name)

    /**
     * Called from native code when a live-resize session starts.
     */
    @Suppress("unused")
    private fun onLiveResizeStarted() {
        liveResizeGeneration.incrementAndGet()
        liveResizeListener?.onLiveResizeStarted()
    }

    /**
     * Called from native code when the live-resize session ends.
     */
    @Suppress("unused")
    private fun onLiveResizeEnded(deferUntilWindowMessageReturns: Boolean) {
        val generation = liveResizeGeneration.get()
        val finishLiveResize = finish@{
            if (isDisposed) return@finish
            if (liveResizeGeneration.get() == generation) {
                javax.swing.SwingUtilities.getWindowAncestor(layer)?.let {
                    it.invalidate()
                    it.validate()
                }
                liveResizeListener?.onLiveResizeEnded()
            }
        }
        if (deferUntilWindowMessageReturns) {
            javax.swing.SwingUtilities.invokeLater(finishLiveResize)
        } else {
            WinApiEdtInvoker.invokeAndWaitWhilePumping(finishLiveResize)
        }
    }

    /**
     * Called from native code to draw a frame during live resize.
     *
     * [isResizeFrame] specifies whether this frame actually resizes the window (there could be non-resizing
     * frames during a live resize).
     */
    @Suppress("unused")
    private fun drawFrameWhileLiveResizing(width: Int, height: Int, isResizeFrame: Boolean) {
        WinApiEdtInvoker.invokeAndWaitWhilePumping {
            if (isDisposed) return@invokeAndWaitWhilePumping
            if (isResizeFrame) {
                layoutLayerHierarchyForLiveResize(width, height)
            }
            liveResizeListener?.onLiveResizeFrame(width, height, isResizeFrame)
        }
    }

    private fun layoutLayerHierarchyForLiveResize(width: Int, height: Int) {
        // AWT updates java.awt.Window's public size only after WM_NCCALCSIZE returns, so Window.validate() here would
        // still lay everything out at the preceding resize step. The native callback already has the pending client
        // size. Convert it to AWT logical coordinates, apply it to the fill-window hierarchy below Window, and lay it
        // out from the root down. ComposeScene then observes the current size when SkiaLayer.doLayout() notifies it.
        val scale = layer.contentScale.coerceAtLeast(1f)
        val pendingSize = Dimension(
            (width / scale).roundToInt(),
            (height / scale).roundToInt()
        )
        val hierarchy = generateSequence<java.awt.Component>(layer) { it.parent }
            .takeWhile { it !is Window }
            .toList()
        hierarchy.asReversed().forEach { component ->
            component.size = pendingSize
            if (component is Container) {
                component.doLayout()
            }
        }
    }

    override fun LayerDrawScope.renderPlatformDrivenFrame(isResizeFrame: Boolean) {
        if (isDisposed) return // may be disposed in user code, during `update`
        if (isResizeFrame) {
            synchronized(drawLock) {
                if (isDisposed) return
                // Snap layouts can replace an in-flight resize proposal before WM_WINDOWPOSCHANGED gets a chance
                // to present it. ResizeBuffers must not wait for that abandoned frame's Present-side fence while
                // the toolkit thread is waiting for this EDT render and then re-enters the present callback.
                // Complete the abandoned GPU work without presenting it, then let the replacement frame own the
                // pending-present slot.
                val replacedPreparedFrame = hasPreparedLiveResizeFrame
                if (replacedPreparedFrame) {
                    discardPreparedFrame(device)
                    hasPreparedLiveResizeFrame = false
                }
                // Prepare the exact-size buffer while Win32 still exposes the preceding geometry. Native code calls
                // presentPreparedLiveResizeFrame only from WM_WINDOWPOSCHANGED, after these pixels and the HWND bounds
                // describe the same resize step.
                if (liveResizeInstalled && isSwapChainInitialized && !replacedPreparedFrame) {
                    waitForNextFrame(device)
                }
                drawFrame()
                hasPreparedLiveResizeFrame = true
            }
        } else {
            drawAndSwap(withVsync = true)
        }
    }

    /** Called from native WM_WINDOWPOSCHANGED after the prepared frame's geometry has committed. */
    @Suppress("unused")
    private fun presentPreparedLiveResizeFrame() {
        synchronized(drawLock) {
            if (isDisposed) return
            presentPreparedLiveResizeFrameLocked()
        }
    }

    private fun presentPreparedLiveResizeFrameLocked() {
        if (!hasPreparedLiveResizeFrame) return
        swap(withVsync = false)
        waitForComposition(device)
        hasPreparedLiveResizeFrame = false
    }

    /** Releases a superseded prepared frame without displaying pixels for its stale geometry. */
    @Suppress("unused")
    private fun discardPreparedLiveResizeFrame() {
        synchronized(drawLock) {
            if (isDisposed || !hasPreparedLiveResizeFrame) return
            discardPreparedFrame(device)
            hasPreparedLiveResizeFrame = false
        }
    }

    private external fun chooseAdapter(adapterPriority: Int): Long
    private external fun createDirectXDevice(
        adapter: Long,
        contentHandle: Long,
        compositionTargetHandle: Long,
        transparency: Boolean
    ): Long
    private external fun makeDirectXContext(device: Long): Long
    private external fun makeDirectXSurface(device: Long, context: Long, width: Int, height: Int, surfacePropsIntArray: InteropPointer, index: Int): Long
    private external fun resizeBuffers(device: Long, width: Int, height: Int)
    private external fun swap(device: Long, isVsyncEnabled: Boolean)
    private external fun disposeDevice(device: Long)
    private external fun getBufferIndex(device: Long): Int
    private external fun initSwapChain(device: Long, width: Int, height: Int, transparency: Boolean, synchronousLiveResize: Boolean)
    private external fun initFence(device: Long)
    private external fun getAdapterName(adapter: Long): String
    private external fun getAdapterMemorySize(adapter: Long): Long

    private external fun installLiveResizeHook(
        window: Long,
        content: Long,
        engageOnMove: Boolean
    ): Long
    private external fun uninstallLiveResizeHook(handle: Long)
    private external fun postLiveResizeRender(handle: Long)
    private external fun waitForNextFrame(device: Long)
    private external fun waitForComposition(device: Long)
    private external fun discardPreparedFrame(device: Long)

    private external fun flush(context: Long, surface: Long)
}
