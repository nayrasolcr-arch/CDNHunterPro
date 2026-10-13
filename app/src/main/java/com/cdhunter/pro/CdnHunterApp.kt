package com.cdhunter.pro

import android.app.Application
import com.cdhunter.pro.di.AppContainer
import kotlinx.coroutines.launch

class CdnHunterApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.init()
        }
    }
}
