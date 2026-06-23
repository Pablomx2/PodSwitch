package com.podswitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podswitch.R
import com.podswitch.core.Category
import com.podswitch.core.Mode
import com.podswitch.platform.AndroidBluetoothConnector

private val ScreenPadding = 20.dp
private val SectionGap = 16.dp
private val CardCorner = 20.dp

/** The single configuration screen rendering [ConfigUiState] and forwarding user actions. */
@Composable
fun ConfigScreen(
    state: ConfigUiState,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (Mode) -> Unit,
    onYieldChange: (Boolean) -> Unit,
    onCategoryChange: (Category, Boolean) -> Unit,
    onDeviceSelect: (AndroidBluetoothConnector.BondedDevice) -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        HeaderBand(
            enabled = state.config.enabled,
            onEnabledChange = onEnabledChange,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            ModeCard(
                mode = state.config.mode,
                onModeChange = onModeChange,
                yieldToOtherSource = state.config.yieldToOtherSource,
                onYieldChange = onYieldChange,
            )

            TriggersCard(
                enabledCategories = state.config.enabledCategories,
                onCategoryChange = onCategoryChange,
            )

            DeviceCard(
                devices = state.bondedDevices,
                selectedAddress = state.config.targetDeviceId,
                onDeviceSelect = onDeviceSelect,
            )

            BatteryTipCard(onOpenBatterySettings = onOpenBatterySettings)

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeaderBand(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandGradient)
            .padding(horizontal = ScreenPadding, vertical = 28.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = stringResource(R.string.ui_master_enable),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color.White.copy(alpha = 0.35f),
                    checkedBorderColor = Color.White,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.6f),
                ),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun ModeCard(
    mode: Mode,
    onModeChange: (Mode) -> Unit,
    yieldToOtherSource: Boolean,
    onYieldChange: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.ui_mode_header)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeChoiceCard(
                label = stringResource(R.string.ui_mode_steal),
                selected = mode == Mode.STEAL,
                onClick = { onModeChange(Mode.STEAL) },
                modifier = Modifier.weight(1f),
            )
            ModeChoiceCard(
                label = stringResource(R.string.ui_mode_ask),
                selected = mode == Mode.ASK,
                onClick = { onModeChange(Mode.ASK) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ui_yield_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ui_yield_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = yieldToOtherSource, onCheckedChange = onYieldChange)
        }
    }
}

@Composable
private fun ModeChoiceCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = modifier
            .height(100.dp)
            .selectable(selected = selected, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            SelectionBadge(selected = selected)
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
            )
        }
    }
}

/** A 24dp slot that becomes a filled primary check disc when [selected]. */
@Composable
private fun SelectionBadge(selected: Boolean) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TriggersCard(
    enabledCategories: Set<Category>,
    onCategoryChange: (Category, Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.ui_categories_header)) {
        TriggerRow(
            label = stringResource(R.string.ui_category_media),
            checked = Category.MEDIA in enabledCategories,
            onCheckedChange = { onCategoryChange(Category.MEDIA, it) },
        )
        TriggerRow(
            label = stringResource(R.string.ui_category_call),
            checked = Category.CALL in enabledCategories,
            onCheckedChange = { onCategoryChange(Category.CALL, it) },
        )
        TriggerRow(
            label = stringResource(R.string.ui_category_notification),
            checked = Category.NOTIFICATION in enabledCategories,
            onCheckedChange = { onCategoryChange(Category.NOTIFICATION, it) },
        )
    }
}

@Composable
private fun TriggerRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DeviceCard(
    devices: List<AndroidBluetoothConnector.BondedDevice>,
    selectedAddress: String?,
    onDeviceSelect: (AndroidBluetoothConnector.BondedDevice) -> Unit,
) {
    SectionCard(title = stringResource(R.string.ui_device_header)) {
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_device_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        selected = device.address == selectedAddress,
                        onClick = { onDeviceSelect(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: AndroidBluetoothConnector.BondedDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun BatteryTipCard(onOpenBatterySettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(containerColor = PodSwitchColors.AmberContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PodSwitchColors.Amber),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_battery_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PodSwitchColors.OnAmberContainer,
                )
                FilledTonalButton(onClick = onOpenBatterySettings) {
                    Text(stringResource(R.string.ui_battery_button))
                }
            }
        }
    }
}
