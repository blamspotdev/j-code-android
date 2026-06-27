#include "jni_bridge.h"
#include <cstdarg>
#include <android/log.h>

#define LOG_TAG "JCodeCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace jcode {

JniBridge::JniBridge(JNIEnv* env) : env_(env) {}

JniBridge::~JniBridge() {
    // Ensure no pending exceptions
    if (env_->ExceptionCheck()) {
        env_->ExceptionDescribe();
        env_->ExceptionClear();
    }
}

std::string JniBridge::jstringToString(jstring jstr) {
    if (!jstr) {
        return "";
    }
    
    const char* chars = env_->GetStringUTFChars(jstr, nullptr);
    if (!chars) {
        return "";
    }
    
    std::string result(chars);
    env_->ReleaseStringUTFChars(jstr, chars);
    return result;
}

jstring JniBridge::stringToJstring(const std::string& str) {
    return env_->NewStringUTF(str.c_str());
}

bool JniBridge::checkAndClearException() {
    if (env_->ExceptionCheck()) {
        env_->ExceptionDescribe();
        env_->ExceptionClear();
        return true;
    }
    return false;
}

void JniBridge::throwException(const std::string& message, const std::string& className) {
    jclass exceptionClass = env_->FindClass(className.c_str());
    if (exceptionClass) {
        env_->ThrowNew(exceptionClass, message.c_str());
        env_->DeleteLocalRef(exceptionClass);
    } else {
        LOGE("Failed to find exception class: %s", className.c_str());
    }
}

jobject JniBridge::createGlobalRef(jobject localRef) {
    if (!localRef) {
        return nullptr;
    }
    return env_->NewGlobalRef(localRef);
}

void JniBridge::deleteGlobalRef(jobject globalRef) {
    if (globalRef) {
        env_->DeleteGlobalRef(globalRef);
    }
}

void JniBridge::callVoidMethod(jobject obj, const char* methodName, const char* signature, ...) {
    jclass clazz = env_->GetObjectClass(obj);
    if (!clazz) {
        LOGE("Failed to get object class for method: %s", methodName);
        return;
    }
    
    jmethodID methodId = env_->GetMethodID(clazz, methodName, signature);
    if (!methodId) {
        LOGE("Failed to find method: %s with signature: %s", methodName, signature);
        env_->DeleteLocalRef(clazz);
        return;
    }
    
    va_list args;
    va_start(args, signature);
    env_->CallVoidMethodV(obj, methodId, args);
    va_end(args);
    
    checkAndClearException();
    env_->DeleteLocalRef(clazz);
}

jobject JniBridge::callObjectMethod(jobject obj, const char* methodName, const char* signature, ...) {
    jclass clazz = env_->GetObjectClass(obj);
    if (!clazz) {
        LOGE("Failed to get object class for method: %s", methodName);
        return nullptr;
    }
    
    jmethodID methodId = env_->GetMethodID(clazz, methodName, signature);
    if (!methodId) {
        LOGE("Failed to find method: %s with signature: %s", methodName, signature);
        env_->DeleteLocalRef(clazz);
        return nullptr;
    }
    
    va_list args;
    va_start(args, signature);
    jobject result = env_->CallObjectMethodV(obj, methodId, args);
    va_end(args);
    
    checkAndClearException();
    env_->DeleteLocalRef(clazz);
    
    return result;
}

jint JniBridge::callIntMethod(jobject obj, const char* methodName, const char* signature, ...) {
    jclass clazz = env_->GetObjectClass(obj);
    if (!clazz) {
        LOGE("Failed to get object class for method: %s", methodName);
        return 0;
    }
    
    jmethodID methodId = env_->GetMethodID(clazz, methodName, signature);
    if (!methodId) {
        LOGE("Failed to find method: %s with signature: %s", methodName, signature);
        env_->DeleteLocalRef(clazz);
        return 0;
    }
    
    va_list args;
    va_start(args, signature);
    jint result = env_->CallIntMethodV(obj, methodId, args);
    va_end(args);
    
    checkAndClearException();
    env_->DeleteLocalRef(clazz);
    
    return result;
}

jboolean JniBridge::callBooleanMethod(jobject obj, const char* methodName, const char* signature, ...) {
    jclass clazz = env_->GetObjectClass(obj);
    if (!clazz) {
        LOGE("Failed to get object class for method: %s", methodName);
        return JNI_FALSE;
    }
    
    jmethodID methodId = env_->GetMethodID(clazz, methodName, signature);
    if (!methodId) {
        LOGE("Failed to find method: %s with signature: %s", methodName, signature);
        env_->DeleteLocalRef(clazz);
        return JNI_FALSE;
    }
    
    va_list args;
    va_start(args, signature);
    jboolean result = env_->CallBooleanMethodV(obj, methodId, args);
    va_end(args);
    
    checkAndClearException();
    env_->DeleteLocalRef(clazz);
    
    return result;
}

} // namespace jcode
