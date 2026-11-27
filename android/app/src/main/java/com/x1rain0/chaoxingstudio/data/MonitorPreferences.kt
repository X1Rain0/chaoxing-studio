package com.x1rain0.chaoxingstudio.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal enum class MonitorMode { FOREGROUND, BACKGROUND }

internal data class MonitorConfig(
    val monitorWanted: Boolean,
    val mode: MonitorMode,
    val bootRestore: Boolean,
    val autoSign: Boolean,
    val intervalSeconds: Int,
    val selectedCoursesJson: String,
    val latitude: String,
    val longitude: String,
    val address: String,
)

/** Persists non-secret Android preferences. Credentials are stored only by CredentialVault. */
internal class MonitorPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateEphemeralSignInputs()
    }

    @Synchronized
    fun config(): MonitorConfig = MonitorConfig(
        monitorWanted = prefs.getBoolean(KEY_MONITOR_WANTED, false),
        mode = runCatching { MonitorMode.valueOf(prefs.getString(KEY_MODE, null) ?: "FOREGROUND") }
            .getOrDefault(MonitorMode.FOREGROUND),
        bootRestore = prefs.getBoolean(KEY_BOOT_RESTORE, false),
        autoSign = prefs.getBoolean(KEY_AUTO_SIGN, false),
        intervalSeconds = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL).coerceIn(MIN_INTERVAL, MAX_INTERVAL),
        selectedCoursesJson = prefs.getString(KEY_COURSES, "[]") ?: "[]",
        latitude = prefs.getString(KEY_LATITUDE, "") ?: "",
        longitude = prefs.getString(KEY_LONGITUDE, "") ?: "",
        address = prefs.getString(KEY_ADDRESS, "") ?: "",
    )

    @Synchronized
    fun setMonitorWanted(value: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR_WANTED, value).apply()
    }

    @Synchronized
    fun setMode(value: MonitorMode) {
        prefs.edit().putString(KEY_MODE, value.name).apply()
    }

    @Synchronized
    fun setBootRestore(value: Boolean) {
        prefs.edit().putBoolean(KEY_BOOT_RESTORE, value).apply()
    }

    @Synchronized
    fun setAutoSign(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SIGN, value).apply()
    }

    @Synchronized
    fun setIntervalSeconds(value: Int) {
        prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(MIN_INTERVAL, MAX_INTERVAL)).apply()
    }

    @Synchronized
    fun saveAvailableCourses(courses: JSONArray) {
        prefs.edit().putString(KEY_AVAILABLE_COURSES, courses.toString()).apply()
    }

    @Synchronized
    fun availableCoursesJson(): String = prefs.getString(KEY_AVAILABLE_COURSES, "[]") ?: "[]"

    @Synchronized
    fun saveSelectedCourses(courses: JSONArray) {
        prefs.edit().putString(KEY_COURSES, courses.toString()).apply()
    }

    @Synchronized
    fun hasSelectedCourseSelection(): Boolean = prefs.contains(KEY_COURSES)

    @Synchronized
    fun accountName(): String = prefs.getString(KEY_ACCOUNT_NAME, "") ?: ""

    @Synchronized
    fun saveAccountName(value: String) {
        prefs.edit().putString(KEY_ACCOUNT_NAME, value).apply()
    }

    @Synchronized
    fun saveLocation(latitude: String, longitude: String, address: String) {
        prefs.edit()
            .putString(KEY_LATITUDE, latitude.trim())
            .putString(KEY_LONGITUDE, longitude.trim())
            .putString(KEY_ADDRESS, address.trim())
            .apply()
    }

    @Synchronized
    fun markScan(checkedAtMillis: Long, courseCount: Int, activityCount: Int) {
        rollCountersIfDayChanged()
        prefs.edit()
            .putLong(KEY_LAST_SCAN, checkedAtMillis)
            .putInt(KEY_SCAN_COUNT, prefs.getInt(KEY_SCAN_COUNT, 0) + 1)
            .putInt(KEY_LAST_COURSE_COUNT, courseCount)
            .putInt(KEY_LAST_ACTIVITY_COUNT, activityCount)
            .apply()
    }

    @Synchronized
    fun lastScanMillis(): Long = prefs.getLong(KEY_LAST_SCAN, 0L)

    @Synchronized
    fun scanCount(): Int {
        rollCountersIfDayChanged()
        return prefs.getInt(KEY_SCAN_COUNT, 0)
    }

    @Synchronized
    fun markSign(success: Boolean) {
        if (success) {
            rollCountersIfDayChanged()
            prefs.edit().putInt(KEY_SIGN_COUNT, prefs.getInt(KEY_SIGN_COUNT, 0) + 1).apply()
        }
    }

    @Synchronized
    fun signCount(): Int {
        rollCountersIfDayChanged()
        return prefs.getInt(KEY_SIGN_COUNT, 0)
    }

    @Synchronized
    fun isSignCompleted(activeId: String): Boolean =
        prefs.getStringSet(KEY_SIGNED_IDS, emptySet()).orEmpty().contains(activeId)

    @Synchronized
    fun markSignCompleted(activeId: String) {
        val ids = prefs.getStringSet(KEY_SIGNED_IDS, emptySet()).orEmpty().toMutableSet()
        ids.add(activeId)
        val attempts = readSignAttempts().apply { remove(activeId) }
        prefs.edit()
            .putStringSet(KEY_SIGNED_IDS, ids.apply { while (size > MAX_SEEN_IDS) remove(first()) }.toSet())
            .putString(KEY_SIGN_ATTEMPTS, attempts.toString())
            .apply()
    }

    @Synchronized
    fun signAttempts(activeId: String): Int = readSignAttempts().optInt(activeId, 0)

    @Synchronized
    fun markSignAttempt(activeId: String) {
        val attempts = readSignAttempts()
        attempts.put(activeId, attempts.optInt(activeId, 0) + 1)
        val keys = attempts.keys().asSequence().toList()
        keys.take((keys.size - MAX_SEEN_IDS).coerceAtLeast(0)).forEach(attempts::remove)
        prefs.edit().putString(KEY_SIGN_ATTEMPTS, attempts.toString()).apply()
    }

    @Synchronized
    fun isNewActivity(activeId: String): Boolean {
        if (activeId.isBlank()) return false
        val ids = prefs.getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty().toMutableSet()
        if (!ids.add(activeId)) return false
        // Bound persistent state; old IDs are only deduplication hints, not a record archive.
        val bounded = ids.apply { while (size > MAX_SEEN_IDS) remove(first()) }.toSet()
        prefs.edit().putStringSet(KEY_SEEN_IDS, bounded).apply()
        return true
    }

    @Synchronized
    fun saveLatestActivity(rawJson: JSONObject) {
        prefs.edit().putString(KEY_LATEST_ACTIVITY, rawJson.toString()).apply()
    }

    @Synchronized
    fun latestActivityJson(): String? = prefs.getString(KEY_LATEST_ACTIVITY, null)

    @Synchronized
    fun clearAccountState() {
        prefs.edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_AVAILABLE_COURSES)
            .remove(KEY_COURSES)
            .remove(KEY_LATEST_ACTIVITY)
            .remove(KEY_SEEN_IDS)
            .remove(KEY_SIGNED_IDS)
            .remove(KEY_SIGN_ATTEMPTS)
            .remove(KEY_LAST_SCAN)
            .remove(KEY_COUNT_DAY)
            .remove(KEY_SCAN_COUNT)
            .remove(KEY_SIGN_COUNT)
            .remove(KEY_SIGN_CODE)
            .remove(KEY_ENC)
            .putBoolean(KEY_MONITOR_WANTED, false)
            .apply()
    }

    private fun migrateEphemeralSignInputs() {
        if (prefs.getBoolean(KEY_EPHEMERAL_INPUTS_CLEARED, false)) return
        prefs.edit()
            .remove(KEY_SIGN_CODE)
            .remove(KEY_ENC)
            .putBoolean(KEY_EPHEMERAL_INPUTS_CLEARED, true)
            .apply()
    }

    private fun readSignAttempts(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_SIGN_ATTEMPTS, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    private fun rollCountersIfDayChanged() {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
            .format(java.util.Date())
        if (prefs.getString(KEY_COUNT_DAY, null) != today) {
            prefs.edit()
                .putString(KEY_COUNT_DAY, today)
                .putInt(KEY_SCAN_COUNT, 0)
                .putInt(KEY_SIGN_COUNT, 0)
                .apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "monitor_preferences"
        const val DEFAULT_INTERVAL = 60
        const val MIN_INTERVAL = 30
        const val MAX_INTERVAL = 300
        const val MAX_SEEN_IDS = 300
        const val KEY_MONITOR_WANTED = "monitor_wanted"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val KEY_MODE = "mode"
        const val KEY_BOOT_RESTORE = "boot_restore"
        const val KEY_AUTO_SIGN = "auto_sign"
        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_COURSES = "selected_courses_json"
        const val KEY_AVAILABLE_COURSES = "available_courses_json"
        const val KEY_SIGN_CODE = "sign_code"
        const val KEY_ENC = "enc"
        const val KEY_EPHEMERAL_INPUTS_CLEARED = "ephemeral_sign_inputs_cleared_v1"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ADDRESS = "address"
        const val KEY_LAST_SCAN = "last_scan_millis"
        const val KEY_SCAN_COUNT = "scan_count"
        const val KEY_SIGN_COUNT = "sign_count"
        const val KEY_LAST_COURSE_COUNT = "last_course_count"
        const val KEY_LAST_ACTIVITY_COUNT = "last_activity_count"
        const val KEY_SEEN_IDS = "seen_active_ids"
        const val KEY_SIGNED_IDS = "signed_active_ids"
        const val KEY_SIGN_ATTEMPTS = "sign_attempts"
        const val KEY_COUNT_DAY = "counter_day"
        const val KEY_LATEST_ACTIVITY = "latest_activity_json"
    }
}
