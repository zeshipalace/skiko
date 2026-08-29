#include <jawt_md.h>
#include <GL/gl.h>
#include <GL/glx.h>
#include <X11/X.h>
#include <X11/Xlib.h>
#include <X11/Xresource.h>
#include <cstdlib>
#include <unistd.h>
#include <stdio.h>
#include "jni_helpers.h"

typedef GLXContext (*glXCreateContextAttribsARBProc)(Display *, GLXFBConfig, GLXContext, Bool, const int *);

// Creates a GLX context bound to the window's actual visual.
//
// AWT picks a 32-bit ARGB visual for transparent windows, but a GLX context
// created from a self-chosen (typically 24-bit) visual is still accepted by
// glXMakeCurrent while rendering into the drawable with the wrong channel
// layout. Compositors (KWin Wayland over XWayland) then read a zeroed alpha
// channel and the window shows up fully transparent.
static GLXContext createContextForWindowVisual(Display *display, Window window)
{
    XWindowAttributes attrs;
    if (!XGetWindowAttributes(display, window, &attrs) || !attrs.visual || !attrs.screen)
    {
        return NULL;
    }

    VisualID visualId = XVisualIDFromVisual(attrs.visual);
    int screen = XScreenNumberOfScreen(attrs.screen);

    int numConfigs = 0;
    GLXFBConfig *fbConfigs = glXGetFBConfigs(display, screen, &numConfigs);
    if (!fbConfigs)
    {
        return NULL;
    }

    GLXFBConfig matched = NULL;
    for (int i = 0; i < numConfigs; i++)
    {
        int configVisualId = 0;
        if (glXGetFBConfigAttrib(display, fbConfigs[i], GLX_VISUAL_ID, &configVisualId) != Success ||
            (VisualID)configVisualId != visualId)
        {
            continue;
        }
        int doubleBuffered = False;
        glXGetFBConfigAttrib(display, fbConfigs[i], GLX_DOUBLEBUFFER, &doubleBuffered);
        matched = fbConfigs[i];
        if (doubleBuffered)
        {
            break;
        }
    }

    GLXContext context = NULL;
    if (matched)
    {
        context = glXCreateNewContext(display, matched, GLX_RGBA_TYPE, NULL, GL_TRUE);
    }
    XFree(fbConfigs);
    return context;
}

extern "C"
{
    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_LinuxOpenGLRedrawerKt_setSwapInterval(JNIEnv *env, jobject redrawer, jlong displayPtr, jlong windowPtr, jint interval)
    {
        Display *display = fromJavaPointer<Display *>(displayPtr);
        Window window = fromJavaPointer<Window>(windowPtr);

        // according to:
        // https://opengl.gpuinfo.org/listreports.php?extension=GLX_EXT_swap_control
        // https://opengl.gpuinfo.org/listreports.php?extension=GLX_MESA_swap_control
        // https://opengl.gpuinfo.org/listreports.php?extension=GLX_SGI_swap_control
        // there is no Linux that doesn't support at least one of these extensions
        static PFNGLXSWAPINTERVALEXTPROC glXSwapIntervalEXT = (PFNGLXSWAPINTERVALEXTPROC) glXGetProcAddress((const GLubyte*)"glXSwapIntervalEXT");
        if (glXSwapIntervalEXT != NULL)
        {
            glXSwapIntervalEXT(display, window, interval);
        }
        else
        {
            static PFNGLXSWAPINTERVALMESAPROC glXSwapIntervalMESA = (PFNGLXSWAPINTERVALMESAPROC) glXGetProcAddress((const GLubyte*)"glXSwapIntervalMESA");
            if (glXSwapIntervalMESA != NULL)
            {
                glXSwapIntervalMESA(interval);
            }
            else
            {
                static PFNGLXSWAPINTERVALSGIPROC glXSwapIntervalSGI = (PFNGLXSWAPINTERVALSGIPROC) glXGetProcAddress((const GLubyte*)"glXSwapIntervalSGI");
                if (glXSwapIntervalSGI != NULL)
                {
                    glXSwapIntervalSGI(interval);
                }
            }
        }
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_LinuxOpenGLRedrawerKt_swapBuffers(JNIEnv *env, jobject redrawer, jlong displayPtr, jlong windowPtr)
    {
        Display *display = fromJavaPointer<Display *>(displayPtr);
        Window window = fromJavaPointer<Window>(windowPtr);

        glXSwapBuffers(display, window);
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_LinuxOpenGLRedrawerKt_makeCurrent(JNIEnv *env, jobject redrawer, jlong displayPtr, jlong windowPtr, jlong contextPtr)
    {
        Display *display = fromJavaPointer<Display *>(displayPtr);
        Window window = fromJavaPointer<Window>(windowPtr);
        GLXContext *context = fromJavaPointer<GLXContext *>(contextPtr);

        glXMakeCurrent(display, window, *context);
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_redrawer_LinuxOpenGLRedrawerKt_createContext(JNIEnv *env, jobject redrawer, jlong displayPtr, jlong windowPtr, jboolean transparency)
    {
        Display *display = fromJavaPointer<Display *>(displayPtr);
        Window window = fromJavaPointer<Window>(windowPtr);
        if (!display) return 0;

        if (window)
        {
            GLXContext visualContext = createContextForWindowVisual(display, window);
            if (visualContext)
            {
                return toJavaPointer(new GLXContext(visualContext));
            }
        }

        // Fallback: choose a visual ourselves (legacy path).
        XVisualInfo *vi;

        if (transparency)
        {
            GLint att[] = {
                GLX_RGBA,
                GLX_RED_SIZE, 8,
                GLX_GREEN_SIZE, 8,
                GLX_BLUE_SIZE, 8,
                GLX_ALPHA_SIZE, 8,
                GLX_DOUBLEBUFFER, True, None
            };
            vi = glXChooseVisual(display, 0, att);
        }
        else {
            GLint att[] = {GLX_RGBA, GLX_DOUBLEBUFFER, True, None};
            vi = glXChooseVisual(display, 0, att);
        }

        if (!vi) return 0;

        GLXContext context = glXCreateContext(display, vi, NULL, GL_TRUE);
        XFree(vi);
        if (!context) return 0;

        return toJavaPointer(new GLXContext(context));
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_LinuxOpenGLRedrawerKt_destroyContext(JNIEnv *env, jobject redrawer, jlong displayPtr, jlong contextPtr)
    {
        Display *display = fromJavaPointer<Display *>(displayPtr);
        GLXContext *context = fromJavaPointer<GLXContext *>(contextPtr);

        if (display && context) {
            glXDestroyContext(display, *context);
            delete context;
	    }
    }
}
