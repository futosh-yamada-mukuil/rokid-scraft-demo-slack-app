package com.scraft.glassreport

import android.app.Application
import com.scraft.glassreport.di.AppContainer

/** Application。DI コンテナを生成して全体へ供給する。 */
class SCRAFTApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
