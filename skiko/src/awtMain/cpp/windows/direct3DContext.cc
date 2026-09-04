#ifdef SK_DIRECT3D
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
    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_renderer_Direct3DRenderer_flush(
        JNIEnv *env,
        jobject renderer,
        jlong contextPtr,
        jlong surfacePtr,
        jlong purgeableResourceCacheLimit)
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

            // submit(kYes) establishes the same lifetime boundary used by explicit GPU APIs: resources still
            // referenced by the current frame or an in-flight command buffer cannot be purgeable. Skia keeps the
            // rest in an LRU queue. Bound that reusable pool at the submission boundary instead of polling it.
            const size_t limit = static_cast<size_t>(purgeableResourceCacheLimit);
            const size_t beforeBytes = context->getResourceCachePurgeableBytes();
            if (beforeBytes <= limit) {
                return 0;
            }

            // Obsolete bitmap generations lose their unique keys and become scratch resources. Reclaim those
            // first, then fall back to the oldest persistent uploads only when scratch memory is insufficient.
            // Frequently reused image textures continually move to the MRU end and remain resident.
            context->purgeUnlockedResources(beforeBytes - limit, true);
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
