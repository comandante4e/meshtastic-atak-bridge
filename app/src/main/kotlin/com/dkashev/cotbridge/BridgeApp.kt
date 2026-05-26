package com.dkashev.cotbridge

import android.app.Application
import com.dkashev.cotbridge.settings.PreferencesRepo
import com.dkashev.cotbridge.tak.CertVault

class BridgeApp : Application() {

    lateinit var preferences: PreferencesRepo
        private set

    lateinit var certVault: CertVault
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = PreferencesRepo(this)
        certVault = CertVault(this)
        instance = this
    }

    companion object {
        lateinit var instance: BridgeApp
            private set
    }
}
