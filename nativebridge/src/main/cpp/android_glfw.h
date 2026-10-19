#pragma once
#include <android/native_window.h>
// Private ABI between the ART bridge and our original GLFW-compatible adapter.
extern "C" {
void launchapk_window_attach(ANativeWindow* window, int width, int height);
void launchapk_window_detach();
void launchapk_window_configure(const char* gl4es_path);
void launchapk_input_key(int key, bool pressed);
void launchapk_input_mouse(int button, bool pressed);
void launchapk_input_pointer(int id, float x, float y, int action);
void launchapk_input_focus(bool focused);
void launchapk_input_scroll(float x, float y);
void launchapk_input_character(unsigned int codepoint);
void launchapk_window_close();
const char* launchapk_window_status();
}
