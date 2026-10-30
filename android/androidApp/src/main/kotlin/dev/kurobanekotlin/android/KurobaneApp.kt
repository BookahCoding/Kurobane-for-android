package dev.kurobanekotlin.android

import android.app.Application
import dev.kurobanekotlin.core.di.coreModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class KurobaneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KurobaneApp)
            modules(coreModule, androidModule)
        }
    }
}
