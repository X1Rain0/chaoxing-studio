package com.x1rain0.chaoxingstudio

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.x1rain0.chaoxingstudio.data.ActivityCard
import com.x1rain0.chaoxingstudio.data.CourseChoice
import com.x1rain0.chaoxingstudio.data.ManualSignInput
import com.x1rain0.chaoxingstudio.data.MonitorMode
import com.x1rain0.chaoxingstudio.data.MonitorUiState
import com.x1rain0.chaoxingstudio.monitor.MonitorService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ChaoxingStudioApplication
    private val runtime = app.monitorRuntime
    private var foregroundLoop: Job? = null

    internal val state: StateFlow<MonitorUiState> = runtime.state

    fun onHostStarted() {
        viewModelScope.launch {
            if (!runtime.state.value.loggedIn && !runtime.restore()) {
                if (runtime.preferences.config().monitorWanted) runtime.setMonitorWanted(false)
                return@launch
            }
            if (runtime.preferences.config().monitorWanted) startAccordingToMode()
        }
    }

    fun onHostStopped() {
        if (runtime.preferences.config().mode == MonitorMode.FOREGROUND) {
            foregroundLoop?.cancel()
            foregroundLoop = null
        }
    }

    fun login(phone: String, password: String) {
        viewModelScope.launch {
            runtime.login(phone, password)
            if (runtime.state.value.loggedIn && runtime.preferences.config().monitorWanted) startAccordingToMode()
        }
    }

    fun startMonitoring() {
        if (!runtime.state.value.loggedIn) {
            runtime.reportError("请先登录，再开始巡检")
            return
        }
        val courses = runCatching { org.json.JSONArray(runtime.preferences.config().selectedCoursesJson) }
            .getOrNull()
        if (courses == null || courses.length() == 0) {
            runtime.reportError("请先选择至少一门课程")
            return
        }
        runtime.setMonitorWanted(true)
        startAccordingToMode()
    }

    fun stopMonitoring() {
        runtime.setMonitorWanted(false)
        foregroundLoop?.cancel()
        foregroundLoop = null
        getApplication<Application>().stopService(Intent(getApplication(), MonitorService::class.java))
    }

    internal fun setMode(mode: MonitorMode) {
        val wasWanted = runtime.preferences.config().monitorWanted
        foregroundLoop?.cancel()
        foregroundLoop = null
        getApplication<Application>().stopService(Intent(getApplication(), MonitorService::class.java))
        runtime.setMode(mode)
        if (wasWanted) startAccordingToMode()
    }

    fun setBootRestore(value: Boolean) = runtime.setBootRestore(value)
    fun setAutoSign(value: Boolean) = runtime.setAutoSign(value)
    fun setIntervalSeconds(value: Int) = runtime.setIntervalSeconds(value)

    internal fun signInParameters() = runtime.signInParameters()

    fun saveDefaultLocation(latitude: String, longitude: String, address: String) =
        runtime.saveDefaultLocation(latitude, longitude, address)

    internal fun saveCourseSelection(courses: List<CourseChoice>) {
        runtime.saveCourseSelection(courses)
        if (courses.isEmpty() && runtime.preferences.config().monitorWanted) {
            stopMonitoring()
            runtime.reportError("课程已清空，巡检已停止。请至少选择一门课程后再开始。")
        }
    }

    internal suspend fun uploadPhotoForActivity(activity: ActivityCard, mimeType: String, data: ByteArray): String =
        runtime.uploadPhotoForActivity(activity, mimeType, data)

    internal fun signNow(activity: ActivityCard) = signNow(activity, ManualSignInput())

    internal fun signNow(activity: ActivityCard, input: ManualSignInput) =
        signNow(activity, input) { _, _ -> }

    internal fun signNow(
        activity: ActivityCard,
        input: ManualSignInput,
        onComplete: (succeeded: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { runtime.signNow(activity, input) }
                .onSuccess { result ->
                    val succeeded = result.optBoolean("ok", false)
                    onComplete(succeeded, result.optString("message", if (succeeded) "签到成功" else "签到未完成"))
                }
                .onFailure { error ->
                    val message = error.message ?: "签到提交失败"
                    runtime.reportError(message)
                    onComplete(false, message)
                }
        }
    }

    fun dismissError() = runtime.reportError("")

    fun dismissSignNotice() = runtime.dismissSignNotice()

    fun reportError(message: String) = runtime.reportError(message)

    fun logout() {
        stopMonitoring()
        viewModelScope.launch { runtime.logout() }
    }

    private fun startAccordingToMode() {
        if (!runtime.state.value.loggedIn) return
        val config = runtime.preferences.config()
        val selected = runCatching { org.json.JSONArray(config.selectedCoursesJson).length() }.getOrDefault(0)
        if (selected == 0) {
            runtime.setMonitorWanted(false)
            runtime.reportError("请至少选择一门课程后再开始巡检")
            return
        }
        when (config.mode) {
            MonitorMode.FOREGROUND -> startForegroundLoop()
            MonitorMode.BACKGROUND -> {
                if (!notificationsAllowed()) {
                    runtime.setMonitorWanted(false)
                    runtime.reportError("后台巡检需要通知权限，请在系统提示中允许通知")
                    return
                }
                try {
                    ContextCompat.startForegroundService(
                        getApplication(),
                        Intent(getApplication(), MonitorService::class.java),
                    )
                } catch (error: Exception) {
                    runtime.setMonitorWanted(false)
                    runtime.reportError(error.message ?: "后台巡检服务启动失败")
                }
            }
        }
    }

    private fun startForegroundLoop() {
        if (foregroundLoop?.isActive == true) return
        foregroundLoop = viewModelScope.launch {
            while (isActive && runtime.preferences.config().monitorWanted &&
                runtime.preferences.config().mode == MonitorMode.FOREGROUND
            ) {
                runCatching { runtime.scanOnce() }
                    .onFailure { runtime.reportError(it.message ?: "巡检失败") }
                val interval = runtime.preferences.config().intervalSeconds.coerceAtLeast(30)
                delay(interval * 1_000L)
            }
            foregroundLoop = null
        }
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
}
