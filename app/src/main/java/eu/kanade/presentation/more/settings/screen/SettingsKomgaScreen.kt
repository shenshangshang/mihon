package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.source.komga.KomgaPreferences

object SettingsKomgaScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.app_name

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val prefs = remember { KomgaPreferences(context) }

        var testResult by remember { mutableStateOf<String?>(null) }
        var testing by remember { mutableStateOf(false) }

        return listOf(
            Preference.PreferenceGroup(
                title = "服务器",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "服务器地址",
                        subtitle = KomgaPreferences.DEFAULT_BASE_URL,
                        onClick = {},
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.usernamePreference(),
                        title = "用户名",
                        subtitle = prefs.username.ifBlank { "邮箱或用户名" },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.passwordPreference(),
                        title = "密码",
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "高级",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.apiKeyPreference(),
                        title = "API Key",
                        subtitle = if (prefs.apiKey.isBlank()) "可选，优先于用户名密码" else "*".repeat(prefs.apiKey.length),
                    ),
                ),
            ),
            Preference.PreferenceItem.TextPreference(
                title = "测试连接",
                subtitle = testResult ?: "点击测试服务器连接",
                onClick = {
                    if (testing) return@TextPreference
                    testing = true
                    testResult = "测试中..."
                    scope.launch {
                        try {
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val ok = tachiyomi.source.komga.api.KomgaApi(
                                client,
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
