package com.podswitch.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.podswitch.SwitchService
import com.podswitch.core.Category
import com.podswitch.core.Config
import com.podswitch.core.Mode
import com.podswitch.platform.AndroidBluetoothConnector
import com.podswitch.platform.AndroidSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Immutable UI state for [ConfigScreen]. */
data class ConfigUiState(
    val config: Config = Config(
        enabled = false,
        mode = Mode.ASK,
        enabledCategories = setOf(Category.MEDIA),
        targetDeviceId = null,
    ),
    val targetName: String? = null,
    val bondedDevices: List<AndroidBluetoothConnector.BondedDevice> = emptyList(),
)

/** Backs [ConfigScreen]: exposes config/devices/target name and mutates settings. */
class ConfigViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AndroidSettingsStore(app)
    private val connector = AndroidBluetoothConnector(app)

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.configFlow.collect { config ->
                _uiState.value = _uiState.value.copy(config = config)
            }
        }
        viewModelScope.launch {
            settings.targetNameFlow.collect { name ->
                _uiState.value = _uiState.value.copy(targetName = name)
            }
        }
    }

    /** Refresh the bonded-device list (call after BLUETOOTH_CONNECT is granted). */
    fun refreshDevices() {
        _uiState.value = _uiState.value.copy(bondedDevices = connector.bondedDevices())
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(enabled)
            if (enabled) startService() else stopService()
        }
    }

    fun setMode(mode: Mode) {
        viewModelScope.launch { settings.setMode(mode) }
    }

    fun setCategoryEnabled(category: Category, enabled: Boolean) {
        viewModelScope.launch { settings.setCategoryEnabled(category, enabled) }
    }

    fun selectDevice(device: AndroidBluetoothConnector.BondedDevice) {
        viewModelScope.launch { settings.setTarget(device.address, device.name) }
    }

    private fun startService() {
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(app, Intent(app, SwitchService::class.java))
    }

    private fun stopService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, SwitchService::class.java))
    }
}
