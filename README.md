# LaunchAPK Java

非官方 Minecraft Java 版启动器。包含横屏玻璃界面、账户与下载管理、可编辑虚拟按键、JRE 导入，以及新接入的 Android LWJGL 3 / GLFW / Surface / GL4ES / OpenAL 游戏执行链。**已具备实际游戏启动入口；尚未在 Android 真机上验证进入 Minecraft 主菜单，不能宣称已可玩。**

## 本轮：实际游戏执行入口

首页 **离线 → 选择版本并启动**，选择完成下载的 LWJGL 3 版本，导入对应 Android/Bionic Java 8/17/21 后点击 **启动游戏**。`GameActivity` 在独立 `:game` 进程持有 Surface，后台线程创建 HotSpot 并通过项目自己的 Java 引导类调用版本主类；Minecraft 的主循环持续运行，退出后销毁该进程。

虚拟按键直接覆盖同一游戏 Surface。空白区域在菜单中定位/点击光标，捕获鼠标后转换为视角位移；WASD、跳跃、潜行、左右键和摇杆经线程安全 JNI 队列进入 GLFW 回调。顶部 **编辑模式** 可拖动、添加、改映射、缩放、调透明度、删除和保存；布局继续使用 `filesDir/controls/default.json`。失焦、取消或退出会释放输入。

Android LWJGL 3.3.3 原生库、原版 LWJGL Java 绑定与官方 JNA Android 分发已固定来源和 SHA-256；原创 GLFW C API 适配器接管 EGL 窗口、缓冲交换、尺寸与输入。OpenAL Soft 从完整源码构建独立共享库并启用 OpenSL 音频后端。没有复制 GPL 启动器应用代码，没有内置 Minecraft 或 JRE。

**边界与验收状态**：当前依赖仅适用于 arm64-v8a/x86_64 的 **4KB 内存页**设备；16KB 设备在启动前明确拒绝。GL4ES 提供 OpenGL 2.1；建议先以 **1.16.5 + Android Java 8** 验证，较新版本和模组可能因更高 OpenGL 要求失败。LWJGL 2、LWJGL 3.4、Forge/NeoForge processors、微软会话交给游戏的入口均未包含在本轮。没有可用 ADB 真机连接，JRE 加载、音频、主菜单与游戏内操作仍待实机验收。

错误与 JVM stdout/stderr 保存到 `filesDir/logs/game-*.log`，HotSpot 致命日志同目录；返回版本页可查看最近运行日志。会话记录能区分 Java 引导阶段、错误、VM 关闭与手动停止；交换首帧只代表发生过缓冲交换，不代表已进入主菜单。

详细边界、复测步骤、许可证与依赖来源见 `docs/ANDROID_GAME_RUNTIME.md` 和 `THIRD_PARTY_NOTICES.md`。以下章节为之前增量的历史记录，其“尚未接入”描述对应当时状态。

## 本轮增量：版本启动准备与 Java main

首页原先禁用的游戏启动按钮改为 **选择版本与启动检查**。本轮补上版本解析与 JNI main 调用两个基础环节，**没有完成 Android LWJGL/GLFW，仍不能进入 Minecraft 主菜单**；检查页会明确显示缺项，没有把已下载或自检成功当作游戏可玩。

### 手机上验证

1. 在 **下载 → 游戏版本 → 原版** 完成版本下载；Fabric 配置可在原版完成后按加载器页面准备。
2. 首页选择 **离线**，添加并选用本地档案，点击 **选择版本与启动检查**。选择已下载版本后，检查页显示主类、继承的原版、Java 要求、classpath、资源数量和实例目录。版本选择保存在应用私有设置。
3. 客户端、库、资源索引或日志配置缺失/哈希不匹配，缺少父版本、继承循环、主类不存在、未知参数、Java 主版本不匹配等问题会逐项提示。资产对象检查存在性及大小；本轮不在每次检查时重算全部资产对象哈希，下载时已有 SHA-1 校验。
4. 进入 **运行环境**，导入匹配的 Android/Bionic JRE 后，点击新增的 **执行 Java main 自检**。`:jvm` 独立进程创建 HotSpot，用 JNI 调用项目自写 Java 8 字节码程序的 `main(String[])`，验证空格、中文和补充 Unicode 参数，并读取 JVM 实际属性。结果文件采用每次独立名称，校验后清理，不会复用旧成功结果。
5. 启动准备日志位于 Android `filesDir/logs/launch-check-*.log`；main 自检日志为 `filesDir/logs/jvm-main-*.log`，原有属性自检日志继续保留。退出/超时会解绑服务并停止独立 JVM 进程。日志不保存完整 argv 或微软令牌。

