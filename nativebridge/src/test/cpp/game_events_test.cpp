#include <cassert>
#include <thread>
#include "game_events.h"
int main() {
    using namespace launchapk;
    PointerTracker pointer;
    assert(pointer.delta(7,500,200,0).first==0);
    auto move=pointer.delta(7,510,190,2);
    assert(move.first==10 && move.second==-10);
    assert(pointer.delta(8,900,300,0).first==0);
    assert(pointer.delta(7,520,200,2).first==10);
    pointer.delta(7,520,200,3);
    assert(pointer.delta(7,200,200,0).first==0);
    pointer.clear();
    assert(pointer.delta(7,1000,1000,2).first==0);
    EventQueue queue;
    std::thread producer([&]{queue.push({EventType::Key,87,1});queue.push({EventType::Pointer,1,2,10,20});queue.push({EventType::Key,87,0});});
    producer.join(); auto batch=queue.drain();
    assert(batch.size()==3 && batch[0].action==1 && batch[2].action==0);
    assert(queue.drain().empty());
    for(int i=0;i<2049;++i)queue.push({EventType::Key,87,1});
    batch=queue.drain();assert(batch[0].type==EventType::Reset && batch.size()==2);
}
