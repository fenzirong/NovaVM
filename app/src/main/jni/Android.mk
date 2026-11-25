LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := novavm
LOCAL_SRC_FILES := novavm_jni.cpp

LOCAL_CPPFLAGS  := -std=c++17 -fexceptions -frtti -Wall -Wextra -Wno-unused-parameter
LOCAL_CFLAGS    := -D_FILE_OFFSET_BITS=64
LOCAL_LDLIBS    := -landroid -llog
LOCAL_LDFLAGS   := -Wl,--build-id

include $(BUILD_SHARED_LIBRARY)
