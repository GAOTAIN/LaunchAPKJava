// Original Android implementation of the public GLFW 3.3 C API subset used by Minecraft.
// GLFW's unmodified zlib-licensed header is in third_party/glfw; no launcher code is copied.
#define GLFW_INCLUDE_NONE
#include <GLFW/glfw3.h>
#include <EGL/egl.h>
#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <unordered_set>
#include "android_glfw.h"
#include "game_events.h"

struct GLFWmonitor { int unused; };
struct GLFWcursor { int shape; };
struct GLFWwindow {
    int width = 0, height = 0, cursorMode = GLFW_CURSOR_NORMAL;
    bool focused = true, visible = true;
    double cursorX = 0, cursorY = 0;
    void* user = nullptr;
    GLFWmonitor* monitor = nullptr;
    std::array<int, GLFW_KEY_LAST + 1> keys{};
    std::array<int, GLFW_MOUSE_BUTTON_LAST + 1> buttons{};
    std::array<int, GLFW_MOUSE_BUTTON_LAST + 1> physicalButtons{};
    GLFWkeyfun key = nullptr; GLFWcharfun character = nullptr; GLFWcharmodsfun charmods = nullptr;
    GLFWmousebuttonfun mouse = nullptr; GLFWcursorposfun cursor = nullptr; GLFWcursorenterfun enter = nullptr;
    GLFWscrollfun scroll = nullptr; GLFWframebuffersizefun framebuffer = nullptr;
    GLFWwindowsizefun size = nullptr; GLFWwindowfocusfun focus = nullptr; GLFWwindowclosefun close = nullptr;
    GLFWwindowposfun pos = nullptr; GLFWwindowrefreshfun refresh = nullptr;
    GLFWwindowiconifyfun iconify = nullptr; GLFWwindowmaximizefun maximize = nullptr;
    GLFWwindowcontentscalefun scale = nullptr; GLFWdropfun drop = nullptr;
};
namespace {
launchapk::EventQueue queue;
launchapk::PointerTracker pointers;
std::unordered_set<int> touchMouseOwners;
std::mutex surfaceMutex;
ANativeWindow* androidWindow = nullptr;
std::atomic<int> screenWidth{1280}, screenHeight{720};
std::atomic<bool> requestedClose{false};
GLFWwindow* gameWindow = nullptr;
thread_local GLFWwindow* currentWindow = nullptr;
GLFWmonitor primaryMonitor{};
GLFWmonitor* monitors[] = {&primaryMonitor};
GLFWvidmode videoMode{1280,720,8,8,8,60};
GLFWerrorfun errorCallback = nullptr;
GLFWmonitorfun monitorCallback = nullptr;
GLFWjoystickfun joystickCallback = nullptr;
thread_local int lastError = GLFW_NO_ERROR;
thread_local std::string lastDescription;
std::string glPath, clipboard;
EGLDisplay display = EGL_NO_DISPLAY;
EGLSurface surface = EGL_NO_SURFACE;
EGLContext context = EGL_NO_CONTEXT;
void* glLibrary = nullptr;
bool glInitialized = false;
std::atomic<int> renderedFrames{0};
std::chrono::steady_clock::time_point epoch = std::chrono::steady_clock::now();
double timeOffset = 0;
void error(int code, const char* text) {
    lastError = code; lastDescription = text;
    __android_log_print(ANDROID_LOG_ERROR, "LaunchAPK.GLFW", "%s", text);
    fprintf(stderr, "[LaunchAPK GLFW] %s\n", text); fflush(stderr);
    if (errorCallback) errorCallback(code, text);
}
int mods(GLFWwindow* w) {
    return ((w->keys[GLFW_KEY_LEFT_SHIFT] || w->keys[GLFW_KEY_RIGHT_SHIFT]) ? GLFW_MOD_SHIFT : 0) |
        ((w->keys[GLFW_KEY_LEFT_CONTROL] || w->keys[GLFW_KEY_RIGHT_CONTROL]) ? GLFW_MOD_CONTROL : 0) |
        ((w->keys[GLFW_KEY_LEFT_ALT] || w->keys[GLFW_KEY_RIGHT_ALT]) ? GLFW_MOD_ALT : 0);
}
void releaseInput(GLFWwindow* w) {
    for (int k = 0; k <= GLFW_KEY_LAST; ++k) if (w->keys[k]) {
        w->keys[k] = 0; if (w->key) w->key(w,k,k,GLFW_RELEASE,mods(w));
    }
    for (int b = 0; b <= GLFW_MOUSE_BUTTON_LAST; ++b) if (w->buttons[b]) {
        w->buttons[b] = 0; if (w->mouse) w->mouse(w,b,GLFW_RELEASE,mods(w));
    }
    w->physicalButtons.fill(0);
    pointers.clear(); touchMouseOwners.clear();
}
void mainFramebuffer(int* width, int* height) { *width = screenWidth.load(); *height = screenHeight.load(); }
void* glesProc(const char* name) { return reinterpret_cast<void*>(eglGetProcAddress(name)); }
bool initEgl() {
    std::lock_guard<std::mutex> lock(surfaceMutex);
    if (!androidWindow || requestedClose) { error(GLFW_PLATFORM_ERROR,"Android Surface is unavailable"); return false; }
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint count = 0; EGLConfig config = nullptr;
    const EGLint attrs[] = {EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_DEPTH_SIZE,24,EGL_STENCIL_SIZE,8,EGL_NONE};
    if (display == EGL_NO_DISPLAY || !eglInitialize(display,nullptr,nullptr) ||
        !eglChooseConfig(display,attrs,&config,1,&count) || !count) {
        error(GLFW_PLATFORM_ERROR,"No EGL ES2 RGBA8/depth24/stencil8 config"); return false;
    }
    EGLint format; eglGetConfigAttrib(display,config,EGL_NATIVE_VISUAL_ID,&format);
    ANativeWindow_setBuffersGeometry(androidWindow,0,0,format);
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    eglBindAPI(EGL_OPENGL_ES_API);
    context = eglCreateContext(display,config,EGL_NO_CONTEXT,ctxAttrs);
    surface = eglCreateWindowSurface(display,config,androidWindow,nullptr);
    if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE || !eglMakeCurrent(display,surface,surface,context)) {
        error(GLFW_PLATFORM_ERROR,"Cannot connect Android Surface to EGL context"); return false;
    }
    glLibrary = dlopen(glPath.c_str(),RTLD_NOW | RTLD_GLOBAL);
    if (!glLibrary) { error(GLFW_PLATFORM_ERROR,dlerror()); return false; }
    auto setProc = reinterpret_cast<void(*)(void*(*)(const char*))>(dlsym(glLibrary,"set_getprocaddress"));
    auto setSize = reinterpret_cast<void(*)(void(*)(int*,int*))>(dlsym(glLibrary,"set_getmainfbsize"));
    auto init = reinterpret_cast<void(*)()>(dlsym(glLibrary,"initialize_gl4es"));
    if (!init || !setProc || !setSize) { error(GLFW_PLATFORM_ERROR,"GL4ES host entry points missing"); return false; }
    setProc(glesProc); setSize(mainFramebuffer); init(); glInitialized = true;
    return true;
}
void destroyEgl() {
    if (glInitialized && glLibrary) {
        auto close = reinterpret_cast<void(*)()>(dlsym(glLibrary,"close_gl4es"));
        if (close) close(); glInitialized = false;
    }
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display,surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display,context);
        // Compose shares the process's EGL display; never terminate it here.
    }
    surface = EGL_NO_SURFACE; context = EGL_NO_CONTEXT;
}
}
extern "C" {
void launchapk_window_configure(const char* path) {
    glPath = path;
    setenv("LIBGL_ES","2",1); setenv("LIBGL_NOBANNER","1",1); setenv("LIBGL_NOPSA","1",1);
    // GL4ES advertises its actual desktop feature level; don't fabricate OpenGL 3.x capabilities.
    setenv("LIBGL_GL","21",1);
}
void launchapk_window_attach(ANativeWindow* window, int width, int height) {
    { std::lock_guard<std::mutex> lock(surfaceMutex);
      if (androidWindow != window) {
          if (window) ANativeWindow_acquire(window);
          if (androidWindow) ANativeWindow_release(androidWindow);
          androidWindow = window;
      }
      screenWidth = width; screenHeight = height;
    }
    queue.push({launchapk::EventType::Size,0,0,static_cast<double>(width),static_cast<double>(height)});
}
void launchapk_window_detach() {
    { std::lock_guard<std::mutex> lock(surfaceMutex);
      requestedClose = true;
      if (androidWindow) ANativeWindow_release(androidWindow);
      androidWindow = nullptr;
    }
    queue.push({launchapk::EventType::Close});
}
void launchapk_window_close() { requestedClose = true; queue.push({launchapk::EventType::Close}); }
void launchapk_input_key(int key, bool pressed) { if (key >= 0 && key <= GLFW_KEY_LAST) queue.push({launchapk::EventType::Key,key,pressed ? GLFW_PRESS : GLFW_RELEASE}); }
void launchapk_input_mouse(int button, bool pressed) { if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST) queue.push({launchapk::EventType::Mouse,button,pressed ? GLFW_PRESS : GLFW_RELEASE}); }
void launchapk_input_pointer(int id, float x, float y, int action) { if (std::isfinite(x) && std::isfinite(y)) queue.push({launchapk::EventType::Pointer,id,action,x,y}); }
void launchapk_input_focus(bool focused) { queue.push({launchapk::EventType::Focus,0,focused ? 1 : 0}); }
void launchapk_input_scroll(float x, float y) { queue.push({launchapk::EventType::Scroll,0,0,x,y}); }
void launchapk_input_character(unsigned int cp) { if (cp > 0 && cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff)) queue.push({launchapk::EventType::Character,static_cast<int>(cp)}); }
const char* launchapk_window_status() { return renderedFrames.load() ? "GLFW / GL4ES 已交换游戏帧" : "等待游戏创建窗口并交换画面"; }

