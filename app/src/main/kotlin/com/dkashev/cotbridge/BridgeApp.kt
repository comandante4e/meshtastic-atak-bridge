package com.dkashev.cotbridge

import android.app.Application
import com.dkashev.cotbridge.settings.PreferencesRepo

class BridgeApp : Application() {

    lateinit var preferences: PreferencesRepo
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = PreferencesRepo(this)
        instance = this
    }

    companion object {
        lateinit var instance: BridgeApp
            private set
    }
}
