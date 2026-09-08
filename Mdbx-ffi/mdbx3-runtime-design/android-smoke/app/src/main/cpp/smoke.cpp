#include <dlfcn.h>
#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_monica_mdbx3_smoke_MainActivity_contractVersion(JNIEnv*, jclass) {
    void* handle = dlopen("libmdbx_ffi.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        return -1;
    }
    using ContractFunction = int32_t (*)();
    auto function = reinterpret_cast<ContractFunction>(
        dlsym(handle, "ffi_mdbx_ffi_uniffi_contract_version"));
    const jint result = function == nullptr ? -1 : static_cast<jint>(function());
    dlclose(handle);
    return result;
}
