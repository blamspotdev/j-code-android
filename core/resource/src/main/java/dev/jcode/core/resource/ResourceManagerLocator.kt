package dev.jcode.core.resource

import android.content.Context

/**
 * Thread-safe singleton locator for ResourceManager.
 * Used until Hilt is fully integrated into the :app module.
 */
object ResourceManagerLocator {
    @Volatile
    private var instance: ResourceManager? = null

    fun resourceManager(context: Context): ResourceManager {
        return instance ?: synchronized(this) {
            instance ?: ResourceManager(context.applicationContext).also { instance = it }
        }
    }
}
