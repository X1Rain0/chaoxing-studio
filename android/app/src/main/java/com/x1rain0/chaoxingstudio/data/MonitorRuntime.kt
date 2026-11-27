package com.x1rain0.chaoxingstudio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

internal data class CourseChoice(
    val courseId: String,
    val classId: String,
    val title: String,
    val selected: Boolean,
    val rawJson: String,
)

internal data class ActivityCard(
    val activeId: String,
    val name: String,
    val kind: Int,
    val kindName: String,
    val courseTitle: String,
    val startedAtMillis: Long,
    val rawJson: String,
    val signed: Boolean = false,
) {
    fun freshness(nowMillis: Long = System.currentTimeMillis()): String {
        if (startedAtMillis <= 0L) return "刚发现"
        val minutes = TimeUnit.MILLISECONDS.toMinutes((nowMillis - startedAtMillis).coerceAtLeast(0L))
        return if (minutes < 1L) "刚开始" else "已开始 $minutes 分钟"
    }
}

internal data class MonitorUiState(
    val loggedIn: Boolean = false,
    val accountName: String = "",
    val busy: Boolean = false,
    val error: String = "",
    val signNotice: String = "",
    val monitorWanted: Boolean = false,
    val mode: MonitorMode = MonitorMode.FOREGROUND,
    val bootRestore: Boolean = false,
    val autoSign: Boolean = false,
    val intervalSeconds: Int = 60,
    val lastScanMillis: Long = 0L,
    val scanCountToday: Int = 0,
    val signCountToday: Int = 0,
    val availableCourses: List<CourseChoice> = emptyList(),
    val activities: List<ActivityCard> = emptyList(),
    val latestActivity: ActivityCard? = null,
)

internal data class ScanOutput(
    val activities: List<ActivityCard>,
    val newlyDiscovered: List<ActivityCard>,
    val courseErrors: Int,
)

/** Shared process-local monitor facade. The Android service owns scheduling; Go owns Chaoxing HTTP/sign logic. */
internal class MonitorRuntime(context: Context) {
    private val appContext = context.applicationContext
    val preferences = MonitorPreferences(appContext)
    private val session = GoSession(appContext)
    private val scanLock = Mutex()
    private val mutableState = MutableStateFlow(loadPreferences())
    val state: StateFlow<MonitorUiState> = mutableState.asStateFlow()

    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        val response = try {
            session.restoreSession()
        } catch (error: Exception) {
            mutableState.value = loadPreferences().copy(error = error.message ?: "自动恢复登录失败")
            return@withContext false
        } ?: return@withContext false

