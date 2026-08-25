package tachiyomi.source.komga

import android.content.Context
import android.content.SharedPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
class KomgaPreferences(
    private val context: Context,
) {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("komga_source", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)!!.removeSuffix("/")
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.removeSuffix("/")).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "")!!
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "")!!
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "")!!
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && (apiKey.isNotBlank() || (username.isNotBlank() && password.isNotBlank()))

    val isLoggedIn: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://komga.shenshang.online"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_API_KEY = "api_key"
    }
}
