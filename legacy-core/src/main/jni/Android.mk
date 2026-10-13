LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := coorlay_legacy_usb
LOCAL_SRC_FILES := legacy_usb.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := coorlay_i2c
LOCAL_SRC_FILES := ../../../../shared/src/main/jni/linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)
