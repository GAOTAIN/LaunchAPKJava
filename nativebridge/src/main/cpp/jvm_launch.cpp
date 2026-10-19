#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>
#include "jvm_state.h"

namespace {
std::string text(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
void failure(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_launchapk_java_nativebridge_NativeBridge_launchJvm(JNIEnv* art, jobject, jstring home_value,
    jstring library_value, jstring cache_value, jstring log_value, jstring directory_value,
    jobjectArray options_value, jstring main_value, jobjectArray arguments_value) {
    std::lock_guard<std::mutex> lock(launchapk_jvm_mutex);
    if (launchapk_jvm_created) { failure(art, "A JVM has already been attempted in this process"); return -1; }
    const auto home = text(art, home_value), library_path = text(art, library_value), cache = text(art, cache_value);
    const auto log = text(art, log_value), directory = text(art, directory_value), main = text(art, main_value);
    if (art->ExceptionCheck()) return -1;
    if (home.empty() || directory.empty() || main.empty() || !options_value || !arguments_value ||
        art->GetArrayLength(options_value) > 256 || art->GetArrayLength(arguments_value) > 512) {
        failure(art, "Invalid JVM main request"); return -1;
    }
    std::vector<std::string> options = {"-Djava.home=" + home, "-Djava.io.tmpdir=" + cache,
        "-Dfile.encoding=UTF-8", "-XX:ErrorFile=" + log + ".hs_err_pid%p"};
    for (jsize index = 0; index < art->GetArrayLength(options_value); ++index) {
        auto value = static_cast<jstring>(art->GetObjectArrayElement(options_value, index));
        auto option = text(art, value);
        if (value) art->DeleteLocalRef(value);
        if (art->ExceptionCheck()) return -1;
        if (option.empty() || option.size() > 65536 || option[0] != '-') { failure(art, "Invalid VM option"); return -1; }
        options.push_back(option);
    }
    // Copy UTF-16 values before creating another VM. JNI handles may never be passed between ART and HotSpot.
    std::vector<std::u16string> arguments;
    for (jsize index = 0; index < art->GetArrayLength(arguments_value); ++index) {
        auto value = static_cast<jstring>(art->GetObjectArrayElement(arguments_value, index));
        if (!value) { failure(art, "Null main argument"); return -1; }
        const jsize length = art->GetStringLength(value);
        if (length > 65536) { art->DeleteLocalRef(value); failure(art, "Main argument too long"); return -1; }
        const jchar* chars = art->GetStringChars(value, nullptr);
        if (!chars) return -1;
        arguments.emplace_back(reinterpret_cast<const char16_t*>(chars), static_cast<size_t>(length));
        art->ReleaseStringChars(value, chars); art->DeleteLocalRef(value);
    }
    int output = open(log.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (output < 0) { failure(art, "Cannot open JVM main log"); return -1; }
    if (dup2(output, STDOUT_FILENO) < 0 || dup2(output, STDERR_FILENO) < 0) {
        close(output); failure(art, "Cannot redirect JVM output"); return -1;
    }
    close(output);
    if (chdir(directory.c_str()) != 0) { failure(art, "Cannot set Android JVM working directory"); return -1; }
    setenv("JAVA_HOME", home.c_str(), 1); setenv("TMPDIR", cache.c_str(), 1);
    for (int attempt = 0; attempt < 3; ++attempt) {
        for (const auto& dir : {home + "/lib", home + "/lib/aarch64", home + "/lib/amd64", home + "/jre/lib/aarch64", home + "/jre/lib/amd64"}) {
            std::error_code error;
            for (const auto& file : std::filesystem::directory_iterator(dir, error)) {
                if (file.is_regular_file() && file.path().extension() == ".so") dlopen(file.path().c_str(), RTLD_NOW | RTLD_GLOBAL);
            }
        }
    }
    void* library = dlopen(library_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!library) { failure(art, "Cannot load Android libjvm; inspect main log"); return -1; }
    using CreateVM = jint (*)(JavaVM**, void**, void*);
    auto create = reinterpret_cast<CreateVM>(dlsym(library, "JNI_CreateJavaVM"));
    if (!create) { failure(art, "libjvm does not export JNI_CreateJavaVM"); return -1; }
    std::vector<JavaVMOption> native_options;
    for (auto& option : options) native_options.push_back(JavaVMOption{option.data(), nullptr});
    JavaVMInitArgs init{}; init.version = JNI_VERSION_1_6; init.options = native_options.data();
    init.nOptions = static_cast<jint>(native_options.size()); init.ignoreUnrecognized = JNI_FALSE;
    JavaVM* vm = nullptr; JNIEnv* jvm = nullptr;
    launchapk_jvm_created = true;
    const jint created = create(&vm, reinterpret_cast<void**>(&jvm), &init);
    if (created != JNI_OK || !vm || !jvm) { failure(art, "JNI_CreateJavaVM failed; inspect main log"); return -1; }
    jclass loader_type = jvm->FindClass("java/lang/ClassLoader");
    jclass thread_type = jvm->ExceptionCheck() ? nullptr : jvm->FindClass("java/lang/Thread");
    if (loader_type && thread_type && !jvm->ExceptionCheck()) {
        auto loader_getter = jvm->GetStaticMethodID(loader_type, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        auto thread_getter = jvm->GetStaticMethodID(thread_type, "currentThread", "()Ljava/lang/Thread;");
        auto setter = jvm->GetMethodID(thread_type, "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
        if (loader_getter && thread_getter && setter && !jvm->ExceptionCheck()) {
            auto loader = jvm->CallStaticObjectMethod(loader_type, loader_getter);
            auto thread = jvm->ExceptionCheck() ? nullptr : jvm->CallStaticObjectMethod(thread_type, thread_getter);
            if (!jvm->ExceptionCheck()) jvm->CallVoidMethod(thread, setter, loader);
        }
    }
    std::string class_name = main;
    for (auto& character : class_name) if (character == '.') character = '/';
    jclass main_type = jvm->ExceptionCheck() ? nullptr : jvm->FindClass(class_name.c_str());
    auto method = main_type ? jvm->GetStaticMethodID(main_type, "main", "([Ljava/lang/String;)V") : nullptr;
    jclass string_type = jvm->ExceptionCheck() ? nullptr : jvm->FindClass("java/lang/String");
    jobjectArray argv = string_type ? jvm->NewObjectArray(static_cast<jsize>(arguments.size()), string_type, nullptr) : nullptr;
    if (argv && method && !jvm->ExceptionCheck()) {
        for (size_t index = 0; index < arguments.size() && !jvm->ExceptionCheck(); ++index) {
            auto value = jvm->NewString(reinterpret_cast<const jchar*>(arguments[index].data()), static_cast<jsize>(arguments[index].size()));
            if (value) { jvm->SetObjectArrayElement(argv, static_cast<jsize>(index), value); jvm->DeleteLocalRef(value); }
        }
        if (!jvm->ExceptionCheck()) jvm->CallStaticVoidMethod(main_type, method, argv);
    }
    if (jvm->ExceptionCheck() || !method || !argv) {
        jvm->ExceptionDescribe(); jvm->ExceptionClear(); failure(art, "Java main failed; inspect main log"); return -1;
    }
    const auto status = vm->DestroyJavaVM();
    if (status != JNI_OK) { failure(art, "JVM main returned but VM shutdown failed"); return -1; }
    return 0;
}