### 本轮实现边界

- `InstalledVersions` 读取已发布版本 JSON，逐个显示损坏配置，解析最多 16 层继承并拒绝循环、路径越界和 id 不匹配。子版本库按 group/artifact/classifier 覆盖父版本，保留不同 classifier；排除桌面 native classifier，并应用 Linux/架构规则。
- `LaunchPlanner` 合并新式 arguments，支持旧式带引号 minecraftArguments、features、占位符、assets/logging、客户端和库 SHA-1 及 classpath 主类检查。参数按 argv 列表传递，不拼接 shell；JNI VM options 使用 `-Djava.class.path`，不会将命令行的 `-cp` 直接交给 JNI_CreateJavaVM。
- 离线身份仅来自已验证昵称和稳定 UUID 的本地档案。离线计划中的 token 字段为本地占位值，不包含微软凭据，也不代表在线身份或游戏许可；正版授权配置与校验保持独立。当前检查不连接服务器，也不执行游戏。
- `NativeBridge.launchJvm` 是真实的 main 调用入口，处理独立进程日志、工作目录、System class loader、跨 ART/HotSpot 字符串复制、异常与 VM 退出；不会跨 JVM 复用 JNI 对象。每个独立进程只尝试一次创建 VM。
- 自写 `launchapk-smoke.jar` 由 Gradle 编译为 Java 8 字节码并作为 assets 随 APK 提供，只包含项目自己的探针类；它不是 Minecraft/JRE/加载器文件。桌面单元测试只验证该探针字节码与 argv，不能代替 Android JNI/JRE 真机验证。

**下一步缺口**：Android/Bionic LWJGL 原生库、GLFW 的 Surface/EGL 上下文与窗口回调、输入 JNI/GLFW 连接，以及把准备好的启动计划交给承载游戏 Surface 的独立进程。Forge/NeoForge processors 仍未执行；部分旧版/模组参数被显式拒绝，不能宣称所有加载器或版本可启动。未导入可信 JRE 或没有手机时，不宣称 Android main 自检/游戏已运行。

验证结果：新增 13 项测试，全工程 79 项测试在 Debug/Release 产生 158 条成功记录，零失败/错误/跳过；assembleDebug、test 与双 ABI CMake/NDK 构建均通过。APK 已检查六个横屏 Activity、双 ABI main JNI 导出、16KB ELF 对齐和唯一自写 Java 8 探针 JAR。手机 JRE/JNI/main、GPU 和游戏主菜单尚未真机验证；桌面 Unicode 测试直接传递 UTF-16，避免本机 Windows GBK 命令行转换影响。核验记录为 `build-reports/launch-chain-verification.json`，改动清单为 `launch-chain-changed-files.txt`。

本轮没有引入参考启动器源码、新的库依赖或第三方 JVM；原有 LICENSE 和版权头保持不变。JNI 调用按 [Java Invocation API](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/invocation.html) 独立实现。

## 应用图标

新增原创的白色玻璃底、蓝色 L＋播放符号。Manifest 的 icon/roundIcon 均引用自适应图标，API 26+ 支持系统圆形/圆角方形等遮罩，API 33+ 另有单色主题层；保留默认矢量资源。没有使用 Minecraft、其他启动器或 iOS 的图标资源。预览位于本机 `build-reports/app-icon-preview.png`，APK 内使用 XML 矢量。

## 本轮增量：正版登录与运行环境

**当前新增的是实际授权流程与运行时/原生自检；尚未完成 Minecraft 的 LWJGL/GLFW 适配，不能启动到主菜单。**

### 微软账号

从启动页 **正版 → 配置与微软登录**，或 **设置 → 账户管理 → 配置与微软登录** 进入。先填写自己的 Microsoft Application（client）ID，点击保存。应用注册需要支持个人微软账户、允许公共客户端设备码流程，并具备 Xbox/Minecraft 所需 API 访问；没有内置其他启动器的应用 ID、密码或 client secret。

点击“微软登录”，在微软官方网页输入授权码，确认后返回本应用。流程为设备码 OAuth → Xbox 用户令牌 → XSTS → Minecraft access token → 游戏许可 → Java 角色资料。无许可或授权失败会显示错误，不把离线档案当成微软身份。按服务器轮询间隔等待，支持取消、拒绝、过期和 slow_down；刷新时重新检查游戏许可与角色资料。协议见 [Microsoft 设备码文档](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)。

