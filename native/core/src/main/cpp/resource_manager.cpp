#include "resource_manager.h"
#include "logger.h"

namespace jcode {

void ResourceManager::addRef(HandleId id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = resources_.find(id);
    if (it != resources_.end()) {
        ++(it->second.refCount);
    }
}

void ResourceManager::release(HandleId id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = resources_.find(id);
    if (it == resources_.end()) {
        return;
    }
    
    if (--(it->second.refCount) <= 0) {
        if (it->second.cleanup) {
            try {
                it->second.cleanup();
            } catch (const std::exception& e) {
                LOG_E("ResourceManager", "Cleanup callback threw: %s", e.what());
            }
        }
        resources_.erase(it);
    }
}

bool ResourceManager::exists(HandleId id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return resources_.find(id) != resources_.end();
}

size_t ResourceManager::count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return resources_.size();
}

void ResourceManager::cleanupAll() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& pair : resources_) {
        if (pair.second.cleanup) {
            try {
                pair.second.cleanup();
            } catch (const std::exception& e) {
                LOG_E("ResourceManager", "Cleanup callback threw: %s", e.what());
            }
        }
    }
    resources_.clear();
    LOG_I("ResourceManager", "All resources cleaned up");
}

// Global resource manager
static std::unique_ptr<ResourceManager> globalResourceManager;
static std::mutex globalResourceManagerMutex;

ResourceManager& getResourceManager() {
    std::lock_guard<std::mutex> lock(globalResourceManagerMutex);
    if (!globalResourceManager) {
        globalResourceManager = std::make_unique<ResourceManager>();
    }
    return *globalResourceManager;
}

} // namespace jcode
