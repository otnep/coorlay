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

# libopus 1.5.2 (opus/README.coorlay.md), fixed point like Android's own build: the Coolray decodes and
# encodes 16-bit PCM only. ARMv7 adds the NEON intrinsics, chosen at run time from /proc/cpuinfo.
include $(CLEAR_VARS)
LOCAL_MODULE := coorlay_opus
OPUS := opus
include $(LOCAL_PATH)/$(OPUS)/celt_sources.mk
include $(LOCAL_PATH)/$(OPUS)/silk_sources.mk
include $(LOCAL_PATH)/$(OPUS)/opus_sources.mk
OPUS_C := $(CELT_SOURCES) $(SILK_SOURCES) $(SILK_SOURCES_FIXED) \
    $(filter-out src/opus_multistream% src/opus_projection% src/mapping_matrix.c,$(OPUS_SOURCES))
OPUS_CFLAGS := -DOPUS_BUILD -DFIXED_POINT -DDISABLE_FLOAT_API -DUSE_ALLOCA -DHAVE_LRINT -DHAVE_LRINTF \
    -O2 -fvisibility=hidden -Wno-unused-parameter
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    OPUS_C += $(CELT_SOURCES_ARM_RTCD) $(SILK_SOURCES_ARM_RTCD) \
        $(addsuffix .neon,$(CELT_SOURCES_ARM_NEON_INTR) $(SILK_SOURCES_ARM_NEON_INTR) \
            $(SILK_SOURCES_FIXED_ARM_NEON_INTR))
    OPUS_CFLAGS += -DOPUS_HAVE_RTCD -DOPUS_ARM_MAY_HAVE_NEON_INTR
    LOCAL_ARM_MODE := arm
endif
LOCAL_SRC_FILES := opus_jni.c $(addprefix $(OPUS)/,$(OPUS_C))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(OPUS) $(LOCAL_PATH)/$(OPUS)/include $(LOCAL_PATH)/$(OPUS)/celt $(LOCAL_PATH)/$(OPUS)/silk \
    $(LOCAL_PATH)/$(OPUS)/silk/fixed $(LOCAL_PATH)/$(OPUS)/src
LOCAL_CFLAGS := $(OPUS_CFLAGS)
include $(BUILD_SHARED_LIBRARY)