登录记录通过 Android Keystore AES-GCM 加密保存至 `filesDir/accounts/microsoft.enc`；client ID 不是秘密，单独保存在私有设置。令牌不写入普通 JSON、日志或界面保存状态。退出删除本机加密记录；不会替用户退出浏览器中的微软会话。本轮用户尚无 client ID，因此真实账号端到端授权留待配置后验收，单元测试只使用明确的假响应。

### Android JRE 与 JNI 启动自检

从 **启动 → 运行环境** 或 **更多 → 运行环境** 进入。准备可信来源的 Android/Bionic Java 8、17 或 21 ZIP，填写其 SHA-256 后导入。支持 arm64-v8a 和 x86_64，必须与应用进程匹配；拒绝桌面 JRE、越界路径、重复文件、哈希错误和超限解压。解压到暂存目录，检查 release、ELF 和许可证后才替换已有运行时；包内声明完整保留。运行时位于 `filesDir/runtimes/current`。

若上游提供 `.tar.xz`，可先在项目内保存原始文件并转换；脚本只接受工作区内的输入和输出，不自动下载或分发 JRE：

```powershell
python -B E:\APkMCJAVA\scripts\prepare_android_jre.py E:\APkMCJAVA\input\android-jre.tar.xz E:\APkMCJAVA\input\android-jre.zip
```

转换器会打印 ZIP 的 SHA-256，展开包内的文件符号链接，并保留 LICENSE/legal。请先自行核验原始运行时的发布来源与校验值；转换后的哈希只验证本次 ZIP 传输完整性。

导入后点击 **运行 JVM 自检**。非导出的 `:jvm` 服务在独立进程用 JNI/dlopen 调用运行时的 `JNI_CreateJavaVM`，读取 HotSpot 中的 java.version/java.vm.name/os.arch；不是返回写死的 Java 版本。运行完成、退出页面或超时后停止独立进程，避免重复创建 VM 和牵连 Compose UI。stdout/stderr 与 HotSpot 错误文件保存至 `filesDir/logs/`，失败会给出可读提示。不同 Android JRE 仍可能有系统库/厂商兼容问题，必须在手机上验证。

### GL4ES 渲染自检

GL4ES 锁定上游 MIT 版本，从源码构建进 APK。点击 **运行 GL4ES 渲染自检**，通过 EGL 与真实 Surface 调用 GL4ES 的 OpenGL 立即模式接口绘制三角形，并显示实际 GL/GPU 信息；不是用 Compose 模拟渲染。Zink/VirGL 仅预留接口，当前禁用。

该自检不包含 Minecraft 或桌面 LWJGL 原生库。实际游戏仍需 Android LWJGL/GLFW、窗口/输入回调、已解析启动计划的执行与渲染兼容检查；GL4ES 也不能保证所有新版游戏或光影可运行。原有虚拟按键调试继续使用 Logcat bridge。

### 原生构建环境

项目内新增 NDK `27.2.12479018` 与 CMake `3.22.1`，由 `scripts/setup_native_tools.py` 从 Google 官方固定归档安装并核验 SHA-1，另外记录 SHA-256。全部工具与临时文件留在本工程，原有许可证不改动。Native bridge 使用 C++17/JNI，构建 arm64-v8a、x86_64 并启用 16KB 页大小兼容链接选项。

## 模组加载器下载与配置

下载中心的 **游戏版本** 现分为 **原版 / Forge / Fabric / NeoForge**。选择加载器后输入完整的 Minecraft 版本号（例如 `1.20.1`），自动读取官方清单；可显示测试版、刷新、查看 Forge 官方推荐版本并加入后台任务。版本输入须匹配 Mojang 清单，不把相近版本混在一起。加载器入口位于游戏版本分类，模组文件仍在独立的模组分类。

