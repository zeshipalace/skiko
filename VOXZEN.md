# Voxzen fork 维护说明

本仓库是 [JetBrains/skiko](https://github.com/JetBrains/skiko) 的 fork,为 Voxzen 项目维护一份带自有补丁的 skiko。

## 分支与版本

- 维护分支:`voxzen`,已合并上游 `master` 至 `2756625dc`(包含 AWT frame-driving extraction #1273 与 stubs.cc 移除 #1285),使用 Skia `m152-2ca5fe6a81`
- 版本号:`skiko/gradle.properties` 的 `deploy.version`,规则 `0.152.0-voxzen.N`;主版本跟随 Skia milestone,N 为 Voxzen 发布序号
- 补丁栈(在基点之上):
  - cherry-pick 上游 PR [#1282](https://github.com/JetBrains/skiko/pull/1282)(SkiaSwingLayer frame pacing,`skiko.swing.frame.pacing`);`SkikoProperties.kt` 已适配上游 lazy 属性风格
  - Linux GLX 上下文按窗口实际 Visual 创建(voxzen.2):`renderer.cc` 的 `createContext` 新增 window 参数,先 `XGetWindowAttributes` 取窗口 Visual,再在 `glXGetFBConfigs` 中按 `GLX_VISUAL_ID` 匹配 FBConfig 用 `glXCreateNewContext` 建上下文;失败回退原 `glXChooseVisual` 路径。修复 KDE Wayland(XWayland)下 OpenGL 透明窗口全透明(CMP-6639;`glXChooseVisual` 选到 24 位 Visual 而 AWT 透明窗口用 32 位 ARGB,`glXMakeCurrent` 不报错但 alpha 通道全 0)
  - Windows Direct3D 同步缩放兼容透明合成(voxzen.2):透明层继续为 `CreateSwapChainForComposition` 使用其强制要求的 `DXGI_SCALING_STRETCH`,避免 `DXGI_SCALING_NONE` 创建失败后回退到不透明 HWND 交换链,保留 Acrylic/Mica 等 DWM 背景材质
  - Windows Direct3D 自定义客户区同步缩放(voxzen.3):当窗口客户区覆盖整个原生窗口时,在调用下层 AWT 窗口过程前保留 `WM_NCCALCSIZE` 的完整候选尺寸,防止 AWT 按系统标题栏/边框扣减后让实时帧和内容子窗口少画右侧、底部区域
  - Windows Direct3D 低延迟同步缩放(voxzen.10):同步缩放交换链使用 `DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT` 并将最大帧延迟设为 1,防止高频拖拽产生的 `Present` 积压;每个 resize frame 在录制前用 native 候选客户区尺寸除以 DPI,直接更新 Window 以下的 `JRootPane`/`ComposeWindowPanel`/`SkiaLayer` fill-window 层级并自上而下布局,绕过 `WM_NCCALCSIZE` 返回前 `java.awt.Window` 仍是旧尺寸的时序,修复 ComposeScene 落后一个 resize step;`Present` 后以 `DwmFlush` 对齐 DWM 边界,透明 DirectComposition/Acrylic 路径不变
  - Linux 多窗口并行 vsync 交换(voxzen.11):`LinuxGLFrameBatch` 批量交换阶段上游原本只有单窗口 interval=1、其余窗口 interval=0;NVIDIA 闭源驱动 XWayland 下 interval=0 的立即呈现会把仍在显示的缓冲交还应用重绘,透明窗口每帧 `clear(Color.TRANSPARENT)` 直接落在可见缓冲上,整窗随重绘闪透明(指针进出桌面歌词窗口稳定触发)。改为全部 vsync 窗口 interval=1 呈现;多 vsync 窗口时交换分发到守护线程池并行阻塞各自 vblank,新增 `releaseCurrent` 在调度线程与工作线程间移交 GLX 上下文(上下文同一时间只能绑定一个线程),任意刷新率下各窗口均可跑满各自 vblank(300Hz 实测双窗口各 ~296fps 且无闪烁)
  - macOS Metal 高频缩放帧合并(voxzen.12):实时缩放中的 bounds 帧已经会推进 Compose 动画,不再让动画请求与高频 bounds 变化交替执行完整的同步 EDT 渲染;动画帧等待 bounds 空闲 50ms 后恢复,连续拖动时优先处理最新窗口尺寸,避免拖动越久输入越滞后
  - Linux 顶层 redrawer 兼容契约(voxzen.13):mediamp(`SkiaOpenGLInterop`)与 Voxzen GPU 缓存控制按 0.9.37.4 布局反射 `SkiaLayer.getRedrawer$skiko`、`LinuxOpenGLRedrawer` 类名、`contextHandler`/`context` 字段及 `LinuxOpenGLRedrawerKt.access$makeCurrent`。同步上游 #1273 后,保留旧包名的 `LinuxOpenGLRedrawer` 作为新 `LinuxOpenGLRenderer` 的轻量子类,`SkiaLayer` 兼容 getter 返回当前 renderer,`ContextHandler` shim 继续同步 `DirectContext`,旧 `access$makeCurrent` 委托到新 renderer 的 GLX 绑定实现;实际帧循环完整使用上游 `FrameDriver`/`FrameProducer`/`FrameScheduler` 架构
  - 上游 AWT frame-driving extraction 同步(voxzen.14):合并 `2756625dc`,采用 #1273 的 `Renderer`/`FrameDriver` 架构并删除 #1285 已废弃的 `stubs.cc`;将 Voxzen 的 Linux GLX Visual、多窗口并行 vsync、Windows 同步缩放、macOS 高频缩放帧合并、Swing frame pacing 与 mediamp 兼容补丁迁移到新结构
  - Linux mediamp GLX 字段兼容(voxzen.15):在兼容 `LinuxOpenGLRedrawer` 上直接声明并初始化 native `context` 字段,满足 mediamp 0.3.0 使用 `getDeclaredField("context")` 的精确反射契约;父类 `LinuxOpenGLRenderer` 仍持有实际上下文并负责完整生命周期
  - Windows Direct3D 快速缩放无残影(voxzen.16):对客户区填满窗口的自定义装饰窗口,在 `WM_WINDOWPOSCHANGING` 阶段完成布局、`ResizeBuffers` 与新尺寸帧录制,等 `WM_WINDOWPOSCHANGED` 确认 HWND 几何提交后再 `Present`,随后等待 GPU fence 并用 `DwmFlush` 将对应内容帧与 DWM 窗口边界对齐;若缩放结束或常规帧接管,会先提交已准备的帧,避免交换链停在不可推进状态。透明且 fill-window 的同步缩放路径把 DirectComposition target 直接绑定到顶层窗口,避免 AWT 内容子 HWND 的独立裁切边界在快速放大时露出 Acrylic 背景;实时缩放期间对子 HWND 使用 `SWP_NOCOPYBITS | SWP_NOREDRAW`,并从 `WM_NCCALCSIZE` 返回 `WVR_REDRAW`,禁止 Win32/AWT 复制旧客户区像素或插入中间背景擦除。同步交换链同时启用 frame-latency waitable object 并将最大帧延迟设为 1,消除快速放大边缘白线与缩放残影

### CMP 兼容性

当前分支已进入 master(0.152.x 线),与 CMP 1.12.0 **二进制不兼容**,实测:

- `org.jetbrains.skia.FontMetrics`、`FontStyle` 等已改为 Kotlin value class,实例方法全部变成名称混淆的静态 `-impl` 方法(如 `getAscent-impl([F)`),CMP 1.12.0 的 ui-text/ui-graphics 编译期绑定旧签名 → 运行时 `NoSuchMethodError`,且无法用 shim 桥接(擦除签名完全不同)
- `RenderNodeContext.<init>(Z)V` 变为 `(ZZ)V`
- master 还包含 AWT 渲染栈重构(#1234、#1273)与 Skia m150→m152 升级

因此 Voxzen 应同时升级到基于 skiko ≥ 0.152 的 CMP 版本,不能把当前产物与 CMP 1.12.0 混用。

## 本地迭代(开发机)

```bash
./gradlew -p skiko publishToMavenLocal -Pdeploy.release=true
```

发布到本机 `~/.m2`,版本号与 GitHub Packages 相同;Voxzen 的仓库配置中 mavenLocal 在前,本地构建自动遮蔽远端,改代码后重跑即可,**不需要改版本号**。

注意:不要省略 `-Pdeploy.release=true`(省略会追加 `-SNAPSHOT` 后缀,与 Voxzen 锁定的版本号对不上)。

## 发布到 GitHub Packages(共享 / CI 全平台)

1. `skiko/gradle.properties` 中 `deploy.version` 尾号 +1(GPR 不允许覆盖同版本)
2. 本机构建验证通过后提交并推送 `voxzen` 分支
3. 在 GitHub Actions 手动运行 `Voxzen Publish` workflow(`.github/workflows/publish-voxzen.yml`),一次运行发布全部 6 个桌面 AWT 平台:
   - `publish-linux`:docker `linux-compat` 镜像内发布全部公共产物(skiko、skiko-awt、skiko-awt-runtime 等)+ linux-x64 runtime(旧 glibc 基线,可对外分发)
   - `publish-linux-arm64`:`ubuntu-24.04-arm` runner + `linux-compat` 镜像(**ARM runner 只对公开仓库免费**)
   - `publish-windows`:windows-2022,x64 本机编译 + arm64 MSVC 交叉编译
   - `publish-macos`:macos-15(arm64 本机 + x64 工具链交叉编译)
   - 非 linux 作业只发平台独有 runtime,避免重复推送公共产物(GPR 拒收重复版本,会失败)
   - 首次运行会先在 CI 本地构建 docker 镜像(ghcr 无 `linux-compat:voxzen` tag 时自动 build,较耗时);可提前跑 `Docker Publish` workflow 预热镜像
4. Voxzen 仓库 `gradle.properties` 更新 `voxzen.skiko.version` 为新版本号

也可以只发 Linux 侧(本机或 CI):
```bash
./gradlew -p skiko publishToVoxzenGitHubPackages -Pdeploy.release=true
```
凭据:gradle property `gpr.user`/`gpr.key`,或环境变量 `GITHUB_ACTOR`/`GITHUB_TOKEN`

## 上游同步(CMP 升级时)

1. 确认新版 CMP 锁定的 skiko 版本(查 `org.jetbrains.compose.desktop:desktop-jvm-<os>` 的 POM)
2. `git fetch upstream`,`git merge upstream/master`,解决冲突并把 Voxzen 补丁迁移到上游的新结构
3. `deploy.version` 改为对应的新版本系(如 `0.153.0-voxzen.1`)
4. 重新走一遍「本地验证 → 发布」流程
5. **注意**:CMP 若跨到 skiko ≥ 0.151,先确认新 CMP 是否已适配 value class 化的 `FontMetrics`/`FontStyle`(CMP 官方发布会自带适配,无需担心;只有自组「新 skiko + 旧 CMP」才会踩坑)

## 已知限制

- **本机编译的 linux runtime 链接本机 glibc**(Arch,较新):本地开发无问题;对外分发的 linux 产物由 `Voxzen Publish` workflow 在 docker `linux-compat` 镜像内构建(旧 glibc 基线),不要用本机 `publishToVoxzenGitHubPackages` 发布对外版本
- `skiko-awt-runtime-all`(全平台 uber jar)在各作业独立发布时只含本机平台;Voxzen 不使用该产物(CMP 按平台引用 `skiko-awt-runtime-<os>-<arch>`),无需处理
- Android / Web(wasm)产物未发布;Voxzen 只用桌面 AWT 平台,需要时参考 `publish-dry-run.yml` 的 Android/Web 作业扩展
- fork 发布的 `skiko-awt` 模块元数据带有指向全部平台 runtime 的严格版本约束(继承上游设计)。自全平台 CI 发布起 6 个桌面 AWT runtime 均可满足;Voxzen `settings.gradle.kts` 中的"未发布模块排除 + `SkikoAwtUnpublishedRuntimeRule`"防御逻辑是为未全平台发布的旧版本(≤ voxzen.2)准备的,确认 GPR 上当前版本全平台齐全后可移除
