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
  - Windows Direct3D 模态移动帧驱动(voxzen.17):标题栏拖动(纯移动,客户区尺寸不变)此前不会触发 `renderPendingResizeFrame`,整场拖动停留在 EDT 帧调度路径;实测模态循环中 `WM_PAINT` 只在输入队列排空时合成,真实拖动(含手部微颤的"按住不动")输入消息以 ~175Hz 持续到达,WM_PAINT 永远轮不到,动画帧完全停摆(实测 3 段拖动 0 帧)。改为在 `WM_ENTERSIZEMOVE` 即挂接平台驱动帧(`javaOnLiveResizeStarted` 暂停 EDT scheduler),帧由两条路径驱动:输入空闲时由合并后的 `WM_PAINT` 驱动(覆盖静止按住与缩放悬停),位置提交后由 `WM_WINDOWPOSCHANGED`(SWP_NOSIZE)按 8ms 节流驱动(覆盖移动中,`isResizeFrame=false` 走 `drawAndSwap`,Present(1) 挂到下一次 vsync);节流计时用 `QueryPerformanceCounter`(`GetTickCount64` 在未提升计时器分辨率时量化到 15.6ms,会把帧率钉在 ~60 以下);两条路径都不会让渲染抢占输入消息。`FrameDriver.onLiveResizeStarted` 暂停 scheduler 后主动补一帧平台驱动帧:恰好在暂停前派发的调度帧会被丢弃且不渲染,其携带的 invalidation 不会再触发 `needRender`,动画链会整场拖动停摆(按下不动即卡死的根因)。新增 `skiko.rendering.windows.direct3DSynchronousLiveMove`(默认 true)可回退到旧的惰性挂接,`skiko.rendering.windows.direct3DLiveResizeDebug`(默认 false)打印每场拖动的帧计数与耗时;`applyEnforcedChildSize` 按子 HWND 实际尺寸去重,避免每个 WM_PAINT 帧重复 `SetWindowPos`
  - Windows Snap 候选帧替换(voxzen.18):标题栏拖到屏幕顶部进入 Windows 快速布局时,系统会在前一张候选尺寸帧尚未进入 `WM_WINDOWPOSCHANGED` Present 前发出新的尺寸提案;第二张帧的 `ResizeBuffers` 因而等待只能由旧帧 Present 发出的 fence,而重入的窗口线程又等待 EDT 持有的 `drawLock`,形成永久互等。现在替换候选帧前直接在 D3D12 队列上为已提交的旧 GPU 工作发出 fence Signal,不 Present 过期尺寸的像素,再由最终候选帧占用待提交槽;保留 `direct3DSynchronousLiveMove=true`、Acrylic DirectComposition 路径与边缘同步缩放
  - Windows Snap 动画无阻塞(voxzen.19):最大化/还原的 `WINDOWPOS` 带 `SWP_STATECHANGED`,这类几何由 DWM 自己做过渡动画;若仍在 `WM_WINDOWPOSCHANGING` 中同步执行 Compose 布局、`ResizeBuffers`、Present 和 `DwmFlush`,Snap 预览会先退回原窗口并停顿,等窗口过程返回才开始最大化。现在状态切换只让系统立即提交几何并沿用旧 surface 作为 DWM 动画快照,过期候选帧只完成 fence 而不显示;同时覆盖系统在 `WM_EXITSIZEMOVE` 之后才决定最大化的时序:纯标题栏移动的收尾不再同步校验布局和强制绘帧,窗口过程返回后才从 EDT 队列恢复 scheduler、校验最终层级并重绘。真正的边缘拖拽以 `WM_SIZING` 区分,仍走同步预渲染和同步收尾,保留 Acrylic 与无白线/残影效果
  - Windows Direct3D 提交驱动纹理回收(voxzen.21):`skiko.gpu.resourceCacheLimit` 是整个 Ganesh 缓存的硬上限,带 key 的图片上传和可复用 scratch 资源低于上限时会继续常驻。新增 `skiko.gpu.resourceCachePurgeableBytesLimit`;每次 `submit(kYes)` 完成后只做 O(1) 的可回收字节检查,超出软上限时才从 Skia 原生 purgeable 队列增量释放超额部分。仍被当前帧、图片代理或 GPU command buffer 引用的资源不会进入该队列;已失效的动态位图代际会丢失 unique key 并转为 scratch,回收时优先释放这类 scratch,不足时再按原生 LRU 淘汰普通图片纹理,持续命中的高频纹理会留在 MRU 端。该实现没有定时器、后台线程或按时间扫描,其生命周期边界与 Rust/wgpu 在 GPU submission 完成后回收资源的模型一致;设为 `-1` 或不设置时保持上游行为,设为 `0` 时接近立即释放,建议 UI 应用保留 `64M` 复用池。`skiko.gpu.resourceCacheTrim.log=true` 可记录实际释放量。实现仅改变 Direct3D 内部 private JNI,不改变任何既有公开类、构造器或方法签名

  - Windows Direct3D 可配置原生堆块(voxzen.22):通过 Ganesh 既有 `fMemoryAllocator` 扩展点复用与 Skia 完全同版本的 D3D12MA,新增 `skiko.rendering.windows.direct3DHeapBlockSize` 调整底层 suballocation 粒度,减少小纹理长期存活导致的大堆块空闲空间常驻。默认 `0` 保留 Skia 原行为;Voxzen Windows 使用 `8M`。资源仍由引用计数和 GPU 完成边界释放,不引入扫描或定时器,不改变公开 JVM 签名;核心 jar 与原生 runtime 必须成套升级

  - Windows Direct3D 热图层复用保护(voxzen.23):纠正 `.21/.22` 将整个 purgeable 池按字节强压到软上限的策略。全窗口 blur/saveLayer 渲染目标每帧解锁后也进入 scratch 池,并不等于冷资源;16M 配置会导致约 13–15GiB/s 的反复淘汰,让 160Hz Debug 播放页降至约 101FPS。现在记录本帧起点,提交后借助 Ganesh 原生有序 LRU 仅回收本帧之前解锁的冷资源,保护本帧使用过的热目标;原属性成为回收触发阈值,总缓存预算保持独立。没有新增定时任务或全量纹理扫描,仅使用实际帧生命周期映射到 Skia 的公开 cleanup API。此前“MRU 高频纹理一定保留”的描述不适用于字节上限小于热工作集的情况,应以本条与 [帧边界回收说明](skiko/docs/windows-direct3d-frame-cache.md) 为准。大窗口热图层回归从 90 帧淘汰 1602MiB 降为 0,停用图层后回收 30MiB,Debug 最大化/全屏播放约 160FPS;公开 JVM ABI 不变,私有 JNI 仍要求核心 jar 与 native runtime 成套升级