- **Fabric**：先点击“先下载原版”，等任务完成后返回 Fabric，点击“准备配置与依赖库”。校验对应原版客户端，读取官方客户端 profile，校验 Maven 坐标、依赖哈希和 JAR 结构，全部成功后原子保存继承配置至 `filesDir/minecraft/versions/fabric-loader-<loader>-<game>/`，依赖保存至 `filesDir/minecraft/libraries/`。保留上游配置，并补充标准 artifact 描述；没有对动态 profile 宣称存在官方哈希。Fabric API 是独立模组，需另外下载。
- **Forge / NeoForge**：读取官方 Maven 清单，下载所选版本的官方 installer JAR，读取官方 SHA-1、校验包结构及 `install_profile.json` 中的 Minecraft 版本。保存于 `filesDir/loaders/forge|neoforge/<game>/<artifact-version>/`；任务完成提示明确写为“安装器已下载、尚未安装”。不会执行桌面 GUI 安装器或将其当作普通模组。
- **NeoForge 1.20.1**：单独使用官方历史 `net.neoforged:forge` 坐标，不套用后续 `net.neoforged:neoforge` 路径。NeoForged 官方在此游戏版本建议优先使用 Forge。20/21 系列与 26+ 四段版本分别匹配 Minecraft，快照及无法确定映射的版本不混入正式版本列表。
- 下载复用现有 WorkManager 队列、通知、重试、取消和原子替换。安装器验证在发布文件之前完成；失败保留原文件，取消清理临时下载。旧下载任务 JSON 仍可读取。

**未完成项**：Forge/NeoForge 的安装器 processors、补丁执行与产物校验尚未接入；Fabric 保存的是配置和依赖，Android LWJGL/GLFW 与启动计划的游戏执行仍待完成。当前不能启动带模组的 Minecraft，也没有宣称光影、模组或各版本已兼容 Android。下载能力不等于运行兼容性。

本增量新增 17 项测试，全工程 66 项测试在 Debug/Release 产生 132 条成功记录，零失败/错误/跳过；assembleDebug、test 与双 ABI CMake/NDK 构建通过。另联网校验 Forge、NeoForge 历史/现代/四段版本安装器，以及 Fabric profile 和 8 个依赖的真实官方哈希/包结构。安装器只在内存中检查，未执行、未捆绑。记录见 `build-reports/mod-loader-verification.json`、`mod-loader-sources.json` 与 `mod-loader-apk.json`；尚无 Android 真机或游戏运行验证。

