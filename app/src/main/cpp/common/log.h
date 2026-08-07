#pragma once

#include <android/log.h>

#ifndef LODESTONE_LOG_TAG
#define LODESTONE_LOG_TAG "LodestoneNative"
#endif

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LODESTONE_LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LODESTONE_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LODESTONE_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LODESTONE_LOG_TAG, __VA_ARGS__)
