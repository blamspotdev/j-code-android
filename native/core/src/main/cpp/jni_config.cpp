#include <jni.h>
#include "jni_bridge.h"
#include "config_service.h"
#include "resource_manager.h"
#include "logger.h"

#define LOG_TAG "JCodeConfig"

using namespace jcode;

extern "C" {

/**
 * @brief Create a new ConfigService instance
 */
JNIEXPORT jlong JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeCreate(JNIEnv* env, jclass clazz) {
    auto service = std::make_shared<ConfigService>();
    return getResourceManager().registerResource<ConfigService>(service);
}

/**
 * @brief Destroy a ConfigService instance
 */
JNIEXPORT void JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeDestroy(JNIEnv* env, jclass clazz, jlong handle) {
    getResourceManager().release(handle);
}

/**
 * @brief Parse YAML from a string
 */
JNIEXPORT jboolean JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeParseYaml(
    JNIEnv* env, jclass clazz, jlong handle, jstring yamlContent)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        LOG_E(LOG_TAG, "Invalid ConfigService handle");
        return JNI_FALSE;
    }
    
    JniBridge bridge(env);
    std::string content = bridge.jstringToString(yamlContent);
    
    return service->parseYaml(content) ? JNI_TRUE : JNI_FALSE;
}

/**
 * @brief Parse YAML from a file
 */
JNIEXPORT jboolean JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeParseYamlFile(
    JNIEnv* env, jclass clazz, jlong handle, jstring filePath)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        LOG_E(LOG_TAG, "Invalid ConfigService handle");
        return JNI_FALSE;
    }
    
    JniBridge bridge(env);
    std::string path = bridge.jstringToString(filePath);
    
    return service->parseYamlFile(path) ? JNI_TRUE : JNI_FALSE;
}

/**
 * @brief Serialize to YAML string
 */
JNIEXPORT jstring JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeToYaml(JNIEnv* env, jclass clazz, jlong handle) {
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        LOG_E(LOG_TAG, "Invalid ConfigService handle");
        return env->NewStringUTF("");
    }
    
    std::string yaml = service->toYaml();
    JniBridge bridge(env);
    return bridge.stringToJstring(yaml);
}

/**
 * @brief Check if a path exists
 */
JNIEXPORT jboolean JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeHas(
    JNIEnv* env, jclass clazz, jlong handle, jstring path)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return JNI_FALSE;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    
    return service->has(pathStr) ? JNI_TRUE : JNI_FALSE;
}

/**
 * @brief Get a string value
 */
JNIEXPORT jstring JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeGetString(
    JNIEnv* env, jclass clazz, jlong handle, jstring path, jstring defaultValue)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return defaultValue;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    std::string defaultStr = bridge.jstringToString(defaultValue);
    
    ConfigValue value = service->get(pathStr);
    std::string result = value.asString(defaultStr);
    
    return bridge.stringToJstring(result);
}

/**
 * @brief Get an integer value
 */
JNIEXPORT jlong JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeGetInteger(
    JNIEnv* env, jclass clazz, jlong handle, jstring path, jlong defaultValue)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return defaultValue;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    
    ConfigValue value = service->get(pathStr);
    return value.asInteger(defaultValue);
}

/**
 * @brief Get a float value
 */
JNIEXPORT jdouble JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeGetFloat(
    JNIEnv* env, jclass clazz, jlong handle, jstring path, jdouble defaultValue)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return defaultValue;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    
    ConfigValue value = service->get(pathStr);
    return value.asFloat(defaultValue);
}

/**
 * @brief Get a boolean value
 */
JNIEXPORT jboolean JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeGetBoolean(
    JNIEnv* env, jclass clazz, jlong handle, jstring path, jboolean defaultValue)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return defaultValue;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    
    ConfigValue value = service->get(pathStr);
    return value.asBoolean(defaultValue) ? JNI_TRUE : JNI_FALSE;
}

/**
 * @brief Set a string value
 */
JNIEXPORT void JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeSetString(
    JNIEnv* env, jclass clazz, jlong handle, jstring path, jstring value)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    std::string valueStr = bridge.jstringToString(value);
    
    service->set(pathStr, ConfigValue(valueStr));
}

/**
 * @brief Set an integer value
 */
JNIEXPORT void JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeSetInteger(
    JNIEnv* env, jclass clazz, jlong handle, jstring path, jlong value)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    
    service->set(pathStr, ConfigValue(static_cast<int64_t>(value)));
}

/**
 * @brief Set a float value
 */
JNIEXPORT void JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeSetFloat(
    JNIEnv* env, jclass clazz, jlong handle, jstring path, jdouble value)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    
    service->set(pathStr, ConfigValue(static_cast<double>(value)));
}

/**
 * @brief Set a boolean value
 */
JNIEXPORT void JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeSetBoolean(
    JNIEnv* env, jclass clazz, jlong handle, jstring path, jboolean value)
{
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (!service) {
        return;
    }
    
    JniBridge bridge(env);
    std::string pathStr = bridge.jstringToString(path);
    
    service->set(pathStr, ConfigValue(value == JNI_TRUE));
}

/**
 * @brief Clear all configuration
 */
JNIEXPORT void JNICALL
Java_dev_jcode_native_core_ConfigServiceNative_nativeClear(JNIEnv* env, jclass clazz, jlong handle) {
    auto service = getResourceManager().getResource<ConfigService>(handle);
    if (service) {
        service->clear();
    }
}

} // extern "C"
