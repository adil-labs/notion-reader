package com.example.notionreader

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Application-bound owner of bulk discovery. Binding does not authorize background processing. */
class DiscoveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = Binder()

    override fun onCreate() {
        super.onCreate()
        (application as ReaderApplication).startDiscoveryWorker(scope)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
