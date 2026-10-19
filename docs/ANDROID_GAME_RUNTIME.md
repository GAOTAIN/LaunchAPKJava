# Android 游戏执行链

本轮提供真实执行入口，但没有可用 Android 设备完成端到端验收。编译、桌面单元测试、ELF/符号检查不能证明 Minecraft 已进入主菜单。

## 使用

1. 安装本轮 Debug APK。下载原版后，在运行环境导入**可信 Android/Bionic** JRE ZIP，校验 SHA-256，先运行既有 Java main 自检。桌面 JRE 不能替代 Android JRE。
2. 建议先验证 1.16.5 与 Java 8：它使用 LWJGL 3，图形要求更接近当前 GL4ES 的 OpenGL 2.1。当前 GL4ES 不能承诺支持需要 OpenGL 3.2/更高能力的版本与模组。
3. 首页选择离线档案，点击“选择版本并启动”。准备检查通过后点击“启动游戏”。所有版本文件、资源、运行时和账户都来自用户操作；APK 不包含 Minecraft、JRE 或账户。
4. 游戏中空白处触摸操作菜单，捕获鼠标时拖动视角；玻璃按键/摇杆发送 GLFW 键码与鼠标按钮。顶部“编辑模式”复用完整布局编辑器，“保存”写入应用私有 controls/default.json。“返回”退出独立游戏进程；系统返回键发送 Esc。
5. 返回启动页查看最近游戏日志。Android `filesDir/logs/game-*.log` 收集标准输出和异常，`*.hs_err_pid*` 收集 HotSpot 致命错误。`last-game.properties` 记录最近执行阶段，不含 argv/令牌。`java_main` 残留可能表示崩溃或系统终止；`vm_shutdown` 只表示执行了 JVM shutdown hook，不能证明正常进入过主菜单。

## 结构和线程

- `GameActivity` 声明为非导出、横屏、独立 `:game` 进程。Compose Overlay 与 SurfaceView 使用同一个视口；不申请 SYSTEM_ALERT_WINDOW。
- Surface 建立后，工作线程重新检查版本与运行时，复制并校验私有 LWJGL/JNA Java 绑定；重新生成 classpath，不修改下载的原始 JAR。引导类由 Gradle 编译 Java 8 字节码，不包含游戏内容。
- `NativeBridge.launchJvm` 只在这个进程调用一次。JNI 在创建 HotSpot 前复制 ART 字符串，传递 argv 数组；不跨 VM 复用对象/类引用，不启动 shell。
- 原创 `libglfw.so` 使用公开 GLFW C API。游戏线程创建 EGL ES2 上下文，把 Android Surface 接到 GL4ES，实际交换缓冲。GL4ES 保留其真实 OpenGL 2.1 能力，不伪报 3.x。
- 所有触摸/按键通过 JNI 排队，只有游戏线程在 glfwPollEvents 中调用 LWJGL/libffi 回调。键状态、鼠标状态、相对视角、绝对菜单触摸、尺寸和失焦在该线程更新。队列溢出先合成释放事件，防止卡键。
- Android Surface 丢失会请求关闭窗口；按键释放后退出游戏进程。HotSpot 不在当前进程重建。Android 设备上的应用私有目录由 filesDir/cacheDir/nativeLibraryDir 决定。
- 窗口仅支持一个游戏上下文；桌面窗口装饰、系统 Gamma、Vulkan 和游戏手柄不属于本次适配。剪贴板仅为当前游戏进程内的文本。没有假造这些功能可用的成功状态。

## 当前限制

- 原生 ABI：arm64-v8a、x86_64。固定的第三方 LWJGL/JNA JNI 库存在 4KB 数据段，只允许 4KB 内存页设备。项目自己编译的桥/GLFW/GL4ES/OpenAL 为 16KB 对齐，但不能据此声称整个 APK 适配 16KB。
- LWJGL 2 和 3.4 在准备阶段拒绝。目标为 LWJGL 3.2/3.3 版本；替换为 3.3.3 Java/native 绑定需要实机验证二进制/API 兼容性，不保证所有版本。
- 较新 Minecraft 可能因 OpenGL 3.2+、缺少 AWT 组件或运行时本身不兼容而失败。需要后续具备实际 OpenGL 能力的渲染插件/运行时适配，不能通过伪造 capability 声明解决。
- Fabric 已准备的继承配置可走相同入口；Forge/NeoForge installer processors 仍未执行。
- 当前启动入口读取本地离线档案，不伪造微软身份/令牌。已有微软官方登录与所有权校验模块保留，正式会话传递尚未接入该游戏 Activity。
- 暂无可用 ADB 真机连接；尚未验证首帧、音频、Minecraft 主菜单、世界进入、操作和 Surface 生命周期。

## 依赖与分发

依赖下载入口是 `scripts/setup-game-runtime.py`。根目录由脚本位置推导，不硬编码本机路径；所有写入严格限制在该根下。`nativebridge/third_party/game-runtime-lock.json` 记录确切 URL、大小和 SHA-256；再次执行对已有记录校验，不能悄悄更新版本。Java assets 的独立 SHA-256 用于手机端复制校验。

- LWJGL：官方 3.3.3 Maven Java 绑定；Android JNI 子集来自 FCL 固定提交内的 lwjgl-3.3.3 AAR。LWJGL BSD-3-Clause、libffi MIT、STB 双许可、TinyFD zlib 等原许可和头保留。只提取 core/opengl/stb/tinyfd 的双 ABI 库，不导入 FCL Java 应用、GLFW 替换类或其他渲染器。
- GLFW：3.3.8 固定提交的公开头，zlib 许可；窗口实现为本项目原创。为 LWJGL 3.3.3 提前解析的 3.4 入口提供必要 ABI；自定义 allocator 明确不支持。
- JNA：官方 5.13.0 Android AAR 的 classes.jar 与 libjnidispatch.so，选用其 Apache-2.0 许可；AL2.0、原 LICENSE 和 native libffi 的原许可一并提供。
- OpenAL Soft：固定 1.23.1 提交，LGPL-2.0-or-later，完整原始源码和版权头保存在 third_party/openal-soft；CMake 在外部构建目录生成 libopenal.so，不改上游源码。OpenSL 后端包含 AOSP Apache-2.0 代码，其他 BSD 许可见原源码及 APK 中 notices。

**许可证影响**：OpenAL 是独立共享库，但 JNI/dlopen 不自动免除 LGPL 要求。分发 APK 时应同时提供确切对应源码、此处构建配置与可重构/重新链接的应用材料，允许为调试库的修改而进行所需操作；本仓库尚未选择自身代码的分发许可，不代表已获闭源发布许可。不要只发布 APK 后假设引用上游链接就满足所有义务。参考 GNU LGPL v2 原文第 4–6 节：https://www.gnu.org/licenses/old-licenses/lgpl-2.0.html 。原 LICENSE 文件不作修改。

## 验证

在仓库根目录运行 gradlew.bat :app:assembleDebug、gradlew.bat test、gradlew.bat :nativebridge:externalNativeBuildDebug。`scripts/verify.ps1` 包含上述命令并清理项目 .tmp。`scripts/verify-game-runtime.py` 检查 APK、必需 GLFW 符号、Java assets 校验值与 ELF ABI，并记录当前 4KB 限制；这些是桌面检查。

`launchapk_events_test` 是 EXCLUDE_FROM_ALL 的设备端 C++ 测试目标，不打包进 APK。它检查视角增量、多指状态、事件顺序与溢出释放。可通过 CMake 显式构建后在相应 ABI 的 Android 设备执行；目前只做交叉编译检查，未在设备执行。
