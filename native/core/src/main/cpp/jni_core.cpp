#include <jni.h>
#include "jni_bridge.h"
#include "event_loop.h"
#include "resource_manager.h"
#include "logger.h"

#define LOG_TAG "JCodeCore"

extern "C" {

/**
 * @brief JNI_OnLoad - called when the native library is loaded
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    
    // Initialize core infrastructure
    jcode::Logger::init("JCodeCore");
    jcode::Logger::setMinLevel(jcode::LogLevel::DEBUG);
    jcode::initGlobalEventLoop();
    
    LOG_I(LOG_TAG, "JCode Core native library loaded successfully");
    
    return JNI_VERSION_1_6;
}

/**
 * @brief JNI_OnUnload - called when the native library is unloaded
 */
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOG_I(LOG_TAG, "JCode Core native library unloading");
    
    // Cleanup core infrastructure
    jcode::shutdownGlobalEventLoop();
    jcode::getResourceManager().cleanupAll();
}

/**
 * @brief Check if native library is available
 */
JNIEXPORT jboolean JNICALL
Java_dev_jcode_native_core_CoreNativeModule_nativeIsAvailable(JNIEnv* env, jclass clazz) {
    return JNI_TRUE;
}

} // extern "C"
