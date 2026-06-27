package dev.jcode.feature.marketplace

import android.content.Context
import dev.jcode.core.distro.DistroServiceLocator

object MarketplaceServiceLocator {
    @Volatile
    private var installer: ExtensionInstaller? = null

    @Volatile
    private var catalog: TemplateCatalog? = null

    @Volatile
    private var scaffolder: TemplateScaffolder? = null

    fun extensionInstaller(context: Context): ExtensionInstaller {
        return installer ?: synchronized(this) {
            installer ?: ExtensionInstaller(context.applicationContext).also { installer = it }
        }
    }

    fun templateCatalog(context: Context): TemplateCatalog {
        return catalog ?: synchronized(this) {
            catalog ?: TemplateCatalog(extensionInstaller(context)).also { catalog = it }
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
