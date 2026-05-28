#ifndef JCODE_STUB_MODULE
#define JCODE_STUB_MODULE "unknown"
#endif

#if defined(__GNUC__)
#define JCODE_EXPORT __attribute__((visibility("default")))
#else
#define JCODE_EXPORT
#endif

JCODE_EXPORT const char* jcode_native_module_name(void) {
    return JCODE_STUB_MODULE;
}