### CMP 兼容性

当前分支已进入 master(0.152.x 线),与 CMP 1.12.0 **二进制不兼容**,实测:

- `org.jetbrains.skia.FontMetrics`、`FontStyle` 等已改为 Kotlin value class,实例方法全部变成名称混淆的静态 `-impl` 方法(如 `getAscent-impl([F)`),CMP 1.12.0 的 ui-text/ui-graphics 编译期绑定旧签名 → 运行时 `NoSuchMethodError`,且无法用 shim 桥接(擦除签名完全不同)
- `RenderNodeContext.<init>(Z)V` 变为 `(ZZ)V`
- master 还包含 AWT 渲染栈重构(#1234、#1273)与 Skia m150→m152 升级

因此 Voxzen 应同时升级到基于 skiko ≥ 0.152 的 CMP 版本,不能把当前产物与 CMP 1.12.0 混用。

## 本地迭代(开发机)

```bash
./gradlew -p skiko :publishToMavenLocal -Pdeploy.release=true
```

发布到本机 `~/.m2`,版本号与 GitHub Packages 相同,**不需要改版本号**。Voxzen 正常发布配置解析 GitHub Packages;Windows 本地验证通过 `-Pvoxzen.skiko.mavenLocal=true` 成套选择本地核心 jar 与 Windows x64 runtime,不会把本地 DLL 与远端核心 jar 混用。Salt UI 传递引入的其它平台 runtime 仍从远端解析。此开关仅供显式本地验证,不能提交依赖本机 MavenLocal 的默认配置。

本机 Windows 工具链位于 `C:\Apps\VSBuildTools2022`,PowerShell 本地构建示例:

```powershell
$env:SKIKO_VSBT_PATH = 'C:\Apps\VSBuildTools2022'
$env:PATH = 'C:\Apps\VSBuildTools2022\VC\Tools\Llvm\x64\bin;' + $env:PATH
.\gradlew.bat -p skiko :publishToMavenLocal '-Pdeploy.release=true'
```

### Direct3D 堆分配配置(voxzen.22)

`skiko.rendering.windows.direct3DHeapBlockSize` 可以设置 D3D12MA 的首选堆块大小,默认 `0` 完整保留 Skia 的默认分配器。非零值只调整底层 suballocation 粒度,不改变 Skia 的资源引用计数、GPU fence 或纹理逐出策略,没有定时扫描。允许 `1M` 至 `256M` 的 2 次幂,例如 `16M`。

仅调整 private JNI,不改变既有公开 Kotlin/JVM 或 Skia API。D3D12MA 头文件固定为当前 Skia DEPS 使用的提交,实现复用预编译 Skia 中的同版本代码。升级 Skia 时必须重新核对该提交,不得混用任意新版本头文件。

Voxzen Windows 配置使用 `8M`,其它调用方仍保持默认 `0`。独立预热、同一窗口位置的纹理 churn 测试覆盖 16M、8M、4M 并通过回收量与帧间隔阈值;本地发布前验证中 8M 与默认值的 p95 分别为 12.57ms 和 12.61ms。首窗口与后续窗口存在显著帧时钟预热差异,不得把未经预热或单次波动当成性能收益。

2026-09-05 本机 Release 候选应用同时使用确定性封面像素所有权、解码背压、NIO 临时缓冲上限和 G1 并发显式回收,可交互首屏私有工作集约 286MiB,列表滚动采样峰值约 476MiB。总工作集和提交量仍超过 500MiB,不能宣布所有场景达标;这些是组合方案结果,不是单独调整堆块的收益。本次发布交付已经验证的优化,不代表内存或零性能损失目标已全面完成。完整口径、限制及复现步骤记录于 Voxzen `docs/windows-memory-budget-2026-09-05.zh-CN.md`。

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
