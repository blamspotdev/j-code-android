#pragma once

#include <memory>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <functional>

namespace jcode {

/**
 * @brief Resource manager for tracking native handles and managing lifetimes
 * 
 * Provides a centralized registry for native resources with automatic
 * cleanup and reference counting.
 */
class ResourceManager {
public:
    using HandleId = uint64_t;
    using CleanupCallback = std::function<void()>;
    
    /**
     * @brief Register a resource and get its handle ID
     * @param resource Shared pointer to the resource
     * @param cleanup Optional cleanup callback
     * @return Handle ID for the resource
     */
    template<typename T>
    HandleId registerResource(std::shared_ptr<T> resource, CleanupCallback cleanup = nullptr) {
        std::lock_guard<std::mutex> lock(mutex_);
        HandleId id = nextId_++;
        
        ResourceEntry entry;
        entry.resource = resource;
        entry.cleanup = cleanup;
        entry.refCount = 1;
        
        resources_[id] = std::move(entry);
        return id;
    }
    
    /**
     * @brief Get a resource by handle ID
     * @return Shared pointer to the resource, or nullptr if not found
     */
    template<typename T>
    std::shared_ptr<T> getResource(HandleId id) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = resources_.find(id);
        if (it == resources_.end()) {
            return nullptr;
        }
        return std::static_pointer_cast<T>(it->second.resource);
    }
    
    /**
     * @brief Increment reference count for a resource
     */
    void addRef(HandleId id);
    
    /**
     * @brief Decrement reference count and cleanup if zero
     */
    void release(HandleId id);
    
    /**
     * @brief Check if a resource exists
     */
    bool exists(HandleId id) const;
    
    /**
     * @brief Get the number of registered resources
     */
    size_t count() const;
    
    /**
     * @brief Cleanup all resources
     */
    void cleanupAll();

private:
    struct ResourceEntry {
        std::shared_ptr<void> resource;
        CleanupCallback cleanup;
        int refCount;
    };
    
    std::unordered_map<HandleId, ResourceEntry> resources_;
    mutable std::mutex mutex_;
    std::atomic<HandleId> nextId_{1};
};

/**
 * @brief Global resource manager instance
 */
ResourceManager& getResourceManager();

} // namespace jcode
