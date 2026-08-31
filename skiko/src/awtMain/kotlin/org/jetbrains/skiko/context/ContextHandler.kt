package org.jetbrains.skiko.context

import org.jetbrains.skia.DirectContext

/**
 * mediamp(SkiaOpenGLInterop)按 Skiko 0.9.37.4 的内部布局反射读取渲染上下文:
 * `LinuxOpenGLRedrawer.contextHandler` 的类型必须是 [OpenGLContextHandler],
 * 且 [ContextHandler] 上声明的 `context` 字段持有当前 [DirectContext]
 * 上游 #1234 渲染栈重构移除了 context 包,这里保留同名结构作为兼容契约
 */
internal abstract class ContextHandler {
    var context: DirectContext? = null
}

internal class OpenGLContextHandler : ContextHandler()
