package com.rtls.app

import android.app.Application
import com.rtls.core.location.LocationServiceFlags

class RtlsApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        LocationServiceFlags.allowFakeLocation = BuildConfig.ALLOW_FAKE_LOCATION
    }
}
