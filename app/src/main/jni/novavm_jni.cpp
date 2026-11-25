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

int64_t fileSize(const char* path) {
    struct stat st {};
    if (::stat(path, &st) != 0) {
        return -1;
    }
    return static_cast<int64_t>(st.st_size);
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

    const size_t rowBytes = static_cast<size_t>(width) * 4u;
    const size_t required = rowBytes * static_cast<size_t>(height);
    if (static_cast<size_t>(capacity) < required) {
        LOGE("nativeBlitFramebuffer: buffer too small (need %zu, have %lld)", required,
             static_cast<long long>(capacity));
        return JNI_FALSE;
    }

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
        auto* dst = static_cast<uint8_t*>(outBuffer.bits);
        if (dst != nullptr) {
            const size_t dstStride = static_cast<size_t>(outBuffer.stride) * 4u;
            const size_t copyRows = static_cast<size_t>(
                    outBuffer.height < height ? outBuffer.height : height);
            const size_t copyBytes =
                    rowBytes < dstStride ? rowBytes : dstStride;
            for (size_t row = 0; row < copyRows; ++row) {
                std::memcpy(dst + row * dstStride, src + row * rowBytes, copyBytes);
            }
            posted = ANativeWindow_unlockAndPost(window) == 0;
            if (!posted) {
                LOGE("nativeBlitFramebuffer: unlockAndPost failed");
            }
        } else {
            LOGE("nativeBlitFramebuffer: locked buffer has no bits");
            ANativeWindow_unlockAndPost(window);
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
    int fd = ::open(rawPath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        json = "{\"format\":\"missing\",\"version\":0,\"virtualSizeBytes\":0,"
               "\"actualSizeBytes\":0}";
        env->ReleaseStringUTFChars(path, rawPath);
        return toJString(env, json);
    }

    unsigned char header[kQcow2HeaderProbeSize] = {0};
    const ssize_t readBytes = ::read(fd, header, sizeof(header));
    ::close(fd);

    const int64_t actualSize = fileSize(rawPath);
    env->ReleaseStringUTFChars(path, rawPath);

    std::string format = "raw";
    uint32_t version = 0;
    uint64_t virtualSize = 0;

    if (readBytes >= 8 && readBe32(header) == kQcow2Magic) {
        format = "qcow2";
        version = readBe32(header + 4);
        if (readBytes >= 32 && version >= 2) {
            virtualSize = readBe64(header + 24);
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
    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    if (rawPath == nullptr) {
        return JNI_FALSE;
    }

    const int fd = ::open(rawPath, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) {
        LOGE("nativeCreateEmptyRawImage: open failed for %s (errno=%d)", rawPath, errno);
        env->ReleaseStringUTFChars(path, rawPath);
        return JNI_FALSE;
    }

    const int64_t sizeBytes = static_cast<int64_t>(sizeMb) * 1024LL * 1024LL;
    const bool ok = ::ftruncate(fd, static_cast<off_t>(sizeBytes)) == 0;
    if (!ok) {
        LOGE("nativeCreateEmptyRawImage: ftruncate failed (errno=%d)", errno);
    }
    ::close(fd);
    env->ReleaseStringUTFChars(path, rawPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_novavm_app_engine_NativeEngine_nativeSendSignal(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jint pid, jint signalNumber) {
    if (pid <= 0) {
        return JNI_FALSE;
    }
    return ::kill(static_cast<pid_t>(pid), signalNumber) == 0 ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
