package com.localai.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LocalAIApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val logger = CrashLogger(this)
        Thread.setDefaultUncaughtExceptionHandler(logger)
        logger.logPreviousExit()
    }
}
