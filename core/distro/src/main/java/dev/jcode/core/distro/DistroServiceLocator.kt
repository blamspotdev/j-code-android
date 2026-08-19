package dev.jcode.core.distro

import android.content.Context

object DistroServiceLocator {
    @Volatile
    private var distroService: DistroService? = null

    fun distroService(context: Context): DistroService {
        return distroService ?: synchronized(this) {
            distroService ?: DistroService(context.applicationContext).also {
                distroService = it
            }
        }
    }
}
