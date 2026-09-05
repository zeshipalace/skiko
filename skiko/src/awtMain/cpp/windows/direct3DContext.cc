#ifdef SK_DIRECT3D
#include <chrono>
#include <locale>
#include <Windows.h>
#include <jawt_md.h>
#include "jni_helpers.h"

#include "ganesh/GrBackendSurface.h"
#include "ganesh/GrDirectContext.h"
#include "SkSurface.h"
#include "exceptions_handler.h"

extern "C"
{
    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_renderer_Direct3DRenderer_resourceCacheFrameStart(
        JNIEnv *, jobject)
    {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_renderer_Direct3DRenderer_flush(
        JNIEnv *env,
        jobject renderer,
        jlong contextPtr,
        jlong surfacePtr,
        jlong purgeableResourceCacheLimit,
        jlong frameStartNanos)
    {
        __try
        {
            SkSurface *surface = fromJavaPointer<SkSurface *>(surfacePtr);
            GrDirectContext *context = fromJavaPointer<GrDirectContext *>(contextPtr);
            context->flush(surface, SkSurfaces::BackendSurfaceAccess::kPresent, GrFlushInfo());
            context->submit(GrSyncCpu::kYes);
            if (purgeableResourceCacheLimit < 0) {
                return 0;
            }

            // Purgeable means safe to destroy, not cold: full-window blur and saveLayer targets are unlocked
            // after every submission. A byte-only trim therefore destroys the next frame's working set.
            const size_t limit = static_cast<size_t>(purgeableResourceCacheLimit);
            const size_t beforeBytes = context->getResourceCachePurgeableBytes();
            if (beforeBytes <= limit) {
                return 0;
            }

            // Use the frame's actual lifetime, not a fixed cleanup interval. Skia updates a resource's LRU age
            // whenever its last reference is dropped. Its ordered queue can discard the cold prefix and stop
            // at this frame's resources without sorting/scanning every scratch texture. The configured value
            // is a cleanup trigger, not a cap on hot resources; the context's normal hard budget still applies.
            const auto frameStart = std::chrono::steady_clock::time_point(std::chrono::nanoseconds(frameStartNanos));
            const auto frameAge = std::chrono::steady_clock::now() - frameStart;
            // The public Ganesh API accepts whole milliseconds. Round up so the cutoff does not enter this frame.
            const auto protectedAge = std::chrono::duration_cast<std::chrono::milliseconds>(frameAge)
                + std::chrono::milliseconds(1);
            context->performDeferredCleanup(protectedAge, GrPurgeResourceOptions::kAllResources);
            const size_t afterBytes = context->getResourceCachePurgeableBytes();
            return beforeBytes > afterBytes
                ? static_cast<jlong>(beforeBytes - afterBytes)
                : 0;
        }
        __except(EXCEPTION_EXECUTE_HANDLER) {
            auto code = GetExceptionCode();
            throwJavaRenderExceptionByExceptionCode(env, __FUNCTION__, code);
            return 0;
        }
    }
}

#endif
