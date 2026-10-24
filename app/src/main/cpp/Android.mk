LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := actiongrid_native
LOCAL_SRC_FILES := native_init.cpp
LOCAL_CPPFLAGS += -DACTIONGRID_VERSION=\"$(ACTIONGRID_VERSION)\"
LOCAL_LDLIBS := -llog -lc++
include $(BUILD_SHARED_LIBRARY)
