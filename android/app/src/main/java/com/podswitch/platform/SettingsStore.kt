package com.podswitch.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.podswitch.core.Category
import com.podswitch.core.Config
import com.podswitch.core.Mode
import com.podswitch.core.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "podswitch_settings")

/** DataStore-backed configuration store implementing the [SettingsStore] port. */
class AndroidSettingsStore(
    private val context: Context,
) : SettingsStore {

    val configFlow: Flow<Config> = context.dataStore.data.map { it.toConfig() }

    override fun currentConfig(): Config = runBlocking {
        context.dataStore.data.first().toConfig()
    }

    /** Stable per-install identifier used to distinguish this device in LAN coordination. */
    fun deviceId(): String = runBlocking {
        context.dataStore.data.first()[KEY_DEVICE_ID] ?: java.util.UUID.randomUUID().toString().also { id ->
            context.dataStore.edit { it[KEY_DEVICE_ID] = id }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setMode(mode: Mode) {
        context.dataStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setCategoryEnabled(category: Category, enabled: Boolean) {
        context.dataStore.edit { it[categoryKey(category)] = enabled }
    }

    suspend fun setYieldToOtherSource(enabled: Boolean) {
        context.dataStore.edit { it[KEY_YIELD] = enabled }
    }

    suspend fun setTarget(address: String?, name: String?) {
        context.dataStore.edit { prefs ->
            if (address == null) {
                prefs.remove(KEY_TARGET_ADDRESS)
                prefs.remove(KEY_TARGET_NAME)
            } else {
                prefs[KEY_TARGET_ADDRESS] = address
                prefs[KEY_TARGET_NAME] = name ?: address
            }
        }
    }

    /** Persisted display name of the target device, or null if unconfigured. */
    val targetNameFlow: Flow<String?> =
        context.dataStore.data.map { it[KEY_TARGET_NAME] }

    private fun Preferences.toConfig(): Config {
        val categories = buildSet {
            if (this@toConfig[KEY_CAT_MEDIA] != false) add(Category.MEDIA)
            if (this@toConfig[KEY_CAT_CALL] == true) add(Category.CALL)
            if (this@toConfig[KEY_CAT_NOTIFICATION] == true) add(Category.NOTIFICATION)
        }
        val mode = this[KEY_MODE]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.ASK
        return Config(
            enabled = this[KEY_ENABLED] ?: false,
            mode = mode,
            enabledCategories = categories,
            targetDeviceId = this[KEY_TARGET_ADDRESS],
            yieldToOtherSource = this[KEY_YIELD] ?: false,
        )
    }

    private fun categoryKey(category: Category): Preferences.Key<Boolean> = when (category) {
        Category.MEDIA -> KEY_CAT_MEDIA
        Category.CALL -> KEY_CAT_CALL
        Category.NOTIFICATION -> KEY_CAT_NOTIFICATION
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_CAT_MEDIA = booleanPreferencesKey("cat_media")
        val KEY_CAT_CALL = booleanPreferencesKey("cat_call")
        val KEY_CAT_NOTIFICATION = booleanPreferencesKey("cat_notification")
        val KEY_TARGET_ADDRESS = stringPreferencesKey("target_address")
        val KEY_TARGET_NAME = stringPreferencesKey("target_name")
        val KEY_YIELD = booleanPreferencesKey("yield_to_other_source")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    }
}
