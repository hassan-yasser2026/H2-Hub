package com.example.auth

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

object AppCheckManager {
    private const val TAG = "AppCheckManager"
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        try {
            if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) {
                throw IllegalStateException("Firebase configuration is unavailable.")
            }
            val provider = createAppCheckProviderFactory()
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(provider)
            initialized = true
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Firebase App Check could not be initialized.", error)
        }
    }
}
