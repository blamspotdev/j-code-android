package dev.jcode.feature.explorer

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * File/folder explorer with tree and list views.
 * Hilt module — provides explorer dependencies when needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object ExplorerModule
