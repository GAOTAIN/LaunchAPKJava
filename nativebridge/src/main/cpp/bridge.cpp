#include <jni.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>
#include <filesystem>
#include "jvm_state.h"

std::mutex launchapk_jvm_mutex;
bool launchapk_jvm_created = false;

namespace {
std::mutex render_mutex;
int framebuffer_width = 0;
int framebuffer_height = 0;
void framebuffer_size(int* width, int* height) { *width = framebuffer_width; *height = framebuffer_height; }
void* resolve_gles(const char* name) { return reinterpret_cast<void*>(eglGetProcAddress(name)); }
std::string utf(JNIEnv* env, jstring value) {
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (!text) return {};
    std::string result(text); env->ReleaseStringUTFChars(value, text); return result;
}
void fail(JNIEnv* env, const std::string& message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}
template<class T> T symbol(void* library, const char* name) {
    return reinterpret_cast<T>(dlsym(library, name));
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_launchapk_java_nativebridge_NativeBridge_diagnoseJvm(JNIEnv* art, jobject, jstring home_value,
    jstring jvm_value, jstring cache_value, jstring log_value) {
    std::lock_guard<std::mutex> guard(launchapk_jvm_mutex);
    if (launchapk_jvm_created) { fail(art, "A JVM has already been attempted in this process"); return nullptr; }
    launchapk_jvm_created = true;
    const auto home = utf(art, home_value), path = utf(art, jvm_value), cache = utf(art, cache_value), log = utf(art, log_value);
    int output = open(log.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (output < 0) { fail(art, "Cannot create JVM diagnostic log"); return nullptr; }
    dup2(output, STDOUT_FILENO); dup2(output, STDERR_FILENO); close(output);
    setenv("JAVA_HOME", home.c_str(), 1); setenv("TMPDIR", cache.c_str(), 1);
    // Preload companion libraries into this process's linker namespace. Desktop JREs are rejected by the importer.
    std::vector<std::string> candidates;
    for (const auto& dir : {home + "/lib", home + "/lib/aarch64", home + "/lib/amd64", home + "/jre/lib/aarch64", home + "/jre/lib/amd64"}) {
        std::error_code error;
        for (const auto& item : std::filesystem::directory_iterator(dir, error)) {
            if (item.is_regular_file() && item.path().extension() == ".so") candidates.push_back(item.path().string());
        }
    }
    for (int attempt = 0; attempt < 3; ++attempt)
        for (const auto& candidate : candidates) dlopen(candidate.c_str(), RTLD_NOW | RTLD_GLOBAL);
    void* library = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!library) { const char* error = dlerror(); fail(art, std::string("Cannot load Android libjvm: ") + (error ? error : "unknown")); return nullptr; }
    using CreateVM = jint (*)(JavaVM**, void**, void*);
    auto create = symbol<CreateVM>(library, "JNI_CreateJavaVM");
    if (!create) { fail(art, "libjvm has no JNI_CreateJavaVM"); return nullptr; }
    std::vector<std::string> options = {"-Djava.home=" + home, "-Djava.io.tmpdir=" + cache,
        "-Djava.library.path=" + home + "/lib:" + home + "/lib/aarch64:" + home + "/lib/amd64",
        "-XX:ErrorFile=" + log + ".hs_err_pid%p", "-Xms32m", "-Xmx128m"};
    std::vector<JavaVMOption> native_options;
    for (auto& option : options) native_options.push_back(JavaVMOption{option.data(), nullptr});
    JavaVMInitArgs args{}; args.version = JNI_VERSION_1_6; args.nOptions = static_cast<jint>(native_options.size());
    args.options = native_options.data(); args.ignoreUnrecognized = JNI_FALSE;
    JavaVM* vm = nullptr; JNIEnv* jvm = nullptr;
    const jint status = create(&vm, reinterpret_cast<void**>(&jvm), &args);
    if (status != JNI_OK || !vm || !jvm) { fail(art, "JNI_CreateJavaVM failed: " + std::to_string(status)); return nullptr; }
    jclass system = jvm->FindClass("java/lang/System");
    jmethodID getter = system ? jvm->GetStaticMethodID(system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;") : nullptr;
    std::string result;
    if (getter) {
        for (const auto* key : {"java.version", "java.vm.name", "os.arch"}) {
            jstring name = jvm->NewStringUTF(key);
            auto value = static_cast<jstring>(jvm->CallStaticObjectMethod(system, getter, name));
            if (jvm->ExceptionCheck()) break;
            result += std::string(key) + "=" + (value ? utf(jvm, value) : "unknown") + "\n";
            jvm->DeleteLocalRef(name); if (value) jvm->DeleteLocalRef(value);
        }
    }
    if (jvm->ExceptionCheck() || result.empty()) {
        jvm->ExceptionDescribe(); jvm->ExceptionClear(); fail(art, "JVM started but System properties failed; inspect diagnostic log"); return nullptr;
    }
    // Do not unload a live JVM; the bound service exits its dedicated process after replying.
    return art->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_launchapk_java_nativebridge_NativeBridge_renderProbe(JNIEnv* env, jobject, jobject surface, jstring library_value) {
    std::lock_guard<std::mutex> guard(render_mutex);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) { fail(env, "Surface unavailable"); return nullptr; }
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLContext context = EGL_NO_CONTEXT; EGLSurface target = EGL_NO_SURFACE;
    std::string result; std::string error;
    void* gl_library = nullptr;
    void (*close_gl4es)() = nullptr;
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) error = "EGL initialization failed";
    EGLConfig config = nullptr; EGLint count = 0;
    const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    if (error.empty() && (!eglChooseConfig(display, attributes, &config, 1, &count) || !count)) error = "No EGL ES2 window config";
    if (error.empty()) {
        EGLint format = 0; eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &format);
        ANativeWindow_setBuffersGeometry(window, 0, 0, format);
        const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
        target = eglCreateWindowSurface(display, config, window, nullptr);
        if (context == EGL_NO_CONTEXT || target == EGL_NO_SURFACE || !eglMakeCurrent(display, target, target, context)) error = "EGL context/window creation failed";
    }
    if (error.empty()) {
        setenv("LIBGL_ES", "2", 1); setenv("LIBGL_NOBANNER", "1", 1); setenv("LIBGL_NOPSA", "1", 1);
        void* library = dlopen(utf(env, library_value).c_str(), RTLD_NOW | RTLD_LOCAL);
        gl_library = library;
        if (!library) error = "Cannot load bundled GL4ES";
        else {
            using Init = void (*)(); using ClearColor = void (*)(float,float,float,float);
            using Clear = void (*)(unsigned); using Begin = void (*)(unsigned); using Vertex = void (*)(float,float);
            using End = void (*)(); using String = const unsigned char* (*)(unsigned);
            using Viewport = void (*)(int,int,int,int);
            using Error = unsigned (*)();
            using SetProc = void (*)(void* (*)(const char*));
            using SetSize = void (*)(void (*)(int*,int*));
            auto init = symbol<Init>(library, "initialize_gl4es");
            auto clear_color = symbol<ClearColor>(library, "glClearColor"); auto clear = symbol<Clear>(library, "glClear");
            auto begin = symbol<Begin>(library, "glBegin"); auto vertex = symbol<Vertex>(library, "glVertex2f");
            auto end = symbol<End>(library, "glEnd"); auto color = symbol<ClearColor>(library, "glColor4f");
            auto get_string = symbol<String>(library, "glGetString");
            auto viewport = symbol<Viewport>(library, "glViewport");
            auto gl_error = symbol<Error>(library, "glGetError");
            auto set_proc = symbol<SetProc>(library, "set_getprocaddress");
            auto set_size = symbol<SetSize>(library, "set_getmainfbsize");
            close_gl4es = symbol<Init>(library, "close_gl4es");
            if (!init || !clear_color || !clear || !begin || !vertex || !end || !color || !get_string || !viewport || !gl_error || !close_gl4es || !set_proc || !set_size) error = "GL4ES entry points missing";
            else {
                // NOEGL makes GL4ES inspect the current context instead of terminating a shared Android EGL display.
                framebuffer_width = ANativeWindow_getWidth(window); framebuffer_height = ANativeWindow_getHeight(window);
                set_proc(resolve_gles); set_size(framebuffer_size);
                init();
                eglMakeCurrent(display, target, target, context);
                viewport(0, 0, ANativeWindow_getWidth(window), ANativeWindow_getHeight(window));
                clear_color(0.07f, 0.08f, 0.10f, 1.f); clear(0x00004000);
                begin(0x0004); color(0.9f,0.9f,0.95f,1.f); vertex(-0.65f,-0.6f);
                color(0.04f,0.52f,1.f,1.f); vertex(0.65f,-0.6f); color(1.f,1.f,1.f,1.f); vertex(0.f,0.65f); end();
                auto version = get_string(0x1F02); auto renderer = get_string(0x1F01);
                result = "GL4ES: " + std::string(version ? reinterpret_cast<const char*>(version) : "unknown") + "\nGPU: " +
                    std::string(renderer ? reinterpret_cast<const char*>(renderer) : "unknown");
                const auto code = gl_error();
                if (code != 0) error = "GL4ES returned GL error " + std::to_string(code);
                else if (!eglSwapBuffers(display, target)) error = "EGL buffer swap failed";
                close_gl4es();
            }
        }
    }
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (target != EGL_NO_SURFACE) eglDestroySurface(display, target);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        // Do not eglTerminate a display that the Compose renderer may also be using.
    }
    ANativeWindow_release(window);
    if (gl_library) dlclose(gl_library);
    if (!error.empty()) { fail(env, error); return nullptr; }
    return env->NewStringUTF(result.c_str());
}
