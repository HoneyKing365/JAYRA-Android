package ai.jayra.companion

import ai.jayra.companion.di.appModule
import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class JayraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@JayraApplication)
            modules(appModule)
        }
    }
}
