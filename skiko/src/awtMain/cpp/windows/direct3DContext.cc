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
    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_renderer_Direct3DRenderer_flush(
        JNIEnv *env, jobject renderer, jlong contextPtr, jlong surfacePtr)
    {
        __try
        {
            SkSurface *surface = fromJavaPointer<SkSurface *>(surfacePtr);
            GrDirectContext *context = fromJavaPointer<GrDirectContext *>(contextPtr);
            context->flush(surface, SkSurfaces::BackendSurfaceAccess::kPresent, GrFlushInfo());
            context->submit(GrSyncCpu::kYes);
        }
        __except(EXCEPTION_EXECUTE_HANDLER) {
            auto code = GetExceptionCode();
            throwJavaRenderExceptionByExceptionCode(env, __FUNCTION__, code);
        }
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_renderer_Direct3DRenderer_performResourceCacheCleanup(
        JNIEnv *env, jobject renderer, jlong contextPtr, jlong maxUnusedMillis)
    {
        __try
        {
            GrDirectContext *context = fromJavaPointer<GrDirectContext *>(contextPtr);
            size_t beforeBytes = 0;
            size_t afterBytes = 0;
            context->getResourceCacheUsage(nullptr, &beforeBytes);
            context->performDeferredCleanup(std::chrono::milliseconds(maxUnusedMillis));
            context->getResourceCacheUsage(nullptr, &afterBytes);
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
