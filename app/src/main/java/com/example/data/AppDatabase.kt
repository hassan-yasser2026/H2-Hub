package com.example.data

import android.content.Context

class AppDatabase private constructor(context: Context) {
    private val appDaoInstance = RoomBackedAppDao(
        context.applicationContext,
        PersistentAppDao(context.applicationContext)
    )

    fun appDao(): AppDao = appDaoInstance

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = AppDatabase(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
