#include <jni.h>
#include <android/native_window_jni.h>
#include "android_glfw.h"

extern "C" {
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_configureGame(JNIEnv* env,jobject,jstring path) {
    const char* text=env->GetStringUTFChars(path,nullptr);
    if(text){launchapk_window_configure(text);env->ReleaseStringUTFChars(path,text);}
}
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_attachGameSurface(JNIEnv* env,jobject,jobject surface,jint width,jint height) {
    ANativeWindow* window=ANativeWindow_fromSurface(env,surface);
    if(window){launchapk_window_attach(window,width,height);ANativeWindow_release(window);}
}
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_detachGameSurface(JNIEnv*,jobject) { launchapk_window_detach(); }
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_closeGame(JNIEnv*,jobject) { launchapk_window_close(); }
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_sendKey(JNIEnv*,jobject,jint key,jboolean pressed) { launchapk_input_key(key,pressed); }
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_sendMouseButton(JNIEnv*,jobject,jint button,jboolean pressed) { launchapk_input_mouse(button,pressed); }
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_sendPointer(JNIEnv*,jobject,jint id,jfloat x,jfloat y,jint action) { launchapk_input_pointer(id,x,y,action); }
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_sendFocus(JNIEnv*,jobject,jboolean focused) { launchapk_input_focus(focused); }
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_sendScroll(JNIEnv*,jobject,jfloat x,jfloat y) { launchapk_input_scroll(x,y); }
JNIEXPORT void JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_sendCharacter(JNIEnv*,jobject,jint cp) { launchapk_input_character(static_cast<unsigned>(cp)); }
JNIEXPORT jstring JNICALL Java_com_launchapk_java_nativebridge_NativeBridge_gameStatus(JNIEnv* env,jobject) { return env->NewStringUTF(launchapk_window_status()); }
}
