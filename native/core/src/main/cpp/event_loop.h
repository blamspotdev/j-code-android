#pragma once

#include <functional>
#include <thread>
#include <queue>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <future>
#include <memory>

namespace jcode {

/**
 * @brief Thread pool and event loop for async operations
 * 
 * Provides a managed thread pool for executing long-running operations
 * without blocking the main thread or JNI calls.
 */
class EventLoop {
public:
    using Task = std::function<void()>;
    
    /**
     * @brief Create an event loop with the specified number of worker threads
     * @param numThreads Number of worker threads (default: hardware concurrency)
     */
    explicit EventLoop(size_t numThreads = 0);
    
    ~EventLoop();
    
    // Prevent copying
    EventLoop(const EventLoop&) = delete;
    EventLoop& operator=(const EventLoop&) = delete;
    
    /**
     * @brief Submit a task for async execution
     * @return std::future for the task result
     */
    template<typename F, typename... Args>
    auto submit(F&& f, Args&&... args) 
        -> std::future<typename std::invoke_result<F, Args...>::type>
    {
        using ReturnType = typename std::invoke_result<F, Args...>::type;
        
        auto task = std::make_shared<std::packaged_task<ReturnType()>>(
            std::bind(std::forward<F>(f), std::forward<Args>(args)...)
        );
        
        std::future<ReturnType> result = task->get_future();
        
        {
            std::unique_lock<std::mutex> lock(queueMutex_);
            if (stopped_) {
                throw std::runtime_error("EventLoop is stopped");
            }
            tasks_.emplace([task]() { (*task)(); });
        }
        
        condition_.notify_one();
        return result;
    }
    
    /**
     * @brief Submit a task without tracking the result
     */
    void post(Task task);
    
    /**
     * @brief Stop the event loop and wait for all tasks to complete
     */
    void stop();
    
    /**
     * @brief Check if the event loop is running
     */
    bool isRunning() const { return !stopped_; }
    
    /**
     * @brief Get the number of pending tasks
     */
    size_t pendingTasks() const;
    
    /**
     * @brief Wait for all pending tasks to complete
     */
    void waitAll();

private:
    void workerThread();
    
    std::vector<std::thread> workers_;
    std::queue<Task> tasks_;
    
    mutable std::mutex queueMutex_;
    std::condition_variable condition_;
    std::atomic<bool> stopped_;
    std::atomic<size_t> activeTasks_;
};

/**
 * @brief Global event loop instance
 */
EventLoop& getGlobalEventLoop();

/**
 * @brief Initialize the global event loop
 */
void initGlobalEventLoop(size_t numThreads = 0);

/**
 * @brief Shutdown the global event loop
 */
void shutdownGlobalEventLoop();

} // namespace jcode