官方接口与规则：[Fabric Meta](https://meta.fabricmc.net/)、[Forge Maven](https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml)、[NeoForge 版本规则](https://docs.neoforged.net/docs/gettingstarted/versioning/)、[NeoForged 用户指南](https://docs.neoforged.net/user/docs/)。没有引入其他启动器代码或新的库依赖，没有内置加载器、游戏或模组二进制；下载文件原有许可证保留在原包内，许可影响记录于 `THIRD_PARTY_NOTICES.md`。

## 横屏、账户与分类

- 主界面、下载页、按键调试页均在 Manifest 声明 `screenOrientation="landscape"`，普通手机全屏打开即横屏。多窗口/桌面窗口的最终方向仍由系统管理，参见 [Android Activity 方向说明](https://developer.android.com/guide/topics/manifest/activity-element#screen)。未增加方向或悬浮窗权限。
- 顶部固定分为 **启动 / 下载 / 设置 / 更多**。启动页采用左侧账户面板、右侧内容卡片，参考用户提供的 PCL 分类结构；白/黑玻璃和蓝色强调保持原有设计，没有使用 PCL 的代码、图像、标识或截图。
- **启动**：正版/离线两个账户类型入口、所选离线账户、下载与按键调试快捷入口。微软入口已接入应用配置和设备码授权，游戏启动仍待适配。
- **下载**：游戏版本 / 模组 / 整合包 / 光影 / 材质包 / 任务，沿用已有下载实现；导航切换保留分页和表单状态。
- **设置**：外观与性能 / 账户管理；外观开关即时生效并保存。
- **更多**：按键调试工具与关于。

### 手机上体验离线账户

1. 安装更新后的 Debug APK，打开应用后进入横屏首页。
2. 选择 **离线 → 添加离线账户**，或进入 **设置 → 账户管理**。
3. 输入 3–16 位英文字母、数字或下划线昵称，点击 **添加并选用**；支持最多 32 个，重复昵称不区分大小写。
4. 在账户列表点击 **选用** 切换当前账户；删除需要确认，仅删除本地账户记录，保留游戏存档和下载文件。
5. 返回 **启动**，左侧显示当前昵称。杀进程重开后恢复账户列表和选择。

账户数据以 JSON 保存在 Android `context.filesDir/accounts/offline.json`，由 `auth` 模块在 IO 线程读写并原子替换。UUID 依照 `OfflinePlayer:<昵称>` 的 UTF-8 字节生成稳定的 name UUID，大小写影响身份；昵称不能编辑，避免隐式改变 UUID。文件没有密码、令牌、微软身份或所有权信息。文件损坏时显示错误并可重新读取，禁止用空列表静默覆盖原记录。

本轮的离线账户要求取代早期仅支持正版账户的产品限制。微软 OAuth、令牌刷新及所有权校验现由独立的正版账号流程接入。离线档案已可管理和保存，**不代表当前已能离线启动游戏**：Android LWJGL/GLFW 及游戏 Surface 启动仍待适配。

## 固定命名

- 应用显示名：**LaunchAPK Java**；对外描述：**非官方 Minecraft Java 版启动器**。
- Gradle `rootProject.name`：`LaunchAPKJava`。
- 应用 `applicationId` 与 `namespace`：`com.launchapk.java`，后续不随意变更。
- 应用源码包：`com.launchapk.java`；设计系统位于其 `com.launchapk.java.ui` 子包及库命名空间，避免与应用生成资源类冲突。
- 工作目录保持原位置，不因产品改名移动仓库。

## 玻璃设计系统（本轮增量）

浅色使用白到浅灰背景、白玻璃卡片及近黑文字；深色使用黑到深灰背景、黑玻璃卡片及近白文字。蓝色只用于交互与强调。浅色强调蓝略加深以保证小字号按钮文字的对比度，不使用大面积蓝背景。

- `GlassTokens`：独立浅/深色配色；普通卡片透明度为 0.68/0.55，降低透明度时为 0.96。
- `GlassCard`：24dp 圆角、1 个物理像素的顶部高光渐变描边、顶部反光与柔和阴影。
- `GlassButton`：胶囊形、至少 48dp 触控区域、按压缩放 0.97，次级按钮按下时高光和蓝边增强。
- `GlassDialog`：使用同一玻璃卡片、文字与按钮，已接入“关于”入口。
- `GlassReveal`：220ms 淡入与 0.98 → 1 的轻微缩放，避免弹跳。
- 背景与装饰层单独绘制；API 31+ 可模糊背景装饰，文字、按钮和内容从不模糊。当前不是游戏画面的实时折射或逐卡片背景采样；输入模块已复用玻璃控件实现调试 Overlay；真实游戏画面接入仍待实现。
- API 26–30 自动禁用模糊；低内存设备默认禁用。提供“关闭模糊”和“降低透明度”开关，后者也禁用模糊；切换后立即生效。
- 外观开关通过 Android 私有 SharedPreferences 保存，跟随系统浅/深色模式；无悬浮窗权限。按键布局另由 `input` 模块保存至 `filesDir/controls/default.json`。
- 首屏文字适配系统字体缩放与滚动；系统栏图标随浅/深色模式更新。

需要真机补验：浅/深色视觉效果、大字体、小屏幕、API 26/30 的降级、API 31+ 开关效果、低内存设备默认值、杀进程后外观设置恢复。配色与降级单元测试已在 Debug/Release 两种变体通过，真机视觉和恢复验收仍待执行。

[Compose 官方模糊说明](https://developer.android.com/develop/ui/compose/graphics/images/customize)：硬件模糊仅支持 Android 12 及以上。本轮组件均为 Compose 自绘，没有使用 iOS 私有资源、截图或第三方玻璃库。

## 手机体验：虚拟按键调试

安装 `app/build/outputs/apk/debug/app-debug.apk` 后：

1. 打开 LaunchAPK Java，点击首页 **调试按键**；页面横屏全屏显示自绘方块场景，不使用游戏资源或悬浮窗权限。
2. 默认进入游玩模式。按住 W/A/S/D、空格、Shift、左右鼠标键或拖动摇杆；在空白区域滑动可发送视角 Pointer 事件。支持一指移动、另一指按键或滑动视角；底部显示最近一条事件。
3. 点击 **编辑模式**，点击并拖动按键或摇杆来移动。选中后点 **属性**，通过滑块调整按键宽高、不透明度或摇杆半径；按键可改名称和 GLFW 数字键值，也可删除。
4. 顶部支持 **加按键**、**加摇杆**、**恢复预设**；窄屏时顶部按钮栏可横向滚动。属性中的名称/键值在点“应用”时提交，尺寸与透明度即时预览。
5. 点击右下角悬浮 **保存**，布局写入 Android `context.filesDir/controls/default.json`。返回或杀进程后重新进入调试页，会加载已保存布局；没有布局文件时自动创建预设。
6. 有未保存更改时，返回会询问保存、放弃或继续编辑。读写失败会显示错误；损坏文件不会被自动覆盖，仅显式保存才替换。

Logcat 标签为 `LaunchAPK.Input`。例如 W 输出 `KEY code=87 pressed=true/false`，左/右鼠标键输出 `MOUSE button=0/1 pressed=true/false`。`sendPointer` 的 x/y 是覆盖层内像素坐标，action 为 DOWN=0、UP=1、MOVE=2、CANCEL=3。按键映射使用 GLFW 编码（空格 32、Shift 340、Esc 256）；布局以 -1/-2 表示鼠标左/右键，由分发器转换为鼠标事件。

按键位置与宽高采用视口归一化比例；摇杆位置表示中心，半径是短边比例。JSON 带 `schemaVersion=1`，保存前检查唯一 ID、坐标、尺寸、透明度及键值，并以临时文件和原子替换保存。切换模式、退出、窗口失焦或退到后台时释放所有输入；同一键的多触点持有会合并，避免提前松键。

该调试页只使用 `LogcatInputBridge` 验证输入，不包含游戏 JVM 或 JNI 注入。未来 `InputBridge` 可替换为真实 LWJGL/GLFW JNI 实现；当前**不是已经能运行 Minecraft 的游戏页面**。真实触屏、低端设备性能和杀进程恢复仍需要手机实测。

## 应用内下载中心

点击顶部 **下载** 或启动页 **打开下载中心**，包含游戏版本、模组、整合包、光影、材质包和任务六个分页，沿用玻璃设计系统。

### 游戏版本

原版版本列表读取 Mojang 官方 `version_manifest_v2.json`。选择版本并确认下载后，任务校验版本 JSON，再下载客户端、按 Android/Linux 架构规则选择的 Java libraries、资源索引、资源对象及日志配置。文件落在 Android `filesDir/minecraft/` 的标准 versions/libraries/assets 子目录；全部文件校验完成后才发布版本 JSON。

- 官方清单及文件使用 SHA-1 校验。已通过校验的文件在重试时复用，未完成的单文件重新下载；本轮没有实现 HTTP Range 分段续传。
- 默认显示正式版，可以按版本号筛选或显示快照。支持含空格的历史版本 ID；旧版 virtual/map_to_resources 资源布局会明确提示暂不支持。
- 下载 Java 游戏文件不等于已获得可运行的 Android 环境：已有 JRE/JNI/GL4ES 自检与正版授权流程，实际运行仍需 Android LWJGL/GLFW 与游戏 Surface 执行链，首页现可打开启动准备检查。桌面 native classifiers 不作为 Android 库安装。

### 从 MC百科查找四类扩展内容

1. 选择模组/整合包/光影/材质包，输入关键词，点击 **在应用内搜索 MC百科**。
2. 在原网页进入资料页，点击顶部 **读取当前资料页**。也可以直接粘贴 `https://www.mcmod.cn/class/数字.html` 或 `/modpack/数字.html`。
3. 可填写 Minecraft 版本和加载器（如 fabric、forge、iris），点击读取。资料页公开列出的 Modrinth 项目会转换为原生文件列表，按分类检查类型并展示版本、加载器、文件名和体积。
4. 点击 **下载并校验** 后确认，文件直接从作者 Modrinth 的下载源获取并校验 SHA-512。没有可解析文件时可点击作者原站入口，在应用内网页选择文件；网页触发的下载会再次显示文件名和来源确认。
5. 原网页下载若没有校验值，明确显示未提供哈希，并检查 ZIP/JAR 包结构；不把这种结果标记为哈希校验成功。需要网站登录、验证码或受限下载流程的来源可能无法由后台任务获取，应按原站流程操作，本轮不绕过。

扩展文件保存至 Android `filesDir/content/mods|modpacks|shaderpacks|resourcepacks/`。本轮仅下载原文件，**不自动解包整合包、不安装模组依赖、不自动应用到游戏实例**；加载器下载与 Fabric 配置见上文；光影和部分模组是否支持 Android 渲染仍需实测。不会捆绑游戏或扩展文件进入 APK。

### 抓取与来源规则

本轮核验：[主站 robots.txt](https://www.mcmod.cn/robots.txt) 禁止自动请求 `/jump/` 等路径；[搜索站 robots.txt](https://search.mcmod.cn/robots.txt) 禁止自动抓取 `/s`。因此搜索以用户操作的 WebView 原网页完成，原生解析器只允许 class/modpack 的数字资料页，并在请求前检查主站 robots 规则。资料读取串行限速并做会话缓存，只保留标题、来源和公开项目链接，不批量抓取全文或重建百科数据库。

公开链接中的目标编码只作本地解码，不自动访问跳转端点。下载候选从 Modrinth 官方 API 获取；CurseForge 等来源保留原网页入口，没有内置 API 密钥或绕过作者的分发选择。

### 下载任务

WorkManager 持久化任务，联网后调度，显示进度、完成路径、失败原因，支持取消和失败重试。应用重开可继续查看任务；支持在后台用通知显示进度，仍受 Android 系统调度限制。下载经过 HTTPS 校验、重定向与私网地址检查、路径检查、临时文件写入和完成后原子替换；取消或校验失败不会发布临时文件，也不会覆盖已有的完整文件。401/403/404/429 不自动循环请求。

客户端使用作者原始文件，保留资料页与下载源记录。PCL（Plain Craft Launcher）的分类、筛选和任务列表思路由用户指定作为交互参考，本轮未复用其代码；应用为独立实现。参考仓库的[自定义许可](https://github.com/Hex-Dragon/PCL2/blob/main/LICENCE)已检查，不能视为 MIT/Apache 授权。

## 构建

- JDK 17 或 21；Java/Kotlin 字节码目标为 17。
- 项目内 SDK 已安装于 `.android-sdk/`：Command-line Tools 19.0、Platform 35、Build Tools 35.0.0、Platform Tools。根目录 `local.properties` 已按本机 SDK 生成并被 Git 忽略；Windows Wrapper 也可在未设置 `ANDROID_HOME` 时自动识别该目录，不修改系统环境变量。
- Gradle 8.13、AGP 8.11.1、Kotlin/Compose 编译插件 2.2.20、Compose BOM 2025.04.01。
- minSdk 26，compileSdk/targetSdk 35。

在仓库根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat test
```

也可使用仓库内的 `scripts/verify.ps1`，依次执行两个命令，将结果保存到 `build-reports/`，并在最后清理 `.tmp/`。涉及原生代码，构建时会运行 CMake/NDK；也可单独运行 `gradlew.bat :nativebridge:externalNativeBuildDebug`。新增 `ui` 的配色对比度、降低透明度和模糊 API 降级单元测试；`test` 成功也不代表游戏功能通过验收。

Windows Wrapper 保留官方版权头，增加了基于脚本位置计算的项目目录、Gradle 用户目录 `.gradle-user-home/`、Java 用户目录 `.build-home/`、Android 用户目录 `.android/` 和临时目录 `.tmp/`。所有这些目录均在仓库内；未写入本机绝对路径。使用 Wrapper 后可从任意调用目录构建，无需切换目录。直接运行 Wrapper 后如产生临时文件，应清理 `.tmp/`；验证脚本会自动清理。

## 项目内 Android SDK

SDK 是本机构建工具，不打包进 APK，也不提交到版本控制。`.android-sdk/` 已加入忽略规则；官方许可证、组件元数据和安装校验记录保留在 SDK 目录中，项目及 Wrapper 原有许可证未修改。

在新的 Windows 开发环境上，可运行以下脚本复现安装（需要 Python 3.9+、JDK 17/21 和网络）：

```powershell
python -B E:\APkMCJAVA\scripts\setup_android_sdk.py --accept-licenses
& E:\APkMCJAVA\scripts\verify.ps1
```

`--accept-licenses` 表示接受所选稳定 SDK 包所需的 [Android SDK 许可协议](https://developer.android.com/studio/terms)。脚本只安装构建所需包和 Platform Tools，不安装模拟器、系统镜像、NDK 或游戏文件。命令行工具来自 Google 官方仓库，下载后验证官方校验和；安装过程先放在项目 `.tmp/` 下，成功后移入项目内 SDK 目录，并清理临时文件。

安装日志为 `build-reports/sdk-install.log`，SDK 内 `installation-receipt.json` 记录命令行工具版本、来源、SHA-1 和 SHA-256。构建时的 Gradle/Android 用户目录及 Java 临时目录也保留在项目内，不要求全局 PATH 配置。

## 本轮验证状态

- `:app:assembleDebug`、`test`、`:nativebridge:externalNativeBuildDebug` 均通过。现有与新增测试共 49 项，Debug/Release 报告合计 98 条，0 失败/错误/跳过。新增覆盖设备码等待/减速/拒绝/过期/取消、Xbox 身份匹配、游戏许可、刷新、AES-GCM 存储与篡改保护、JRE 路径/架构/哈希及失败保护。
- 已核验最终 APK：包名 `com.launchapk.java`、五个横屏 Activity、非导出的 `:jvm` 服务、ARM64/x86_64 的 JNI/GL4ES 库及所需导出符号、16KB ELF LOAD 对齐、图标的 API 26/33 资源与第三方许可证。应用层限制 64 位 ABI，避免只有 Compose 的 32 位库而缺少启动桥。
- JRE 转换器通过合成 tar.xz 的 SHA-256、许可证保留、文件链接展开与越界拒绝检查；NDK 安装脚本重复执行保留原校验记录。合成 ELF/归档只测试校验逻辑，不能代替真实 JVM 执行。
- 未进行真实微软授权、Android Keystore 真机恢复、JRE 启动、GPU 渲染或真实游戏测试；用户尚未配置自己的 client ID，工程不附带 JRE 或游戏。游戏启动仍缺 Android LWJGL/GLFW 与游戏 Surface 执行链。
- 日志为 `build-reports/assembleDebug.log`、`test.log`、`native-build.log`；核验记录为 `runtime-auth-verification.json`，完整源码与主要产物清单为 `runtime-auth-changed-files.txt`。本轮所有文件写入留在工程内，`.tmp/` 已清理。上游 GL4ES 源文件与许可证未修改。

## 逐步实现的模块边界

当前创建 `app`、`ui`、`input`、`core`、`auth`、`launcher`、`nativebridge`、`renderer` 模块；其他模块按后续增量添加。布局存储暂位于 `input`，以后可拆入固定名称的 `controls` 模块。

| 模块 | 职责与待办 |
| --- | --- |
| app | Compose + Material3：横屏四分类、账户管理、外观设置、下载和按键调试入口；已增加版本启动准备 Activity；游戏 Surface Activity 待实现。 |
| ui | 已有 GlassTokens、GlassTheme、GlassBackground、GlassCard、GlassButton、GlassDialog 和淡入缩放出现效果。 |
| core | 已有官方下载规划、rules/libraries/assets 解析、MCMOD 资料源与 Modrinth 文件列表、校验下载及后台任务；已有启动参数、继承与 classpath 组装和文件检查；游戏执行待接入。 |
| launcher | Android JRE ZIP 校验导入、独立进程 JVM 自检、超时/崩溃提示；完整游戏启动生命周期待接入。 |
| nativebridge | C++17/JNI/CMake 已有 JNI_CreateJavaVM 和 EGL/GL4ES 自检；已有 Java main 调用入口及探针；游戏 GLFW 窗口及输入注入仍待适配。 |
| renderer | MIT GL4ES 锁定源码与默认后端、真实 Surface 自检；Zink/VirGL 为禁用的预留项。 |
| input | 已实现布局模型、JSON 存储、玻璃 Overlay、编辑/游玩调试、多触点及取消释放；真实 Surface/JNI 注入仍待接入。 |
| auth | 已有离线昵称/稳定 UUID、账户列表与选择的 JSON 持久化；设备码 OAuth、Keystore 加密、刷新、Xbox/Minecraft 授权与许可校验已实现，实际授权需配置自己的 client ID。 |
| controls | 模型、预设、校验与 default.json 持久化已先实现于 input；独立模块与导入导出待后续增量。 |

后续按键验收包含添加、拖动、缩放、改映射、透明度、删除、保存与杀进程恢复；游戏模式包含 WASD、空格、Shift、左右鼠标键和视角拖动。Overlay 优先与游戏 Surface 同 Activity，不依赖悬浮窗权限。当前 JVM 自检已有日志落盘、超时/崩溃提示；完整游戏进程的崩溃归档仍待接入。

## 分发和许可证

不附带 Minecraft jar、assets、JRE 或账号；GL4ES 按 MIT 许可随 APK 分发并保留声明。用户可创建本地离线账户，不伪造微软身份、授权或令牌。本轮没有复制任何参考启动器源码。参考项目只用于后续架构研究：FCL 的模块与输入分层、Amethyst-Android 的 JRE/启动参数、ZalithLauncher2 的 Compose 交互、PojavLauncher 的控件与 JNI 历史实现；接入前必须锁定来源和版本，读取并记录实际许可证。

根目录原本没有 LICENSE，本轮未为项目决定授权方式。Gradle Wrapper 为官方第三方构建工具，其 Apache-2.0 及附带声明已保留，详见 `THIRD_PARTY_NOTICES.md`；这些声明不代表对本项目原创代码授予 Apache-2.0。

构建依据：[AGP 8.11 兼容表](https://developer.android.com/build/releases/agp-8-11-0-release-notes)、[Kotlin Gradle 插件兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Compose 编译插件](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Gradle Wrapper 校验](https://docs.gradle.org/8.13/userguide/gradle_wrapper.html)。
