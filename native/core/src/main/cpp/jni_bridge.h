#pragma once

#include <jni.h>
#include <string>
#include <memory>

namespace jcode {

/**
 * @brief Safe JNI bridge utilities for cross-boundary calls
 * 
 * Provides RAII wrappers for JNI operations to prevent resource leaks
 * and simplify error handling across the JNI boundary.
 */
class JniBridge {
public:
    explicit JniBridge(JNIEnv* env);
    ~JniBridge();
    
    // Prevent copying
    JniBridge(const JniBridge&) = delete;
    JniBridge& operator=(const JniBridge&) = delete;
    
    /**
     * @brief Get the JNI environment
     */
    JNIEnv* getEnv() const { return env_; }
    
    /**
     * @brief Convert JNI string to std::string
     */
    std::string jstringToString(jstring jstr);
    
    /**
     * @brief Convert std::string to JNI string
     */
    jstring stringToJstring(const std::string& str);
    
    /**
     * @brief Check if a JNI exception occurred and clear it
     * @return true if an exception was pending
     */
    bool checkAndClearException();
    
    /**
     * @brief Throw a Java exception from C++
     */
    void throwException(const std::string& message, const std::string& className = "java/lang/RuntimeException");
    
    /**
     * @brief Get a global reference to a Java object
     */
    jobject createGlobalRef(jobject localRef);
    
    /**
     * @brief Delete a global reference
     */
    void deleteGlobalRef(jobject globalRef);
    
    /**
     * @brief Call a Java method that returns void
     */
    void callVoidMethod(jobject obj, const char* methodName, const char* signature, ...);
    
    /**
     * @brief Call a Java method that returns an object
     */
    jobject callObjectMethod(jobject obj, const char* methodName, const char* signature, ...);
    
    /**
     * @brief Call a Java method that returns an int
     */
    jint callIntMethod(jobject obj, const char* methodName, const char* signature, ...);
    
    /**
     * @brief Call a Java method that returns a boolean
     */
    jboolean callBooleanMethod(jobject obj, const char* methodName, const char* signature, ...);

private:
    JNIEnv* env_;
};

/**
 * @brief RAII wrapper for JNI local references
 */
template<typename T>
class JniLocalRef {
public:
    JniLocalRef(JNIEnv* env, T ref) : env_(env), ref_(ref) {}
    
    ~JniLocalRef() {
        if (ref_ && env_) {
            env_->DeleteLocalRef(ref_);
        }
    }
    
    T get() const { return ref_; }
    T release() { 
        T temp = ref_; 
        ref_ = nullptr; 
        return temp; 
    }
    
    // Prevent copying
    JniLocalRef(const JniLocalRef&) = delete;
    JniLocalRef& operator=(const JniLocalRef&) = delete;
    
private:
    JNIEnv* env_;
    T ref_;
};

/**
 * @brief RAII wrapper for JNI global references
 */
template<typename T>
class JniGlobalRef {
public:
    JniGlobalRef(JNIEnv* env, T localRef) : env_(env), ref_(nullptr) {
        if (localRef) {
            ref_ = static_cast<T>(env->NewGlobalRef(localRef));
        }
    }
    
    ~JniGlobalRef() {
        if (ref_ && env_) {
            env_->DeleteGlobalRef(ref_);
        }
    }
    
    T get() const { return ref_; }
    
    // Prevent copying
    JniGlobalRef(const JniGlobalRef&) = delete;
    JniGlobalRef& operator=(const JniGlobalRef&) = delete;
    
private:
    JNIEnv* env_;
    T ref_;
};

} // namespace jcode
