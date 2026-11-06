package com.example.remotescreen

import android.content.Context
import java.security.SecureRandom

// يحفظ رمز الجهاز الثابت وسرّه، وأكواد الثقة للأجهزة التي وافقنا عليها
class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val deviceId: String? get() = prefs.getString("id", null)

    val secret: String
        get() = prefs.getString("secret", null) ?: newSecret().also {
            prefs.edit().putString("secret", it).apply()
        }

    fun saveId(id: String) = prefs.edit().putString("id", id).apply()

    fun trustToken(peerId: String): String? = prefs.getString("trust_$peerId", null)

    fun saveTrustToken(peerId: String, token: String) =
        prefs.edit().putString("trust_$peerId", token).apply()

    private fun newSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
