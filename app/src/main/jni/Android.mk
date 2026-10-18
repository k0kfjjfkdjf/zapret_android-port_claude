# Builds libhev-socks5-tunnel.so (TUN <-> SOCKS5) from the submodule, with one file replaced by
# hev-patch/hev-socks5-session.c (the "@src" user name: each connection tells the engine its
# source address, for routing by app). Mirrors the submodule's own Android.mk otherwise.
JNI_PATH := $(call my-dir)
TOP_PATH := $(JNI_PATH)/hev-socks5-tunnel

include $(TOP_PATH)/third-part/yaml/Android.mk
include $(TOP_PATH)/third-part/lwip/Android.mk
include $(TOP_PATH)/third-part/hev-task-system/Android.mk

LOCAL_PATH := $(TOP_PATH)
SRCDIR := $(LOCAL_PATH)/src

include $(LOCAL_PATH)/build.mk
HEV_SOCKS5_TUNNEL_SRC := $(filter-out src/hev-socks5-session.c,$(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))) \
    ../hev-patch/hev-socks5-session.c

include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel
LOCAL_SRC_FILES := $(HEV_SOCKS5_TUNNEL_SRC)
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/src \
    $(LOCAL_PATH)/src/misc \
    $(LOCAL_PATH)/src/core/include \
    $(LOCAL_PATH)/third-part/yaml/include \
    $(LOCAL_PATH)/third-part/lwip/src/include \
    $(LOCAL_PATH)/third-part/lwip/src/ports/include \
    $(LOCAL_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY
LOCAL_CFLAGS += $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
