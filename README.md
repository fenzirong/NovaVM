# NovaVM

An open-source VMOS Pro-style Android full-system virtual machine host.
Batch 1 delivers the engine core: the JNI capability layer, VM profile
persistence, QEMU/AVF backends, QMP and RFB clients, shared-folder transfer,
and root/Xposed boot-script generation.

## Requirements

- JDK 17
- Android SDK with `compileSdk 34`, build-tools `35.0.0`, NDK `28.2.13676358`
- Gradle 8.9 via the bundled wrapper
- No CMake: the native layer is built with `ndk-build`

Create `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

Build and test:

```bash
./gradlew :app:testDebugUnitTest :app:externalNativeBuildDebug
```

## Layout

```
app/src/main/jni/            Android.mk, Application.mk, novavm_jni.cpp
app/src/main/java/com/novavm/app/
  data/                      @Serializable models, VM/ROM repositories
  engine/                    NativeEngine, HostCapabilities, QEMU/AVF backends,
                             QMP client, RFB client + boot console renderer,
                             VmInstanceManager
  transfer/                  Host <-> guest shared-folder and APK staging
  root/                      resetprop/root/Xposed/SELinux boot scripts
app/src/test/java/           JVM unit tests (no Robolectric)
```

## Native surface (`libnovavm.so`)

| Method | Purpose |
| --- | --- |
| `nativeDetectCapabilities` | KVM, vhost-vsock, AVF, cores, RAM, primary ABI as JSON |
| `nativeBlitFramebuffer` | Upload an RGBA `ByteBuffer` to an `ANativeWindow` |
| `nativeProbeDiskImage` | QCOW2 header parse (magic/version/virtual size) or RAW size |
| `nativeCreateEmptyRawImage` | Sparse image via `ftruncate` |
| `nativeSendSignal` | `kill(pid, signal)` for guest process control |

Both `arm64-v8a` and `x86_64` ABIs are built. Every Kotlin entry point has a
JVM fallback so the repository and engine layers stay testable on a desktop.

## Batch 1 state

QEMU processes are not spawned yet. `VmInstanceManager.start` builds the full
argv, records it in `logs/launch.log`, and drives the runtime state machine;
when no `qemu-system-*` binary is present the app stays interactive through
`BootConsoleRenderer` on the same RGBA surface path.

Guest images are expected under `filesDir/vms/<vmId>/`, with the shared
VirtIO-9p export at `shared/` and `.apk` staging at `shared/apks/`.
