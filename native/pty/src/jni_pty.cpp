#include <jni.h>
#include "pty.h"
#include <android/log.h>
#include <vector>
#include <string>

#define LOG_TAG "JCodePty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace jcode;

static Pty* getPty(JNIEnv* env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    return reinterpret_cast<Pty*>(env->GetLongField(thiz, fid));
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_jcode_core_term_PtyProcess_nativeCreate(JNIEnv* env, jobject thiz,
                                                   jstring exe, jobjectArray argv,
                                                   jobjectArray envp, jstring cwd,
                                                   jint cols, jint rows) {
    const char* c_exe = env->GetStringUTFChars(exe, nullptr);
    const char* c_cwd = cwd ? env->GetStringUTFChars(cwd, nullptr) : nullptr;

    std::vector<std::string> c_argv;
    jsize argv_len = env->GetArrayLength(argv);
    for (jsize i = 0; i < argv_len; ++i) {
        jstring arg = (jstring)env->GetObjectArrayElement(argv, i);
        const char* c_arg = env->GetStringUTFChars(arg, nullptr);
        c_argv.emplace_back(c_arg);
        env->ReleaseStringUTFChars(arg, c_arg);
        env->DeleteLocalRef(arg);
    }

    std::vector<std::string> c_env;
    if (envp) {
        jsize envp_len = env->GetArrayLength(envp);
        for (jsize i = 0; i < envp_len; ++i) {
            jstring var = (jstring)env->GetObjectArrayElement(envp, i);
            const char* c_var = env->GetStringUTFChars(var, nullptr);
            c_env.emplace_back(c_var);
            env->ReleaseStringUTFChars(var, c_var);
            env->DeleteLocalRef(var);
        }
    }

    Pty* pty = new Pty();
    bool success = pty->create(c_exe, c_argv, c_env, c_cwd, cols, rows);

    env->ReleaseStringUTFChars(exe, c_exe);
    if (c_cwd) env->ReleaseStringUTFChars(cwd, c_cwd);

    if (!success) {
        LOGE("Failed to create PTY");
        delete pty;
        return 0;
    }

    LOGI("PTY created: master_fd=%d, child_pid=%d", pty->masterFd(), pty->childPid());
    return reinterpret_cast<jlong>(pty);
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_PtyProcess_nativeRead(JNIEnv* env, jobject thiz,
                                                 jbyteArray buffer, jint offset, jint length) {
    Pty* pty = getPty(env, thiz);
    if (!pty) return -1;

    jbyte* buf = env->GetByteArrayElements(buffer, nullptr);
    int n = pty->read(reinterpret_cast<uint8_t*>(buf + offset), length);
    env->ReleaseByteArrayElements(buffer, buf, 0);

    return n;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_PtyProcess_nativeWrite(JNIEnv* env, jobject thiz,
                                                  jbyteArray data, jint offset, jint length) {
    Pty* pty = getPty(env, thiz);
    if (!pty) return -1;

    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    int n = pty->write(reinterpret_cast<const uint8_t*>(buf + offset), length);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);

    return n;
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_term_PtyProcess_nativeResize(JNIEnv* env, jobject thiz,
                                                   jint cols, jint rows) {
    Pty* pty = getPty(env, thiz);
    if (!pty) return JNI_FALSE;
    return pty->resize(cols, rows) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_core_term_PtyProcess_nativeWaitForExit(JNIEnv* env, jobject thiz) {
    Pty* pty = getPty(env, thiz);
    if (!pty) return -1;
    return pty->waitForExit();
}

JNIEXPORT jboolean JNICALL
Java_dev_jcode_core_term_PtyProcess_nativeIsOpen(JNIEnv* env, jobject thiz) {
    Pty* pty = getPty(env, thiz);
    if (!pty) return JNI_FALSE;
    return pty->isOpen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_jcode_core_term_PtyProcess_nativeCloseByHandle(JNIEnv* env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    Pty* pty = reinterpret_cast<Pty*>(handle);
    pty->close();
    delete pty;
}

JNIEXPORT jint JNICALL
Java_dev_jcode_native_pty_PtyNativeModule_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("Native PTY module initialized");
    return 1;
}

}  // extern "C"
