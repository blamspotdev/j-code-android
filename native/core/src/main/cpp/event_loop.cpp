#include "event_loop.h"
#include "logger.h"
#include <algorithm>

namespace jcode {

EventLoop::EventLoop(size_t numThreads) 
    : stopped_(false), activeTasks_(0)
{
    if (numThreads == 0) {
        numThreads = std::max(1u, std::thread::hardware_concurrency());
    }
    
    LOG_I("EventLoop", "Starting event loop with %zu threads", numThreads);
    
    for (size_t i = 0; i < numThreads; ++i) {
        workers_.emplace_back(&EventLoop::workerThread, this);
    }
}

EventLoop::~EventLoop() {
    stop();
}

void EventLoop::workerThread() {
    while (true) {
        Task task;
        
        {
            std::unique_lock<std::mutex> lock(queueMutex_);
            condition_.wait(lock, [this] { 
                return stopped_ || !tasks_.empty(); 
            });
            
            if (stopped_ && tasks_.empty()) {
                return;
            }
            
            task = std::move(tasks_.front());
            tasks_.pop();
        }
        
        ++activeTasks_;
        try {
            task();
        } catch (const std::exception& e) {
            LOG_E("EventLoop", "Task threw exception: %s", e.what());
        } catch (...) {
            LOG_E("EventLoop", "Task threw unknown exception");
        }
        --activeTasks_;
    }
}

void EventLoop::post(Task task) {
    {
        std::unique_lock<std::mutex> lock(queueMutex_);
        if (stopped_) {
            LOG_W("EventLoop", "Attempted to post task to stopped event loop");
            return;
        }
        tasks_.emplace(std::move(task));
    }
    condition_.notify_one();
}

void EventLoop::stop() {
    {
        std::unique_lock<std::mutex> lock(queueMutex_);
        stopped_ = true;
    }
    
    condition_.notify_all();
    
    for (auto& worker : workers_) {
        if (worker.joinable()) {
            worker.join();
        }
    }
    
    LOG_I("EventLoop", "Event loop stopped");
}

size_t EventLoop::pendingTasks() const {
    std::unique_lock<std::mutex> lock(queueMutex_);
    return tasks_.size();
}

void EventLoop::waitAll() {
    while (true) {
        {
            std::unique_lock<std::mutex> lock(queueMutex_);
            if (tasks_.empty() && activeTasks_ == 0) {
                return;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

// Global event loop management
static std::unique_ptr<EventLoop> globalEventLoop;
static std::mutex globalEventLoopMutex;

EventLoop& getGlobalEventLoop() {
    std::lock_guard<std::mutex> lock(globalEventLoopMutex);
    if (!globalEventLoop) {
        initGlobalEventLoop();
    }
    return *globalEventLoop;
}

void initGlobalEventLoop(size_t numThreads) {
    std::lock_guard<std::mutex> lock(globalEventLoopMutex);
    if (globalEventLoop) {
        LOG_W("EventLoop", "Global event loop already initialized");
        return;
    }
    globalEventLoop = std::make_unique<EventLoop>(numThreads);
    LOG_I("EventLoop", "Global event loop initialized");
}

void shutdownGlobalEventLoop() {
    std::lock_guard<std::mutex> lock(globalEventLoopMutex);
    if (globalEventLoop) {
        globalEventLoop->stop();
        globalEventLoop.reset();
        LOG_I("EventLoop", "Global event loop shutdown");
    }
}

} // namespace jcode
