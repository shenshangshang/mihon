package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.komga.KomgaPreferences

object SettingsKomgaScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val prefs = remember { KomgaPreferences(context) }

        var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
        var username by remember { mutableStateOf(prefs.username) }
        var password by remember { mutableStateOf(prefs.password) }
        var apiKey by remember { mutableStateOf(prefs.apiKey) }
        var testResult by remember { mutableStateOf<String?>(null) }
        var testing by remember { mutableStateOf(false) }

        return listOf(
            Preference.PreferenceGroup(
                title = "Komga 服务器",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = object : tachiyomi.core.common.preference.Preference<String> {
                            override fun key() = "komga_base_url"
                            override fun get(): String = baseUrl
                            override fun set(value: String) {
                                baseUrl = value
                                prefs.baseUrl = value
                            }
                            override fun isSet(): Boolean = baseUrl.isNotBlank()
                            override fun delete() {
                                baseUrl = ""
                                prefs.baseUrl = ""
                            }
                        },
                        title = "服务器地址",
                        subtitle = baseUrl.ifBlank { "例如: https://komga.example.com" },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = object : tachiyomi.core.common.preference.Preference<String> {
                            override fun key() = "komga_api_key"
                            override fun get(): String = apiKey
                            override fun set(value: String) {
                                apiKey = value
                                prefs.apiKey = value
                            }
                            override fun isSet(): Boolean = apiKey.isNotBlank()
                            override fun delete() {
                                apiKey = ""
                                prefs.apiKey = ""
                            }
                        },
                        title = "API Key",
                        subtitle = if (apiKey.isBlank()) "可选，优先使用" else "*".repeat(apiKey.length),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "或使用用户名密码",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = object : tachiyomi.core.common.preference.Preference<String> {
                            override fun key() = "komga_username"
                            override fun get(): String = username
                            override fun set(value: String) {
                                username = value
                                prefs.username = value
                            }
                            override fun isSet(): Boolean = username.isNotBlank()
                            override fun delete() {
                                username = ""
                                prefs.username = ""
                            }
                        },
                        title = "用户名",
                        subtitle = username.ifBlank { "邮箱或用户名" },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = object : tachiyomi.core.common.preference.Preference<String> {
                            override fun key() = "komga_password"
                            override fun get(): String = password
                            override fun set(value: String) {
                                password = value
                                prefs.password = value
                            }
                            override fun isSet(): Boolean = password.isNotBlank()
                            override fun delete() {
                                password = ""
                                prefs.password = ""
                            }
                        },
                        title = "密码",
                        subtitle = if (password.isBlank()) "账号密码" else "*".repeat(password.length),
                    ),
                ),
            ),
            Preference.PreferenceItem.TextPreference(
                title = "测试连接",
                subtitle = testResult ?: "点击测试 Komga 服务器连接",
                onClick = {
                    if (testing) return@TextPreference
                    testing = true
                    testResult = "测试中..."
                    scope.launch {
                        try {
                            val ok = tachiyomi.source.komga.api.KomgaApi(
                                okhttp3.OkHttpClient(),
                                prefs,
                            ).testConnection()
                            testResult = if (ok) "连接成功 ✓" else "连接失败 ✗"
                        } catch (e: Exception) {
                            testResult = "错误: ${e.message}"
                        }
                        testing = false
                    }
                },
            ),
        )
    }
}