        val available = response.optJSONArray("courses") ?: JSONArray()
        val hasStoredSelection = preferences.hasSelectedCourseSelection()
        val previous = runCatching { JSONArray(preferences.config().selectedCoursesJson) }
            .getOrDefault(JSONArray())
        val selectedIds = buildSet {
            for (index in 0 until previous.length()) {
                val item = previous.optJSONObject(index) ?: continue
                add(courseKey(item.optString("courseId"), item.optString("classId")))
            }
        }
        val rows = parseCourses(available, selectedIds.takeIf { hasStoredSelection })
        val selected = JSONArray()
        rows.filter { it.selected }.forEach { selected.put(JSONObject(it.rawJson)) }
        preferences.saveAccountName(response.optString("name"))
        preferences.saveAvailableCourses(available)
        preferences.saveSelectedCourses(selected)
        mutableState.value = loadPreferences().copy(
            loggedIn = true,
            accountName = response.optString("name"),
            availableCourses = parseCourses(available, selectedIds.takeIf { hasStoredSelection }),
            error = "",
        )
        true
    }

    suspend fun login(phone: String, password: String) = withContext(Dispatchers.IO) {
        mutableState.value = mutableState.value.copy(busy = true, error = "")
        try {
            val response = session.login(phone, password)
            val available = response.optJSONArray("courses") ?: JSONArray()
            val selected = JSONArray()
            val rows = parseCourses(available, null)
            rows.forEach { selected.put(JSONObject(it.rawJson)) }
            preferences.saveAccountName(response.optString("name"))
            preferences.saveAvailableCourses(available)
            preferences.saveSelectedCourses(selected)
            mutableState.value = loadPreferences().copy(
                loggedIn = true,
                accountName = response.optString("name"),
                availableCourses = rows,
                busy = false,
                error = "",
            )
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                busy = false,
                error = error.message ?: "登录失败，请检查网络和账号信息",
            )
        }
    }

    suspend fun scanOnce(): ScanOutput = scanLock.withLock {
        withContext(Dispatchers.IO) {
            if (!mutableState.value.loggedIn && !restore()) {
                error("请先登录学习通账号")
            }
            val config = preferences.config()
            check(config.selectedCoursesJson != "[]") { "请至少选择一门课程" }
            val response = session.checkActivities(config.selectedCoursesJson)
            val activitiesJson = response.optJSONArray("activities") ?: JSONArray()
            val activities = buildList {
                for (index in 0 until activitiesJson.length()) {
                    val item = activitiesJson.optJSONObject(index) ?: continue
                    add(parseActivity(item))
                }
            }.sortedByDescending { it.startedAtMillis }
            val now = System.currentTimeMillis()
            val checkedAt = parseInstant(response.optString("checkedAt")) ?: now
            val courseCount = response.optInt("courseCount", 0)
            preferences.markScan(checkedAt, courseCount, activities.size)
            val newlyDiscovered = activities.filter { preferences.isNewActivity(it.activeId) }
            activities.firstOrNull()?.let { preferences.saveLatestActivity(JSONObject(it.rawJson)) }

            if (config.autoSign) {
                for (activity in activities) tryAutoSign(activity, config)
            }
            val activitiesWithStatus = activities.map { activity ->
                activity.copy(signed = preferences.isSignCompleted(activity.activeId))
            }
            val courseErrors = response.optJSONArray("courseErrors")?.length() ?: 0
            mutableState.value = loadPreferences().copy(
                loggedIn = true,
                accountName = preferences.accountName(),
                activities = activitiesWithStatus,
                latestActivity = activitiesWithStatus.firstOrNull(),
                error = if (courseErrors > 0) "$courseErrors 门课程扫描失败" else "",
            )
            ScanOutput(activities, newlyDiscovered, courseErrors)
        }
    }

    suspend fun signNow(activity: ActivityCard, manualInput: ManualSignInput = ManualSignInput()): JSONObject =
        withContext(Dispatchers.IO) {
            val config = preferences.config()
            check(config.selectedCoursesJson != "[]") { "请至少选择一门课程后再签到" }
            val currentActivity = requireFreshActivity(activity, config.selectedCoursesJson)
            val input = signInput(config, manualInput)
            val result = session.sign(currentActivity.toString(), input.toString())
            val succeeded = result.optBoolean("ok", false)
            preferences.markSign(succeeded)
            if (succeeded) preferences.markSignCompleted(activity.activeId)
            val updatedActivities = mutableState.value.activities.map { card ->
                if (card.activeId == activity.activeId) card.copy(signed = succeeded) else card
            }
            val updatedLatest = updatedActivities.firstOrNull()
                ?: mutableState.value.latestActivity?.let { card ->
                    if (card.activeId == activity.activeId) card.copy(signed = succeeded) else card
                }
            val currentCard = currentActivity.let(::parseActivity)
            mutableState.value = loadPreferences().copy(
                loggedIn = true,
                accountName = preferences.accountName(),
                activities = updatedActivities,
                latestActivity = updatedLatest ?: currentCard,
                error = if (succeeded) "" else result.optString("message", "签到未完成"),
                signNotice = if (succeeded) "${currentCard.kindName}签到成功" else "",
            )
            result
        }

    /** Fresh-checks the activity before an upload so stale cards do not orphan photos. */
    suspend fun uploadPhotoForActivity(activity: ActivityCard, mimeType: String, data: ByteArray): String =
        withContext(Dispatchers.IO) {
            val config = preferences.config()
            check(config.selectedCoursesJson != "[]") { "请至少选择一门课程后再上传照片" }
            requireFreshActivity(activity, config.selectedCoursesJson)
            session.uploadPanPhoto(mimeType, data)
        }

    private suspend fun requireFreshActivity(activity: ActivityCard, coursesJson: String): JSONObject {
        val response = session.checkActivities(coursesJson)
        // A present activity in a partial scan is fresh evidence; unrelated course errors do not block it.
        val items = response.optJSONArray("activities") ?: JSONArray()
        val fresh = (0 until items.length())
            .mapNotNull(items::optJSONObject)
            .firstOrNull { it.optString("activeId") == activity.activeId }
            ?: error("该签到活动已不在当前活动列表中，请重新巡检后再试")
        check(fresh.optInt("kind", activity.kind) == activity.kind) { "签到类型已变化，请重新打开活动卡" }
        return fresh
    }

    fun setMonitorWanted(value: Boolean) {
        preferences.setMonitorWanted(value)
        mutableState.value = loadPreferences().copy(loggedIn = mutableState.value.loggedIn)
    }

    fun setMode(value: MonitorMode) {
        preferences.setMode(value)
        mutableState.value = loadPreferences().copy(loggedIn = mutableState.value.loggedIn)
    }

    fun setBootRestore(value: Boolean) {
        preferences.setBootRestore(value)
        mutableState.value = loadPreferences().copy(loggedIn = mutableState.value.loggedIn)
    }

    fun setAutoSign(value: Boolean) {
        preferences.setAutoSign(value)
        mutableState.value = loadPreferences().copy(loggedIn = mutableState.value.loggedIn)
    }

    fun setIntervalSeconds(value: Int) {
        preferences.setIntervalSeconds(value)
        mutableState.value = loadPreferences().copy(loggedIn = mutableState.value.loggedIn)
    }

    fun saveCourseSelection(selected: List<CourseChoice>) {
        val array = JSONArray()
        selected.forEach { array.put(JSONObject(it.rawJson)) }
        preferences.saveSelectedCourses(array)
        mutableState.value = mutableState.value.copy(availableCourses = mutableState.value.availableCourses.map { row ->
            row.copy(selected = selected.any { it.courseId == row.courseId && it.classId == row.classId })
        })
    }

    fun signInParameters(): MonitorConfig = preferences.config()

    fun saveDefaultLocation(latitude: String, longitude: String, address: String) {
        preferences.saveLocation(latitude, longitude, address)
        mutableState.value = mutableState.value.copy(error = "")
    }

    fun reportError(message: String) {
        mutableState.value = mutableState.value.copy(error = message, signNotice = "")
    }

    fun dismissSignNotice() {
        mutableState.value = mutableState.value.copy(signNotice = "")
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        setMonitorWanted(false)
        session.logout()
        preferences.clearAccountState()
        mutableState.value = loadPreferences()
    }

    private suspend fun tryAutoSign(activity: ActivityCard, config: MonitorConfig) {
        if (preferences.isSignCompleted(activity.activeId) || preferences.signAttempts(activity.activeId) >= MAX_SIGN_ATTEMPTS) return
        if (!isAutoSignReady(activity.kind, config)) return
        preferences.markSignAttempt(activity.activeId)
        try {
            val result = session.sign(activity.rawJson, signInput(config).toString())
            val success = result.optBoolean("ok", false)
            preferences.markSign(success)
            if (success) preferences.markSignCompleted(activity.activeId)
        } catch (_: Exception) {
            // Retried on a later scan, capped by MAX_SIGN_ATTEMPTS.
        }
    }

    private fun signInput(config: MonitorConfig, manualInput: ManualSignInput = ManualSignInput()): JSONObject {
        val hasLocationOverride = !manualInput.latitude.isNullOrBlank() && !manualInput.longitude.isNullOrBlank()
        val latitude = if (hasLocationOverride) manualInput.latitude.orEmpty().trim() else config.latitude
        val longitude = if (hasLocationOverride) manualInput.longitude.orEmpty().trim() else config.longitude
        val address = if (hasLocationOverride) manualInput.address?.trim().orEmpty() else config.address
        return JSONObject()
            .put("signCode", manualInput.signCode.trim())
            .put("enc", manualInput.enc.trim())
            .put("photoObjectId", manualInput.photoObjectId.trim())
            .put(
                "location",
                JSONObject()
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("address", address),
            )
    }

    private fun parseCourses(array: JSONArray, selectedIds: Set<String>?): List<CourseChoice> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val courseId = item.optString("courseId")
            val classId = item.optString("classId")
            val key = courseKey(courseId, classId)
            val courseName = item.optString("courseName")
            val className = item.optString("className")
            val title = when {
                courseName.isBlank() -> className
                className.isBlank() || className == courseName -> courseName
                else -> "$courseName · $className"
            }
            add(CourseChoice(courseId, classId, title, selectedIds == null || key in selectedIds, item.toString()))
        }
    }

    private fun parseActivity(item: JSONObject): ActivityCard {
        val course = item.optJSONObject("course") ?: JSONObject()
        val courseName = course.optString("courseName")
        val className = course.optString("className")
        val title = when {
            courseName.isBlank() -> className
            className.isBlank() || className == courseName -> courseName
            else -> "$courseName · $className"
        }
        return ActivityCard(
            activeId = item.optString("activeId"),
            name = item.optString("name"),
            kind = item.optInt("kind", SignKinds.GENERAL),
            kindName = item.optString("kindName", "签到活动"),
            courseTitle = title,
            startedAtMillis = parseInstant(item.optString("startAt")) ?: 0L,
            rawJson = item.toString(),
            signed = preferences.isSignCompleted(item.optString("activeId")),
        )
    }

    private fun loadPreferences(): MonitorUiState {
        val config = preferences.config()
        val courses = runCatching {
            val available = JSONArray(preferences.availableCoursesJson())
            val selected = JSONArray(config.selectedCoursesJson)
            val selectedIds = buildSet {
                for (index in 0 until selected.length()) {
                    val item = selected.optJSONObject(index) ?: continue
                    add(courseKey(item.optString("courseId"), item.optString("classId")))
                }
            }
            parseCourses(available, selectedIds)
        }.getOrDefault(emptyList())
        val latest = runCatching { preferences.latestActivityJson()?.let { parseActivity(JSONObject(it)) } }.getOrNull()
        return MonitorUiState(
            accountName = preferences.accountName(),
            monitorWanted = config.monitorWanted,
            mode = config.mode,
            bootRestore = config.bootRestore,
            autoSign = config.autoSign,
            intervalSeconds = config.intervalSeconds,
            lastScanMillis = preferences.lastScanMillis(),
            scanCountToday = preferences.scanCount(),
            signCountToday = preferences.signCount(),
            availableCourses = courses,
            latestActivity = latest,
        )
    }

    private fun parseInstant(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun courseKey(courseId: String, classId: String) = "$courseId\u0000$classId"

    private companion object {
        const val MAX_SIGN_ATTEMPTS = 6
    }
}