int glfwInit() { epoch = std::chrono::steady_clock::now(); return GLFW_TRUE; }
void glfwTerminate() { if (gameWindow) glfwDestroyWindow(gameWindow); }
void glfwInitHint(int, int) {}
// LWJGL 3.3.3 resolves these GLFW 3.4 entry points eagerly, even for 3.3 clients.
// This is a non-desktop platform with a host-provided EGL window (the null platform ABI).
int glfwGetPlatform() { return 0x00060005; }
int glfwPlatformSupported(int platform) { return platform == 0x00060005; }
void glfwInitAllocator(const void* allocator) {
    if(allocator)error(GLFW_PLATFORM_ERROR,"Custom GLFW allocation callbacks are not supported by the Android adapter");
}
void glfwGetVersion(int* a,int* b,int* c) { if(a)*a=3; if(b)*b=3; if(c)*c=8; }
const char* glfwGetVersionString() { return "3.3.8 LaunchAPK Android EGL/GL4ES"; }
int glfwGetError(const char** description) { int code=lastError; if(description)*description=lastDescription.c_str(); lastError=0; return code; }
GLFWerrorfun glfwSetErrorCallback(GLFWerrorfun cb) { auto old=errorCallback; errorCallback=cb; return old; }
void glfwDefaultWindowHints() {}
void glfwWindowHint(int, int) {} // Android owns window size/decorations; GL4ES owns GL profile support.
void glfwWindowHintString(int, const char*) {}
GLFWwindow* glfwCreateWindow(int,int,const char*,GLFWmonitor* monitor,GLFWwindow* share) {
    if (gameWindow || share) { error(GLFW_PLATFORM_ERROR,"Only one Android game window is supported"); return nullptr; }
    if (!initEgl()) { destroyEgl(); return nullptr; }
    gameWindow = new GLFWwindow; gameWindow->width=screenWidth; gameWindow->height=screenHeight; gameWindow->monitor=monitor;
    fprintf(stderr,"[LaunchAPK GLFW] Surface/EGL connected: %dx%d\n",gameWindow->width,gameWindow->height); fflush(stderr);
    return gameWindow;
}
void glfwDestroyWindow(GLFWwindow* w) { if(w && w==gameWindow) { releaseInput(w); destroyEgl(); if(currentWindow==w)currentWindow=nullptr; gameWindow=nullptr; delete w; } }
int glfwWindowShouldClose(GLFWwindow*) { return requestedClose ? GLFW_TRUE : GLFW_FALSE; }
void glfwSetWindowShouldClose(GLFWwindow*,int value) { requestedClose=value!=0; }
void glfwMakeContextCurrent(GLFWwindow* w) { currentWindow=w; if(display!=EGL_NO_DISPLAY) eglMakeCurrent(display,w?surface:EGL_NO_SURFACE,w?surface:EGL_NO_SURFACE,w?context:EGL_NO_CONTEXT); }
GLFWwindow* glfwGetCurrentContext() { return currentWindow; }
void glfwSwapBuffers(GLFWwindow*) {
    std::lock_guard<std::mutex> lock(surfaceMutex);
    if (!androidWindow || requestedClose) return;
    if (!eglSwapBuffers(display,surface)) { requestedClose=true; error(GLFW_PLATFORM_ERROR,"EGL swap failed: Android Surface lost"); }
    else { if (renderedFrames.fetch_add(1)==0) { fprintf(stderr,"[LaunchAPK GLFW] First frame swapped\n"); fflush(stderr); } }
}
void glfwSwapInterval(int interval) { if(display!=EGL_NO_DISPLAY) eglSwapInterval(display,std::clamp(interval,0,1)); }
GLFWglproc glfwGetProcAddress(const char* name) {
    if (!glLibrary || !name) return nullptr;
    auto proc=dlsym(glLibrary,name);
    if (!proc) {
        auto resolver=reinterpret_cast<void*(*)(const char*)>(dlsym(glLibrary,"glXGetProcAddress"));
        if(resolver)proc=resolver(name);
    }
    return reinterpret_cast<GLFWglproc>(proc);
}
int glfwExtensionSupported(const char* extension) {
    auto string=reinterpret_cast<const unsigned char*(*)(unsigned)>(glfwGetProcAddress("glGetString"));
    if(!string || !extension || !*extension || strchr(extension,' '))return GLFW_FALSE;
    auto raw=string(0x1f03); if(!raw)return GLFW_FALSE;
    std::string list=" "+std::string(reinterpret_cast<const char*>(raw))+" ";
    return list.find(" "+std::string(extension)+" ")!=std::string::npos;
}
void glfwPollEvents() {
    auto* w=gameWindow; if(!w)return;
    for(const auto& event:queue.drain()) {
        using launchapk::EventType;
        switch(event.type) {
            case EventType::Key: {
                int action=(event.action==GLFW_PRESS && w->keys[event.code])?GLFW_REPEAT:event.action;
                w->keys[event.code]=event.action; if(w->key)w->key(w,event.code,event.code,action,mods(w)); break;
            }
            case EventType::Mouse: {
                w->physicalButtons[event.code]=event.action;
                int action=(event.action==GLFW_PRESS || (event.code==0 && !touchMouseOwners.empty()))?GLFW_PRESS:GLFW_RELEASE;
                if(w->buttons[event.code]!=action) { w->buttons[event.code]=action; if(w->mouse)w->mouse(w,event.code,action,mods(w)); } break;
            }
            case EventType::Pointer: {
                auto delta=pointers.delta(event.code,event.x,event.y,event.action);
                if(w->cursorMode==GLFW_CURSOR_DISABLED) { w->cursorX+=delta.first; w->cursorY+=delta.second; }
                else { w->cursorX=event.x; w->cursorY=event.y; }
                if(w->cursor && event.action!=3)w->cursor(w,w->cursorX,w->cursorY);
                // Absolute-mode touch acts as a mouse click in menus; captured-mode drag only looks.
                if(event.action==0 && w->cursorMode!=GLFW_CURSOR_DISABLED)touchMouseOwners.insert(event.code);
                if(event.action==1 || event.action==3)touchMouseOwners.erase(event.code);
                const bool down=!touchMouseOwners.empty() || w->physicalButtons[0];
                if(w->buttons[0]!=static_cast<int>(down)) {
                    int action=down?GLFW_PRESS:GLFW_RELEASE;
                    w->buttons[0]=action; if(w->mouse)w->mouse(w,0,action,mods(w));
                }
                break;
            }
            case EventType::Size:
                w->width=static_cast<int>(event.x); w->height=static_cast<int>(event.y);
                if(w->size)w->size(w,w->width,w->height); if(w->framebuffer)w->framebuffer(w,w->width,w->height); break;
            case EventType::Focus:
                w->focused=event.action!=0; if(!w->focused)releaseInput(w); if(w->focus)w->focus(w,event.action); break;
            case EventType::Scroll: if(w->scroll)w->scroll(w,event.x,event.y); break;
            case EventType::Character: if(w->character)w->character(w,event.code); if(w->charmods)w->charmods(w,event.code,mods(w)); break;
            case EventType::Reset: releaseInput(w); break;
            case EventType::Close: requestedClose=true; releaseInput(w); if(w->close)w->close(w); break;
        }
    }
}
void glfwWaitEvents() { queue.wait(.1); glfwPollEvents(); }
void glfwWaitEventsTimeout(double seconds) { if(std::isfinite(seconds)&&seconds>0)queue.wait(std::min(seconds,60.)); glfwPollEvents(); }
void glfwPostEmptyEvent() { queue.push({launchapk::EventType::Scroll}); }
GLFWmonitor** glfwGetMonitors(int* count) { if(count)*count=1; return monitors; }
GLFWmonitor* glfwGetPrimaryMonitor() { return &primaryMonitor; }
const char* glfwGetMonitorName(GLFWmonitor*) { return "Android display"; }
void glfwGetMonitorPos(GLFWmonitor*,int* x,int* y) { if(x)*x=0;if(y)*y=0; }
void glfwGetMonitorWorkarea(GLFWmonitor*,int* x,int* y,int* width,int* height) { if(x)*x=0;if(y)*y=0;if(width)*width=screenWidth;if(height)*height=screenHeight; }
void glfwGetMonitorPhysicalSize(GLFWmonitor*,int* w,int* h) { if(w)*w=0;if(h)*h=0; }
void glfwGetMonitorContentScale(GLFWmonitor*,float* x,float* y) { if(x)*x=1;if(y)*y=1; }
const GLFWvidmode* glfwGetVideoMode(GLFWmonitor*) { videoMode.width=screenWidth;videoMode.height=screenHeight;return &videoMode; }
const GLFWvidmode* glfwGetVideoModes(GLFWmonitor* m,int* count) { if(count)*count=1;return glfwGetVideoMode(m); }
GLFWmonitorfun glfwSetMonitorCallback(GLFWmonitorfun cb) { auto old=monitorCallback;monitorCallback=cb;return old; }
void glfwGetWindowSize(GLFWwindow* w,int* x,int* y) { if(x)*x=w->width;if(y)*y=w->height; }
void glfwGetFramebufferSize(GLFWwindow* w,int* x,int* y) { glfwGetWindowSize(w,x,y); }
void glfwGetWindowContentScale(GLFWwindow*,float* x,float* y) { if(x)*x=1;if(y)*y=1; }
void glfwGetWindowPos(GLFWwindow*,int* x,int* y) { if(x)*x=0;if(y)*y=0; }
void glfwGetWindowFrameSize(GLFWwindow*,int* l,int* t,int* r,int* b) { if(l)*l=0;if(t)*t=0;if(r)*r=0;if(b)*b=0; }
void glfwSetWindowSize(GLFWwindow*,int,int) {} // Surface dimensions are controlled by Android.
void glfwSetWindowPos(GLFWwindow*,int,int) {}
void glfwSetWindowSizeLimits(GLFWwindow*,int,int,int,int) {}
void glfwSetWindowAspectRatio(GLFWwindow*,int,int) {}
void glfwSetWindowTitle(GLFWwindow*,const char*) {}
void glfwSetWindowIcon(GLFWwindow*,int,const GLFWimage*) {}
void glfwShowWindow(GLFWwindow* w) { w->visible=true; }
void glfwHideWindow(GLFWwindow* w) { w->visible=false; }
void glfwFocusWindow(GLFWwindow*) {}
void glfwRequestWindowAttention(GLFWwindow*) {}
void glfwIconifyWindow(GLFWwindow*) {}
void glfwRestoreWindow(GLFWwindow*) {}
void glfwMaximizeWindow(GLFWwindow*) {}
GLFWmonitor* glfwGetWindowMonitor(GLFWwindow* w) { return w->monitor; }
void glfwSetWindowMonitor(GLFWwindow* w,GLFWmonitor* monitor,int,int,int,int,int) { w->monitor=monitor; }
int glfwGetWindowAttrib(GLFWwindow* w,int attr) {
    switch(attr) {
        case GLFW_FOCUSED:return w->focused;case GLFW_VISIBLE:return w->visible;
        case GLFW_RESIZABLE:return GLFW_TRUE;case GLFW_CLIENT_API:return GLFW_OPENGL_API;
        case GLFW_CONTEXT_CREATION_API:return GLFW_EGL_CONTEXT_API;
        case GLFW_CONTEXT_VERSION_MAJOR:return 2;case GLFW_CONTEXT_VERSION_MINOR:return 1;
        default:return GLFW_FALSE;
    }
}
void glfwSetWindowAttrib(GLFWwindow*,int,int) {}
void glfwSetWindowUserPointer(GLFWwindow* w,void* p) { w->user=p; }
void* glfwGetWindowUserPointer(GLFWwindow* w) { return w->user; }
float glfwGetWindowOpacity(GLFWwindow*) { return 1.f; }
void glfwSetWindowOpacity(GLFWwindow*,float) {}
int glfwGetInputMode(GLFWwindow* w,int mode) { return mode==GLFW_CURSOR?w->cursorMode:GLFW_FALSE; }
void glfwSetInputMode(GLFWwindow* w,int mode,int value) { if(mode==GLFW_CURSOR){w->cursorMode=value;pointers.clear();} }
int glfwRawMouseMotionSupported() { return GLFW_FALSE; }
int glfwGetKey(GLFWwindow* w,int key) { return key>=0&&key<=GLFW_KEY_LAST?w->keys[key]:GLFW_RELEASE; }
int glfwGetMouseButton(GLFWwindow* w,int button) { return button>=0&&button<=GLFW_MOUSE_BUTTON_LAST?w->buttons[button]:GLFW_RELEASE; }
int glfwGetKeyScancode(int key) { return key; }
const char* glfwGetKeyName(int key,int) { static thread_local char name[2];if(key>=32&&key<=96){name[0]=static_cast<char>(key>=65&&key<=90?key+32:key);name[1]=0;return name;}return nullptr; }
void glfwGetCursorPos(GLFWwindow* w,double* x,double* y) { if(x)*x=w->cursorX;if(y)*y=w->cursorY; }
void glfwSetCursorPos(GLFWwindow* w,double x,double y) { w->cursorX=x;w->cursorY=y;if(w->cursor)w->cursor(w,x,y); }
GLFWcursor* glfwCreateStandardCursor(int shape) { return new GLFWcursor{shape}; }
GLFWcursor* glfwCreateCursor(const GLFWimage*,int,int) { return new GLFWcursor{GLFW_ARROW_CURSOR}; }
void glfwDestroyCursor(GLFWcursor* c) { delete c; }
void glfwSetCursor(GLFWwindow*,GLFWcursor*) {}
void glfwSetClipboardString(GLFWwindow*,const char* value) { clipboard=value?value:""; }
const char* glfwGetClipboardString(GLFWwindow*) { return clipboard.c_str(); }
double glfwGetTime() { return timeOffset+std::chrono::duration<double>(std::chrono::steady_clock::now()-epoch).count(); }
void glfwSetTime(double value) { if(std::isfinite(value)&&value>=0){timeOffset=value;epoch=std::chrono::steady_clock::now();} }
uint64_t glfwGetTimerValue() { return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
uint64_t glfwGetTimerFrequency() { return 1000000000; }
int glfwJoystickPresent(int) { return GLFW_FALSE; }
const float* glfwGetJoystickAxes(int,int* n) { if(n)*n=0;return nullptr; }
const unsigned char* glfwGetJoystickButtons(int,int* n) { if(n)*n=0;return nullptr; }
const unsigned char* glfwGetJoystickHats(int,int* n) { if(n)*n=0;return nullptr; }
const char* glfwGetJoystickName(int) { return nullptr; }
const char* glfwGetJoystickGUID(int) { return nullptr; }
void glfwSetJoystickUserPointer(int,void*) {}
void* glfwGetJoystickUserPointer(int) { return nullptr; }
int glfwJoystickIsGamepad(int) { return GLFW_FALSE; }
GLFWjoystickfun glfwSetJoystickCallback(GLFWjoystickfun cb) { auto old=joystickCallback;joystickCallback=cb;return old; }
int glfwUpdateGamepadMappings(const char*) { return GLFW_FALSE; }
const char* glfwGetGamepadName(int) { return nullptr; }
int glfwGetGamepadState(int,GLFWgamepadstate*) { return GLFW_FALSE; }
int glfwVulkanSupported() { return GLFW_FALSE; }
const char** glfwGetRequiredInstanceExtensions(uint32_t* count) { if(count)*count=0;return nullptr; }
void glfwSetGamma(GLFWmonitor*,float) { error(GLFW_PLATFORM_ERROR,"Android display gamma is managed by the system"); }
const GLFWgammaramp* glfwGetGammaRamp(GLFWmonitor*) { return nullptr; }
void glfwSetGammaRamp(GLFWmonitor*,const GLFWgammaramp*) { error(GLFW_PLATFORM_ERROR,"Android display gamma is managed by the system"); }
void glfwSetMonitorUserPointer(GLFWmonitor*,void*) {}
void* glfwGetMonitorUserPointer(GLFWmonitor*) { return nullptr; }
#define CALLBACK(Name,Type,Field) Type glfwSet##Name##Callback(GLFWwindow* w,Type cb){auto old=w->Field;w->Field=cb;return old;}
CALLBACK(Key,GLFWkeyfun,key)
CALLBACK(Char,GLFWcharfun,character)
CALLBACK(CharMods,GLFWcharmodsfun,charmods)
CALLBACK(MouseButton,GLFWmousebuttonfun,mouse)
CALLBACK(CursorPos,GLFWcursorposfun,cursor)
CALLBACK(CursorEnter,GLFWcursorenterfun,enter)
CALLBACK(Scroll,GLFWscrollfun,scroll)
CALLBACK(FramebufferSize,GLFWframebuffersizefun,framebuffer)
CALLBACK(WindowSize,GLFWwindowsizefun,size)
CALLBACK(WindowFocus,GLFWwindowfocusfun,focus)
CALLBACK(WindowClose,GLFWwindowclosefun,close)
CALLBACK(WindowPos,GLFWwindowposfun,pos)
CALLBACK(WindowRefresh,GLFWwindowrefreshfun,refresh)
CALLBACK(WindowIconify,GLFWwindowiconifyfun,iconify)
CALLBACK(WindowMaximize,GLFWwindowmaximizefun,maximize)
CALLBACK(WindowContentScale,GLFWwindowcontentscalefun,scale)
CALLBACK(Drop,GLFWdropfun,drop)
}
