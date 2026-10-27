package org.pagel.ai.di

import javax.inject.Qualifier

/** Dispatchers are injected (RULES.md), so tests run the same code on a test dispatcher. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
