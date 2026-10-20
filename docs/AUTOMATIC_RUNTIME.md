# 自动补齐 Android Java 运行环境

## 使用

- 下载所需游戏版本，首页选择本地离线档案，再进入“选择版本并启动”。启动计划解析成功后，按版本 JSON 的 Java 要求匹配已有运行时。
- 缺少 Java 8、17、21 或主版本不匹配时，点击“补齐 Java … 并启动”。界面提前显示版本、架构和下载体积；用户点击后才下载。安装成功后，仍处于前台的启动页继续启动原先选中的版本。
- 在“运行环境”页也可以单独选择 Java 8/17/21，点击“下载并安装”。无需手动输入 URL 或 SHA-256。原来的 Android JRE ZIP 导入与 Java main 自检继续保留。
- 后台任务通过 WorkManager 持久化，等待网络、显示通知和进度，支持取消；临时网络故障最多重试两次。返回页面能看到正在进行的任务；应用后台不会自动拉起游戏页面。
- 目前仅有一个 `filesDir/runtimes/current` 激活运行时。同一 Java 主版本可以复用已有手动/自动安装，切换主版本会下载安装对应版本并替换；没有同时保存三套 JRE 的管理功能。

## 固定来源与安全安装

`launcher/src/main/assets/runtime-catalog.json` 提供 6 种组合：8/17/21 × arm64-v8a/x86_64。每种包含 universal 与架构分包两段 TAR.XZ，全部固定到 FCL 提交 `cd14d179ce22a806c9eda6353836e5abfa93cf30` 的公开运行时目录。只允许该目录的 HTTPS URL；包内置大小和 SHA-256 校验值，不读取动态 latest，也不使用用户凭据访问下载源。

实际版本是 1.8.0_442、17.0.10、21.0.1，不是当前最新补丁版。SHA-256 为本项目对固定上游文件核验后锁定的值，不宣称为 OpenJDK 官方签名。后续升级需要重新审查来源、内容、版本与校验值。

下载使用现有公共 HTTPS 客户端，检查重定向和公开地址；压缩包留在 `cacheDir/runtime-downloads`，失败/取消时清理本次临时文件。解包到私有 `filesDir/runtimes/.auto-*`；支持原始 TAR 里的 `./`、相对内部符号/硬链接，将链接内容复制为普通文件，保留许可证原文，不在文件系统创建符号链接。

拒绝路径越界、重复文件、设备/FIFO、稀疏条目、越界/循环/悬空链接；限制 20,000 条目、单文件 512 MiB、总展开 2 GiB、XZ 解码内存 64 MiB。合并后检查 release 的精确 Java 版本、64 位 ELF 架构、非 glibc libjvm，以及 LICENSE/legal。

只有完整校验通过才替换 current。进程内门闩和跨进程文件锁防止两个安装器同时替换；替换前保留 `.previous-*`，中断后读取运行时时可恢复旧目录。持锁清理过期的 `.auto-*`/`.install-*` 解包暂存。异常终止可能留下缓存压缩包，缓存还受 Android 系统管理；不声称能在进程死亡瞬间执行清理。

## 许可证

运行时为第三方 Android OpenJDK 构建，不是 Oracle 的 Android 官方发行版。包内 OpenJDK GPLv2、Classpath Exception、ADDITIONAL_LICENSE_INFO、ASSEMBLY_EXCEPTION 及第三方 notices 均保留。下载直接来自上游公共目录，不在本项目 APK、源码仓库或 Release 中重新分发运行时。未来更换来源、镜像或重新分发二进制时需另行确认该产物对应源码与许可证义务。

下载/安装器是本项目原创，没有复制 FCL/Pojav 的 GPL 应用代码。TAR 解包引用 Apache Commons Compress 1.27.1（Apache-2.0），XZ 解码引用 XZ for Java 1.10（0BSD）；原 LICENSE/COPYING、NOTICE 及传递依赖声明随 APK 以 assets 提供。没有改动原有许可证或版权头。

上游产物：https://github.com/FCL-Team/FoldCraftLauncher/tree/cd14d179ce22a806c9eda6353836e5abfa93cf30/FCL/src/main/jreAssets/app_runtime/java

构建来源说明：https://github.com/FCL-Team/Android-OpenJDK-Build

## 验证与边界

单元测试覆盖分包合并、SHA-256、截断下载、架构/版本、链接安全、取消、保留旧安装、恢复中断提交与锁。`scripts/verify-runtime-downloads.py` 可在项目 `.tmp` 下载并校验 9 个唯一分包；随后运行 `gradlew.bat :launcher:verifyRuntimeDownloads`，调用实际 Kotlin 安装器安装全部 6 种组合，验证原始许可证和架构，结果写入 build-reports。

交叉构建和桌面真实包安装验证不等于手机上执行 Android JVM。无可用真机连接时，不声称通知、后台生命周期、JVM 加载或主菜单已实机通过。

4KB 内存页限制、GL4ES 的 OpenGL 2.1 能力、LWJGL 2/3.4 拒绝、Forge/NeoForge processors 和微软会话启动缺口均保持原有状态。本轮完成运行时自动获取，不代表全新手机已经可以保证玩到 Minecraft。
