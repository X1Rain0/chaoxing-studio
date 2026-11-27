package com.x1rain0.chaoxingstudio.data

import android.content.Context
import com.x1rain0.chaoxingstudio.bridge.mobilebridge.Bridge
import com.x1rain0.chaoxingstudio.bridge.mobilebridge.Mobilebridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous gomobile calls stay on Dispatchers.IO; the Go facade serializes its own session. */
internal class GoSession(context: Context) {
    private val bridge: Bridge = Mobilebridge.newBridge()
    private val vault = CredentialVault(context)
    @Volatile private var authenticated = false

    suspend fun login(phone: String, password: String): JSONObject = withContext(Dispatchers.IO) {
        val response = JSONObject(bridge.login(phone.trim(), password))
        vault.save(phone.trim(), password)
        authenticated = true
        response
    }

    suspend fun restoreSession(): JSONObject? = withContext(Dispatchers.IO) {
        val saved = vault.load() ?: return@withContext null
        val response = JSONObject(bridge.login(saved.phone, saved.password))
        authenticated = true
        response
    }

    suspend fun courses(): JSONArray = withContext(Dispatchers.IO) {
        ensureAuthenticated()
        JSONArray(bridge.courses())
    }

    suspend fun checkActivities(coursesJson: String): JSONObject = withContext(Dispatchers.IO) {
        ensureAuthenticated()
        JSONObject(bridge.checkActivities(coursesJson))
    }

    suspend fun uploadPanPhoto(mimeType: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        ensureAuthenticated()
        bridge.uploadPanPhoto(mimeType, data)
    }

    suspend fun sign(activityJson: String, inputJson: String): JSONObject = withContext(Dispatchers.IO) {
        ensureAuthenticated()
        JSONObject(bridge.sign(activityJson, inputJson))
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        bridge.logout()
        authenticated = false
        vault.clear()
    }

    private suspend fun ensureAuthenticated() {
        if (authenticated) return
        val restored = restoreSession()
        check(restored != null) { "请先登录学习通账号" }
    }
}
