package com.example.translator

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log

class TranslatorApplication : Application() {

    companion object {
        const val CRASH_PREFS = "crash_prefs"
        const val KEY_LAST_CRASH = "last_crash"
        private const val KEY_LAST_CRASH_AT = "last_crash_at"
        private const val KEY_CRASH_COUNT = "crash_count"
    }

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val decision = recordCrash(throwable)

            // Por qué: Solo se relanza la interfaz si el fallo ocurrió en el hilo principal y no forma parte
            // de un bucle de fallos repetidos; en caso contrario se delega en el gestor del sistema.
            val isMainThread = thread == Looper.getMainLooper().thread
            if (decision.shouldRelaunch && isMainThread) {
                relaunchMainActivity()
                terminateProcess()
            } else if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                terminateProcess()
            }
        }
    }

    private fun recordCrash(throwable: Throwable): CrashLoopPolicy.Decision {
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val decision = CrashLoopPolicy.evaluate(
            previousCount = prefs.getInt(KEY_CRASH_COUNT, 0),
            lastCrashAtMillis = prefs.getLong(KEY_LAST_CRASH_AT, 0L),
            nowMillis = now
        )
        // Por qué: commit() síncrono porque el proceso termina inmediatamente después.
        prefs.edit()
            .putString(KEY_LAST_CRASH, Log.getStackTraceString(throwable))
            .putLong(KEY_LAST_CRASH_AT, now)
            .putInt(KEY_CRASH_COUNT, decision.newCrashCount)
            .commit()
        return decision
    }

    private fun relaunchMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("Translator", "Failed to relaunch after crash", e)
        }
    }

    private fun terminateProcess() {
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(10)
    }
}
