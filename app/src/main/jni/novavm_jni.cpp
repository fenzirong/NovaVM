// NovaVM native engine (Batch 1)
// JNI surface paired with com.novavm.app.engine.NativeEngine.
//
// Provides host capability detection, a direct Surface framebuffer blitter,
// disk-image header probing/creation and process signalling. No QEMU code is
// linked here; the guest hypervisor binary is shipped separately and launched
// through QemuBackend.

#include <jni.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <fcntl.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG "NovaVM-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kQcow2Magic = 0x514649fbU;  // "QFI\xfb"
constexpr size_t kQcow2HeaderProbeSize = 64;

// QCOW2 versions in the wild are 2 and 3.  A header claiming anything far
// beyond that is corrupt, and the value is decoded into a Kotlin `Int` later,
// so it must never surface as an arbitrary u32.
constexpr uint32_t kQcow2MaxKnownVersion = 8;

// 64 TiB ceiling for a header-reported virtual size.  Real images are far
// smaller; clamping keeps the emitted JSON inside Long range so the Kotlin
// decoder cannot reject the whole probe result.
constexpr uint64_t kMaxSaneVirtualSizeBytes = 64ULL * 1024ULL * 1024ULL * 1024ULL * 1024ULL;

// 1 TiB expressed in MiB: the largest raw image this entry point will create.
constexpr jlong kMaxRawImageSizeMb = 1024LL * 1024LL;

constexpr int kMaxSignalNumber = 64;

