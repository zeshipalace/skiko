# Voxzen fork 维护说明

本仓库是 [JetBrains/skiko](https://github.com/JetBrains/skiko) 的 fork,为 Voxzen 项目维护一份带自有补丁的 skiko。

## 分支与版本

- 维护分支:`voxzen`,基于上游 tag `v0.150.1`(`3956e988`)——与 Voxzen 使用的 CMP 1.12.0 所编译的 skiko 同源
- 版本号:`skiko/gradle.properties` 的 `deploy.version`,规则 `0.150.1-voxzen.N`
- 补丁栈(在基点之上):
  - cherry-pick 上游 PR [#1282](https://github.com/JetBrains/skiko/pull/1282)(SkiaSwingLayer frame pacing,`skiko.swing.frame.pacing`);`SkikoProperties.kt` 冲突已按本分支的非 lazy 属性风格移植
  - Linux GLX 上下文按窗口实际 Visual 创建(voxzen.2):`redrawer.cc` 的 `createContext` 新增 window 参数,先 `XGetWindowAttributes` 取窗口 Visual,再在 `glXGetFBConfigs` 中按 `GLX_VISUAL_ID` 匹配 FBConfig 用 `glXCreateNewContext` 建上下文;失败回退原 `glXChooseVisual` 路径。修复 KDE Wayland(XWayland)下 OpenGL 透明窗口全透明(CMP-6639;`glXChooseVisual` 选到 24 位 Visual 而 AWT 透明窗口用 32 位 ARGB,`glXMakeCurrent` 不报错但 alpha 通道全 0)

### 为什么不基于 master

master(0.152.x 线)与 CMP 1.12.0 **二进制不兼容**,实测:

- `org.jetbrains.skia.FontMetrics`、`FontStyle` 等已改为 Kotlin value class,实例方法全部变成名称混淆的静态 `-impl` 方法(如 `getAscent-impl([F)`),CMP 1.12.0 的 ui-text/ui-graphics 编译期绑定旧签名 → 运行时 `NoSuchMethodError`,且无法用 shim 桥接(擦除签名完全不同)
- `RenderNodeContext.<init>(Z)V` 变为 `(ZZ)V`
- master 还包含 AWT 渲染栈重构(#1234)与 Skia m150→m152 升级

只有当 Voxzen 升级到基于 skiko ≥ 0.152 的 CMP 版本后,才考虑把 `voxzen` 分支 rebase 到 master 系。master 基点的尝试保留在 `voxzen-master` 分支供参考。

## 本地迭代(开发机)

```bash
./gradlew -p skiko publishToMavenLocal -Pdeploy.release=true
```

发布到本机 `~/.m2`,版本号与 GitHub Packages 相同;Voxzen 的仓库配置中 mavenLocal 在前,本地构建自动遮蔽远端,改代码后重跑即可,**不需要改版本号**。

注意:不要省略 `-Pdeploy.release=true`(省略会追加 `-SNAPSHOT` 后缀,与 Voxzen 锁定的版本号对不上)。

## 发布到 GitHub Packages(共享 / Windows 构建机拉取)

1. `skiko/gradle.properties` 中 `deploy.version` 尾号 +1(GPR 不允许覆盖同版本)
2. 本机构建验证通过后提交并推送 `voxzen` 分支
3. 发布 Linux 侧全部产物(本机或 CI 均可):
   ```bash
   ./gradlew -p skiko publishToVoxzenGitHubPackages -Pdeploy.release=true
   ```
   凭据:gradle property `gpr.user`/`gpr.key`,或环境变量 `GITHUB_ACTOR`/`GITHUB_TOKEN`
4. Windows runtime:在 GitHub Actions 手动运行 `Voxzen Publish` workflow(`.github/workflows/publish-voxzen.yml`),windows 作业只发布 `skiko-awt-runtime-windows-x64`
5. Voxzen 仓库 `gradle.properties` 更新 `voxzen.skiko.version` 为新版本号

## 上游同步(CMP 升级时)

1. 确认新版 CMP 锁定的 skiko 版本(查 `org.jetbrains.compose.desktop:desktop-jvm-<os>` 的 POM)
2. `git fetch` 上游,`git rebase --onto <新基点tag> v0.150.1 voxzen`,解决冲突
3. `deploy.version` 改为对应的新版本系(如 `0.153.0-voxzen.1`)
4. 重新走一遍「本地验证 → 发布」流程
5. **注意**:CMP 若跨到 skiko ≥ 0.151,先确认新 CMP 是否已适配 value class 化的 `FontMetrics`/`FontStyle`(CMP 官方发布会自带适配,无需担心;只有自组「新 skiko + 旧 CMP」才会踩坑)

## 已知限制

- **本机编译的 linux runtime 链接本机 glibc**(Arch,较新):本地开发无问题;若对外分发 Linux 构建,需用仓库的 docker `linux-compat` 镜像构建(参考 `.github/workflows/publish-dry-run.yml`)
- `skiko-awt-runtime-all`(全平台 uber jar)在本机/单作业发布时只含本机平台;Voxzen 不使用该产物(CMP 按平台引用 `skiko-awt-runtime-<os>-<arch>`),无需处理
- 暂未构建 windows-arm64 / linux-arm64 / macOS runtime;需要时扩展 `publish-voxzen.yml` 矩阵
  - 注意:fork 发布的 `skiko-awt` 模块元数据带有指向全部平台 runtime 的严格版本约束(继承上游按全平台发布的设计),未发布平台的模块在 fork 仓库不存在。消费方若解析全平台变体(如 compose-hot-reload 的 `syncDesktopMainStartupLibs` 会拉 salt-ui 传递的 macos-arm64)会解析失败;Voxzen 侧已在 `settings.gradle.kts` 用"未发布模块不改写版本 + `ComponentMetadataRule` 移除严格约束"处理
