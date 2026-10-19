#pragma once
#include <condition_variable>
#include <deque>
#include <mutex>
#include <unordered_map>
#include <utility>

namespace launchapk {
enum class EventType { Key, Mouse, Pointer, Size, Focus, Scroll, Character, Reset, Close };
struct Event { EventType type; int code = 0; int action = 0; double x = 0; double y = 0; };
// Only the game thread consumes events and invokes LWJGL's HotSpot callbacks.
class EventQueue {
    std::mutex mutex;
    std::condition_variable wake;
    std::deque<Event> events;
public:
    void push(Event event) {
        std::lock_guard<std::mutex> guard(mutex);
        // Preserve button/key ordering. On overflow explicitly release held input.
        if (events.size() >= 2048) { events.clear(); events.push_back({EventType::Reset}); }
        events.push_back(event); wake.notify_one();
    }
    std::deque<Event> drain() {
        std::lock_guard<std::mutex> guard(mutex);
        std::deque<Event> result; result.swap(events); return result;
    }
    void wait(double seconds) {
        std::unique_lock<std::mutex> guard(mutex);
        wake.wait_for(guard, std::chrono::duration<double>(seconds), [&] { return !events.empty(); });
    }
};
class PointerTracker {
    std::unordered_map<int, std::pair<double, double>> touches;
public:
    // action matches Android/InputBridge: down=0 up=1 move=2 cancel=3.
    std::pair<double, double> delta(int id, double x, double y, int action) {
        auto found = touches.find(id);
        std::pair<double, double> result{0, 0};
        if (action == 2 && found != touches.end()) result = {x - found->second.first, y - found->second.second};
        if (action == 0 || action == 2) touches[id] = {x, y}; else touches.erase(id);
        return result;
    }
    void clear() { touches.clear(); }
};
}
