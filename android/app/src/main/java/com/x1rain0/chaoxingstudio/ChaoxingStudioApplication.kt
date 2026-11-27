package com.x1rain0.chaoxingstudio

import android.app.Application
import com.x1rain0.chaoxingstudio.data.MonitorRuntime

class ChaoxingStudioApplication : Application() {
    internal lateinit var monitorRuntime: MonitorRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        monitorRuntime = MonitorRuntime(this)
    }
}
