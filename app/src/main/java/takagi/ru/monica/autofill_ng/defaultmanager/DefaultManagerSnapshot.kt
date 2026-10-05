package takagi.ru.monica.autofill_ng.defaultmanager

import android.os.Bundle
import org.json.JSONObject
import takagi.ru.monica.BuildConfig

internal object MonicaDefaultServices {
    val credential = "${BuildConfig.APPLICATION_ID}/takagi.ru.monica.passkey.MonicaCredentialProviderService"
    val autofill = "${BuildConfig.APPLICATION_ID}/takagi.ru.monica.autofill_ng.MonicaAutofillServiceNg"
}

internal fun SettingsSnapshot.toBundle() = Bundle().apply {
    putInt("userId", userId)
    SettingKey.entries.forEach { putString(it.wireName, this@toBundle[it]) }
}

internal fun Bundle.toSnapshot(): SettingsSnapshot {
    require(containsKey("userId") && SettingKey.entries.all { containsKey(it.wireName) })
    val values = SettingKey.entries.associateWith { getString(it.wireName) }
    values.forEach { (key, value) ->
        require((value?.length ?: 0) <= 32768)
        require(key != SettingKey.AUTOFILL || components(value).size <= 1)
        components(value)
    }
    return SettingsSnapshot(getInt("userId"), values)
}

internal fun SettingsSnapshot.toJson(): String = JSONObject().apply {
    put("userId", userId)
    SettingKey.entries.forEach { put(it.wireName, this@toJson[it] ?: JSONObject.NULL) }
}.toString()

internal fun snapshotFromJson(text: String): SettingsSnapshot {
    require(text.length <= 100_000)
    val json = JSONObject(text)
    return Bundle().apply {
        putInt("userId", json.getInt("userId"))
        SettingKey.entries.forEach {
            require(json.has(it.wireName))
            putString(it.wireName, if (json.isNull(it.wireName)) null else json.getString(it.wireName))
        }
    }.toSnapshot()
}
