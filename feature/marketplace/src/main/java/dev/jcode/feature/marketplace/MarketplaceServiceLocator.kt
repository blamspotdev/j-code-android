package dev.jcode.feature.marketplace

import android.content.Context
import dev.jcode.core.distro.DistroServiceLocator

object MarketplaceServiceLocator {
    @Volatile
    private var catalog: TemplateCatalog? = null

    @Volatile
    private var scaffolder: TemplateScaffolder? = null

    fun templateCatalog(context: Context): TemplateCatalog {
        return catalog ?: synchronized(this) {
            catalog ?: TemplateCatalog(context.applicationContext).also { catalog = it }
        }
    }

    fun templateScaffolder(context: Context): TemplateScaffolder {
        return scaffolder ?: synchronized(this) {
            scaffolder ?: TemplateScaffolder(
                DistroServiceLocator.distroService(context.applicationContext),
            ).also { scaffolder = it }
        }
    }
}
