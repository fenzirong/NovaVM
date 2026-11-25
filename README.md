# NovaVM

**NovaVM** 是一个开源架构的、类似 VMOS Pro 的 Android 全系统虚拟机宿主。它把完整的
Android / Linux 客户机系统跑在手机本地沙箱里，界面、文档与默认文案均为**简体中文**。

设计上参考了两条技术路线：

- [Vectras VM Android](https://github.com/vectras-vm/vectras-vm-android) —— 在 Android 上
  以 QEMU 承载完整客户机、并提供 VNC/RFB 显示与共享目录的成熟做法；
- Android Virtualization Framework（AVF）与 pKVM —— Android 13+ 官方提供的受保护虚拟机
  框架，可在支持设备上以硬件虚拟化方式运行客户机。

## 核心特性

- **QEMU / AVF 双后端**：自动探测宿主能力（KVM、vhost-vsock、AVF、核心数、内存、主 ABI），
  在 QEMU（TCG 软件模拟或 KVM 加速）与 AVF/pKVM 之间择优选择，并可手动指定。
- **C++17 / JNI 原生渲染**：`libnovavm.so` 负责能力探测、RGBA 帧缓冲直传 `ANativeWindow`、
  QCOW2 头部解析、稀疏镜像创建与客户机进程信号控制；Kotlin 侧全部提供 JVM 回退实现，
  因此仓库层与引擎层可在桌面端直接跑单元测试。
- **多实例与快照**：任意数量的虚拟机实例并行管理，支持克隆、QMP `savevm` / `loadvm` 快照
  创建与回滚，以及每个实例独立的启动参数、日志与共享目录。
- **全屏 + PiP 画中画 + 悬浮窗/气泡**：全屏 `SurfaceView` 显示、系统画中画、可拖拽可缩放的
  `TYPE_APPLICATION_OVERLAY` 悬浮窗，以及收起为悬浮球的迷你模式。
- **虚拟硬件与机型伪装**：CPU 核心、内存、磁盘、分辨率、DPI、刷新率、GPU 模式（VirtIO-GPU /
  ramfb / 软件渲染）全部可调；品牌、厂商、型号、设备代号、主板、IMEI、Android ID、WiFi MAC、
  Build Fingerprint 可一键随机或按预设伪装，并写入 `resetprop` 与 QEMU SMBIOS。
- **VirtIO-9p 文件/APK 互传**：宿主与客户机通过 9p 共享目录交换文件，APK 单独暂存并自动生成
  `install_apks.sh` 与 `adb connect` / `adb install` 命令。
- **Root / Xposed 启动注入**：Magisk / `su`、LSPosed 框架、SELinux Permissive、MagiskHide
  开关，配合 `novavm_boot.sh` 在客户机启动阶段注入；支持导入 Magisk / LSPosed 模块 zip。
- **虚拟定位（Mock GPS）**：内置城市预设、坐标雷达、实时摇杆与步进定位，输出标准
  **NMEA-0183** 报文（GPGGA / GPRC / GPGSA 等）与 `apply_location.sh`，可直接通过
  `adb emu geo fix` 注入客户机。

## 模块结构

```
app/src/main/jni/                 Android.mk、Application.mk、novavm_jni.cpp（C++17）
app/src/main/java/com/novavm/app/
  data/                           @Serializable 数据模型、虚拟机仓库、开源镜像目录
  engine/                         NativeEngine、HostCapabilities、QEMU/AVF 后端、
                                  QMP 客户端、RFB 客户端 + 启动控制台渲染器、
                                  VmInstanceManager（实例与快照状态机）
  location/                       虚拟定位引擎、NMEA-0183 生成器、坐标数学
  root/                           resetprop / Root / Xposed / SELinux 启动脚本生成
  transfer/                       宿主 <-> 客户机共享目录与 APK 暂存
  overlay/                        全屏显示 Activity、悬浮窗 Service、渲染管线
  ui/                             Jetpack Compose 界面（首页、创建、镜像库、互传、详情）
app/src/test/java/                JVM 单元测试（不依赖 Robolectric）
```

## 本地编译与测试

环境要求：

- JDK 17
- Android SDK：`compileSdk 34`、build-tools `35.0.0`、NDK `28.2.13676358`
- Gradle 8.9（使用仓库自带的 wrapper）
- 不使用 CMake：原生层由 `ndk-build` 构建

先创建 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

然后编译并运行全部单元测试：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows（PowerShell）：

```powershell
$env:JAVA_HOME = "E:\bg\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "E:\dev\android-sdk"

.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。测试报告位于
`app/build/reports/tests/testDebugUnitTest/index.html`。

## 真机运行

1. 用 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装到 Android 8.0+ 设备。
2. 首次启动授予通知权限；使用悬浮窗功能时还需授予「显示在其他应用上层」权限。
3. 首页会显示宿主虚拟化能力（CPU 核心、内存、ABI、KVM、AVF、QEMU 就绪状态）。
4. 点击「一键创建」即可得到一台 Pixel 8 Pro 规格的默认虚拟机，或进入「创建虚拟机」
   自定义架构、后端、硬件、伪装、定位与 Root 配置。

## 导入开源 ROM 与 QEMU 运行时

- **导入镜像**：在「镜像库」中点击「导入本地镜像 (SAF)」，选择 `.qcow2` / `.img` / `.iso`
  文件并指定客户机架构（ARM64 / X86_64）。镜像会被复制到应用私有目录
  `filesDir/novavm/roms/images/`，并自动探测 QCOW2 魔数与虚拟容量。
- **内置目录**：「镜像库」同时列出可直接自由分发的开源镜像（Android-x86、BlissOS、
  LineageOS GSI、AOSP Cuttlefish、Alpine Linux、Debian GNU/Linux），附版本、体积、
  许可证与官方下载地址。
- **QEMU 运行时**：把对应 ABI 的 `qemu-system-aarch64` / `qemu-system-x86_64` 及其依赖
  放入应用沙箱后，`VmInstanceManager` 会自动拼装完整 argv 并拉起进程；若未检测到 QEMU
  二进制，应用会退回到同一 RGBA 表面上的交互式启动控制台，功能界面保持可用。
- **客户机目录约定**：镜像位于 `filesDir/novavm/vms/<vmId>/`，VirtIO-9p 共享导出为
  `shared/`，APK 暂存于 `shared/apks/`，启动日志写入 `logs/launch.log`。

## 下载

最新构建与历史版本请见
[GitHub Releases](https://github.com/fenzirong/NovaVM/releases)。

## 许可证

本项目以开源方式发布；内置镜像目录中的每一项均标注了其原始许可证，请遵循对应上游项目的
分发条款。
