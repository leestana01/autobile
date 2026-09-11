package com.autobile.app

import android.app.Application

class AutobileApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this).also { it.start() }
    }

    override fun onTerminate() {
        graph.close()
        super.onTerminate()
    }
}
