package dev.jcode.core.config

object ConfigServiceLocator {
    @Volatile
    private var configService: ConfigService? = null

    fun configService(): ConfigService {
        return configService ?: synchronized(this) {
            configService ?: ConfigService().also { configService = it }
        }
    }
}
