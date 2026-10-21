package com.example

import android.app.Application
import com.example.auth.AppCheckManager

class H2HubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCheckManager.initialize(this)
    }
}
