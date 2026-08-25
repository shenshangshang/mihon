package tachiyomi.source.komga

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class KomgaPreferences(
    context: Context,
) {
    private val preferenceStore: PreferenceStore = AndroidPreferenceStore(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val baseUrlPref: Preference<String> by lazy {
        preferenceStore.getString(KEY_BASE_URL, DEFAULT_BASE_URL)
    }
    private val usernamePref: Preference<String> by lazy {
        preferenceStore.getString(KEY_USERNAME)
    }
    private val passwordPref: Preference<String> by lazy {
        preferenceStore.getString(KEY_PASSWORD)
    }
    private val apiKeyPref: Preference<String> by lazy {
        preferenceStore.getString(KEY_API_KEY)
    }

    var baseUrl: String
        get() = baseUrlPref.get().removeSuffix("/")
        set(value) = baseUrlPref.set(value.removeSuffix("/"))

    var username: String
        get() = usernamePref.get()
        set(value) = usernamePref.set(value)

    var password: String
        get() = passwordPref.get()
        set(value) = passwordPref.set(value)

    var apiKey: String
        get() = apiKeyPref.get()
        set(value) = apiKeyPref.set(value)

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && (apiKey.isNotBlank() || (username.isNotBlank() && password.isNotBlank()))

    val isLoggedIn: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    fun baseUrlPreference(): Preference<String> = baseUrlPref
    fun usernamePreference(): Preference<String> = usernamePref
    fun passwordPreference(): Preference<String> = passwordPref
    fun apiKeyPreference(): Preference<String> = apiKeyPref

    companion object {
        const val PREFS_NAME = "komga_source"
        const val DEFAULT_BASE_URL = "https://komga.shenshang.online"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_API_KEY = "api_key"
    }
}