uint32_t readBe32(const unsigned char* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

uint64_t readBe64(const unsigned char* p) {
    uint64_t hi = readBe32(p);
    uint64_t lo = readBe32(p + 4);
    return (hi << 32) | lo;
}

bool pathExists(const char* path) {
    struct stat st {};
    return ::stat(path, &st) == 0;
}

bool pathIsAccessible(const char* path) {
    return ::access(path, R_OK | W_OK) == 0;
}

// MemTotal is reported in kB on the first "/proc/meminfo" line.
long readTotalRamMb() {
    FILE* fp = ::fopen("/proc/meminfo", "r");
    if (fp == nullptr) {
        return -1;
    }
    char line[256];
    long totalKb = -1;
    if (::fgets(line, sizeof(line), fp) != nullptr) {
        // Format: "MemTotal:       16316312 kB"
        long value = 0;
        if (::sscanf(line, "MemTotal: %ld kB", &value) == 1) {
            totalKb = value;
        }
    }
    ::fclose(fp);
    if (totalKb <= 0) {
        return -1;
    }
    return totalKb / 1024;
}

const char* primaryAbi() {
#if defined(__aarch64__) || defined(__arm64__)
    return "arm64-v8a";
#elif defined(__x86_64__) || defined(__amd64__)
    return "x86_64";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

std::string buildCapabilitiesJson() {
    const bool kvm = pathIsAccessible("/dev/kvm");
    const bool vsock = pathIsAccessible("/dev/vhost-vsock") || pathExists("/sys/module/vhost_vsock");
    const bool avf = kvm || pathExists("/sys/firmware/devicetree");

    long cores = ::sysconf(_SC_NPROCESSORS_ONLN);
    if (cores <= 0) {
        cores = 1;
    }
    long ramMb = readTotalRamMb();

    std::string json = "{";
    json += "\"kvmAvailable\":" + std::string(kvm ? "true" : "false");
    json += ",\"vhostVsockAvailable\":" + std::string(vsock ? "true" : "false");
    json += ",\"avfSupported\":" + std::string(avf ? "true" : "false");
    json += ",\"cpuCores\":" + std::to_string(cores);
    json += ",\"totalRamMb\":" + std::to_string(ramMb);
    json += ",\"primaryAbi\":\"" + std::string(primaryAbi()) + "\"";
    json += ",\"qemuBinaryPresent\":" +
            std::string((pathExists("/system/bin/qemu-system-aarch64") ||
                         pathExists("/system/bin/qemu-system-x86_64")) ? "true" : "false");
    json += "}";
    return json;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_novavm_app_engine_NativeEngine_nativeDetectCapabilities(JNIEnv* env, jobject /*thiz*/) {
    return toJString(env, buildCapabilitiesJson());
}

JNIEXPORT jboolean JNICALL
Java_com_novavm_app_engine_NativeEngine_nativeBlitFramebuffer(JNIEnv* env, jobject /*thiz*/,
                                                             jobject surface, jobject buffer,
                                                             jint width, jint height) {
    if (surface == nullptr || buffer == nullptr || width <= 0 || height <= 0) {
        LOGW("nativeBlitFramebuffer: invalid arguments (w=%d h=%d)", width, height);
        return JNI_FALSE;
    }

    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (src == nullptr || capacity <= 0) {
        LOGE("nativeBlitFramebuffer: buffer is not a direct ByteBuffer");
        return JNI_FALSE;
    }

    // width/height are validated positive above, but width * height * 4 can
    // still overflow the 64-bit size_t for absurd geometries.  Validate the
    // frame size in 64-bit math before it is used as a byte count.
    const uint64_t width64 = static_cast<uint64_t>(width);
    const uint64_t height64 = static_cast<uint64_t>(height);
    const uint64_t kMaxPixelBytes = static_cast<uint64_t>(INT64_MAX);
    if (height64 != 0 && width64 > (kMaxPixelBytes / 4ULL) / height64) {
        LOGE("nativeBlitFramebuffer: frame size overflows (w=%d h=%d)", width, height);
        return JNI_FALSE;
    }
    const uint64_t required64 = width64 * 4ULL * height64;
    if (required64 > kMaxPixelBytes) {
        LOGE("nativeBlitFramebuffer: frame too large (%llu bytes)",
             static_cast<unsigned long long>(required64));
        return JNI_FALSE;
    }
    if (static_cast<uint64_t>(capacity) < required64) {
        LOGE("nativeBlitFramebuffer: buffer too small (need %llu, have %lld)",
             static_cast<unsigned long long>(required64),
             static_cast<long long>(capacity));
        return JNI_FALSE;
    }

    const size_t rowBytes = static_cast<size_t>(width64) * 4u;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("nativeBlitFramebuffer: ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }

    if (ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888) != 0) {
        LOGE("nativeBlitFramebuffer: setBuffersGeometry failed (errno=%d)", errno);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    ANativeWindow_Buffer outBuffer {};
    bool posted = false;
    if (ANativeWindow_lock(window, &outBuffer, nullptr) == 0) {
        // From here on every exit path must unlock: a locked buffer that is
        // never unlocked permanently starves the surface's buffer queue.
        auto* dst = static_cast<uint8_t*>(outBuffer.bits);
        const int32_t outStride = outBuffer.stride;
        if (dst == nullptr) {
            LOGE("nativeBlitFramebuffer: locked buffer has no bits");
            ANativeWindow_unlockAndPost(window);
        } else if (outStride <= 0 || outBuffer.width <= 0 || outBuffer.height <= 0) {
            LOGE("nativeBlitFramebuffer: locked buffer has an invalid geometry "
                 "(w=%d h=%d stride=%d)",
                 outBuffer.width, outBuffer.height, outStride);
            ANativeWindow_unlockAndPost(window);
        } else {
            const size_t dstStride = static_cast<size_t>(outStride) * 4u;
            const size_t copyRows = static_cast<size_t>(outBuffer.height) <
                                            static_cast<size_t>(height)
                                        ? static_cast<size_t>(outBuffer.height)
                                        : static_cast<size_t>(height);
            const size_t copyBytes = rowBytes < dstStride ? rowBytes : dstStride;
            for (size_t row = 0; row < copyRows; ++row) {
                std::memcpy(dst + row * dstStride, src + row * rowBytes, copyBytes);
            }
            posted = ANativeWindow_unlockAndPost(window) == 0;
            if (!posted) {
                LOGE("nativeBlitFramebuffer: unlockAndPost failed");
            }
        }
    } else {
        LOGE("nativeBlitFramebuffer: ANativeWindow_lock failed");
    }

    ANativeWindow_release(window);
    return posted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_novavm_app_engine_NativeEngine_nativeProbeDiskImage(JNIEnv* env, jobject /*thiz*/,
                                                            jstring path) {
    if (path == nullptr) {
        return toJString(env, "{\"format\":\"missing\",\"version\":0,\"virtualSizeBytes\":0,"
                              "\"actualSizeBytes\":0}");
    }

    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    if (rawPath == nullptr) {
        return toJString(env, "{\"format\":\"missing\",\"version\":0,\"virtualSizeBytes\":0,"
                              "\"actualSizeBytes\":0}");
    }

    std::string json;
    // O_NONBLOCK keeps a FIFO/socket path from blocking the open and the probe
    // read; the fstat below then rejects anything that is not a regular file.
    int fd = ::open(rawPath, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        json = "{\"format\":\"missing\",\"version\":0,\"virtualSizeBytes\":0,"
               "\"actualSizeBytes\":0}";
        env->ReleaseStringUTFChars(path, rawPath);
        return toJString(env, json);
    }

    struct stat st {};
    if (::fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        // A directory, FIFO, socket or device is never a disk image; reading it
        // would either block or produce garbage.
        ::close(fd);
        json = "{\"format\":\"missing\",\"version\":0,\"virtualSizeBytes\":0,"
               "\"actualSizeBytes\":0}";
        env->ReleaseStringUTFChars(path, rawPath);
        return toJString(env, json);
    }

    unsigned char header[kQcow2HeaderProbeSize] = {0};
    size_t filled = 0;
    while (filled < sizeof(header)) {
        const ssize_t got = ::read(fd, header + filled, sizeof(header) - filled);
        if (got > 0) {
            filled += static_cast<size_t>(got);
            continue;
        }
        if (got == 0) break;                    // short header at EOF
        if (errno == EINTR) continue;           // retry an interrupted read
        filled = 0;                             // unreadable: treat as raw
        break;
    }
    ::close(fd);

    const int64_t actualSize = st.st_size;
    env->ReleaseStringUTFChars(path, rawPath);

    const ssize_t readBytes = static_cast<ssize_t>(filled);

    std::string format = "raw";
    uint32_t version = 0;
    uint64_t virtualSize = 0;

    if (readBytes >= 8 && readBe32(header) == kQcow2Magic) {
        format = "qcow2";
        const uint32_t headerVersion = readBe32(header + 4);
        // Corrupt/fuzzed headers can claim version 4000000000; report 0 so the
        // Kotlin Int field stays decodable and the caller sees "unknown".
        version = headerVersion <= kQcow2MaxKnownVersion ? headerVersion : 0;
        if (readBytes >= 32 && headerVersion >= 2) {
            const uint64_t reported = readBe64(header + 24);
            // virtualSize is attacker-controlled: clamp it before it becomes a
            // JSON number that a Long cannot hold.
            if (reported > 0 && reported <= kMaxSaneVirtualSizeBytes) {
                virtualSize = reported;
            }
        }
        if (virtualSize == 0 && actualSize > 0) {
            virtualSize = static_cast<uint64_t>(actualSize);
        }
    } else if (actualSize > 0) {
        virtualSize = static_cast<uint64_t>(actualSize);
    } else {
        format = "missing";
    }

    json = "{\"format\":\"" + format + "\",\"version\":" + std::to_string(version) +
           ",\"virtualSizeBytes\":" + std::to_string(virtualSize) +
           ",\"actualSizeBytes\":" + std::to_string(actualSize < 0 ? 0 : actualSize) + "}";

    return toJString(env, json);
}

JNIEXPORT jboolean JNICALL
Java_com_novavm_app_engine_NativeEngine_nativeCreateEmptyRawImage(JNIEnv* env, jobject /*thiz*/,
                                                                 jstring path, jlong sizeMb) {
    if (path == nullptr || sizeMb <= 0) {
        return JNI_FALSE;
    }
    // sizeMb * 1024 * 1024 overflows int64 for large values, which used to turn
    // a huge request into a tiny (or negative) ftruncate length.
    if (sizeMb > kMaxRawImageSizeMb) {
        LOGE("nativeCreateEmptyRawImage: size %lld MiB exceeds the %lld MiB ceiling",
             static_cast<long long>(sizeMb), static_cast<long long>(kMaxRawImageSizeMb));
        return JNI_FALSE;
    }
    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    if (rawPath == nullptr) {
        return JNI_FALSE;
    }

    const bool existedBefore = pathExists(rawPath);
    const int fd = ::open(rawPath, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) {
        LOGE("nativeCreateEmptyRawImage: open failed for %s (errno=%d)", rawPath, errno);
        env->ReleaseStringUTFChars(path, rawPath);
        return JNI_FALSE;
    }

    const int64_t sizeBytes = static_cast<int64_t>(sizeMb) * 1024LL * 1024LL;
    const bool ok = ::ftruncate(fd, static_cast<off_t>(sizeBytes)) == 0;
    bool failedFileIsBroken = false;
    if (!ok) {
        const int savedErrno = errno;
        LOGE("nativeCreateEmptyRawImage: ftruncate failed (errno=%d)", savedErrno);
        // Do not leave a truncated/zero-length image behind when this call also
        // created the file: callers treat a `false` result as "no image".
        failedFileIsBroken = !existedBefore;
    }
    ::close(fd);

    if (failedFileIsBroken) {
        ::unlink(rawPath);
    }
    env->ReleaseStringUTFChars(path, rawPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novavm_app_engine_NativeEngine_nativeSendSignal(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jint pid, jint signalNumber) {
    if (pid <= 0) {
        return JNI_FALSE;
    }
    // `kill` accepts out-of-range signal numbers on some kernels and rejects
    // them on others; bound them here so callers get a stable answer.
    if (signalNumber < 1 || signalNumber > kMaxSignalNumber) {
        LOGW("nativeSendSignal: refusing out-of-range signal %d", signalNumber);
        return JNI_FALSE;
    }
    return ::kill(static_cast<pid_t>(pid), signalNumber) == 0 ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
