package com.example.translator

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log

class TranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            
            val prefs = getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_crash", stackTrace).commit()

            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)

            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }
}
