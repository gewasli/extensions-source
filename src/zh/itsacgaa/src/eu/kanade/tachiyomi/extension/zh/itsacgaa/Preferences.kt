package eu.kanade.tachiyomi.extension.zh.itsacgaa

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen

const val DEFAULT_BASE_URL = "https://www.itsacgaa.online"

const val BASE_URL_PREF = "baseUrl"
const val USERNAME_PREF = "username"
const val PASSWORD_PREF = "password"
const val COOKIE_PREF = "cookies"

val SharedPreferences.baseUrl: String
    get() = getString(BASE_URL_PREF, DEFAULT_BASE_URL).orEmpty()

val SharedPreferences.savedCookies: String
    get() = getString(COOKIE_PREF, "").orEmpty()

val SharedPreferences.hasCookies: Boolean
    get() = savedCookies.isNotBlank()

/**
 * Adds an [EditTextPreference] to this screen. `onChange` is invoked when the
 * user commits a new value (only after it passed [validate]). `summaryOf`
 * transforms the committed value for display in the summary (e.g. masking).
 */
fun PreferenceScreen.addEditTextPreference(
    title: String,
    summary: String,
    key: String = title,
    default: String = "",
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    summaryOf: ((String) -> String)? = null,
    onChange: (String) -> Unit = {},
) {
    EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.dialogTitle = title
        this.dialogMessage = dialogMessage
        setDefaultValue(default)

        if (inputType != null) {
            setOnBindEditTextListener { it.inputType = inputType }
        }

        setOnPreferenceChangeListener { _, newValue ->
            val text = (newValue as? String).orEmpty()
            val ok = text.isBlank() || validate?.invoke(text) ?: true
            if (ok) {
                onChange(text)
                this.summary = summaryOf?.invoke(text) ?: text.ifBlank { default }
            }
            ok
        }
    }.also(::addPreference)
}
