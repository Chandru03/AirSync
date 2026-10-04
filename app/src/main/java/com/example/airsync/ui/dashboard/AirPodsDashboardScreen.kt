package com.example.airsync.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.example.airsync.R
import com.example.airsync.domain.model.AirPodsDevice
import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.EqPreset
import com.example.airsync.domain.model.LastSeenLocation
import com.example.airsync.domain.repository.FindSoundTarget
import com.example.airsync.ui.dashboard.components.AutomationCard
import com.example.airsync.ui.dashboard.components.BackgroundCard
import com.example.airsync.ui.dashboard.components.ConnectionDetailsCard
import com.example.airsync.ui.dashboard.components.DeviceHeroCard
import com.example.airsync.ui.dashboard.components.FindCard
import com.example.airsync.ui.dashboard.components.ListeningCard
import com.example.airsync.ui.dashboard.components.NoiseControlCard
import com.example.airsync.ui.dashboard.components.SoundCard
import com.example.airsync.ui.main.DashboardUiState
import com.example.airsync.ui.common.EntranceHost
import com.example.airsync.ui.common.entrance

/** Every user intent the dashboard can raise. Keeps the screen stateless and previewable. */
class DashboardActions(
    val onNoiseMode: (AncMode) -> Unit,
    val onLevel: (Float) -> Unit,
    val onConversationalAwareness: (Boolean) -> Unit,
    val onRemoveToPause: (Boolean) -> Unit,
    val onHeadGestures: (Boolean) -> Unit,
    val onRequestNotificationAccess: () -> Unit,
    val onAutomation: (Boolean) -> Unit,
    val onRules: (Boolean, Boolean, Boolean) -> Unit,
    val onEqPreset: (EqPreset) -> Unit,
    val onAppEqPreset: (String, EqPreset?) -> Unit,
    val onBackground: (Boolean) -> Unit,
    val onEnableBluetooth: () -> Unit,
    val onBluetoothSettings: () -> Unit,
    val onConnect: () -> Unit,
    val onSelectDevice: (String?) -> Unit,
    val bondedDevices: () -> List<AirPodsDevice>,
    val onEnableLocation: () -> Unit,
    val onOpenMaps: (LastSeenLocation) -> Unit,
    val onPlaySound: (FindSoundTarget) -> Unit,
    val onStopSound: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onRequestBatteryExemption: () -> Unit
)

data class PermissionSnapshot(
    val location: Boolean,
    /** Notification access lets head gestures answer WhatsApp and other app calls. */
    val notificationAccess: Boolean,
    val notifications: Boolean,
    val batteryOptimized: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirPodsDashboardScreen(
    state: DashboardUiState,
    permissions: PermissionSnapshot,
    actions: DashboardActions,
    snackbarHostState: SnackbarHostState
) {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    // Fold 8 inner display ≈ 750 dp wide → medium class → two panes; cover screen → compact.
    val twoPane = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    // In book posture (half-folded) keep panes off the hinge.
    val hinge = adaptiveInfo.windowPosture.hingeList.firstOrNull { it.isVertical && it.isSeparating }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showDevicePicker by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_choose_device)) },
                            onClick = { menuOpen = false; showDevicePicker = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_bluetooth_settings)) },
                            onClick = { menuOpen = false; actions.onBluetoothSettings() }
                        )
                    }
                }
            )
        }
    ) { inner -> EntranceHost {
        val layoutDirection = LocalLayoutDirection.current
        val listPadding = { start: Dp, end: Dp ->
            PaddingValues(
                start = start, end = end,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 24.dp
            )
        }
        val sidePadding = inner.calculateStartPadding(layoutDirection).coerceAtLeast(16.dp)
        val chooseDevice = { showDevicePicker = true }

        if (twoPane) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val gutter = 24.dp
                val leftWidth = (hinge?.let { with(density) { it.bounds.left.toDp() } - sidePadding - gutter / 2 }
                    ?: ((maxWidth - sidePadding * 2 - gutter) * 0.44f))
                    .coerceIn(MinPaneWidth, (maxWidth - MinPaneWidth).coerceAtLeast(MinPaneWidth))
                val gap = hinge?.let { with(density) { it.bounds.width.toDp() } + gutter } ?: gutter
                Row(Modifier.fillMaxSize().padding(horizontal = sidePadding), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    // Left: device status. Right: controls.
                    LazyColumn(
                        Modifier.width(leftWidth).fillMaxHeight(),
                        contentPadding = listPadding(0.dp, 0.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) { statusPane(state, permissions, actions, chooseDevice) }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = listPadding(0.dp, 0.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) { controlsPane(state, permissions, actions) }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = listPadding(16.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Cap width so the cover screen in landscape doesn't stretch cards edge to edge.
                statusPane(state, permissions, actions, chooseDevice, onlyHero = true)
                controlsPane(state, permissions, actions)
                statusPane(state, permissions, actions, chooseDevice, skipHero = true)
            }
        }
    } }

    if (showDevicePicker) {
        DevicePickerDialog(
            devices = remember { actions.bondedDevices() },
            selected = state.settings.preferredDeviceAddress,
            onSelect = { actions.onSelectDevice(it); showDevicePicker = false },
            onDismiss = { showDevicePicker = false }
        )
    }
}

private val MinPaneWidth = 280.dp
private val CardWidth = Modifier.widthIn(max = 640.dp).fillMaxWidth()

private fun LazyListScope.statusPane(
    state: DashboardUiState,
    permissions: PermissionSnapshot,
    actions: DashboardActions,
    onChooseDevice: () -> Unit,
    onlyHero: Boolean = false,
    skipHero: Boolean = false
) {
    if (!skipHero) item("hero") {
        DeviceHeroCard(
            status = state.status,
            connecting = state.connecting,
            onConnect = actions.onConnect,
            onEnableBluetooth = actions.onEnableBluetooth,
            onOpenBluetoothSettings = actions.onBluetoothSettings,
            onChooseDevice = onChooseDevice,
            modifier = CardWidth.then(Modifier.animateItem()).entrance(0)
        )
    }
    if (onlyHero) return
    item("find") {
        FindCard(
            lastSeen = state.settings.lastSeen,
            connected = state.status.isConnected,
            locationGranted = permissions.location,
            soundPlaying = state.findSoundPlaying,
            onEnableLocation = actions.onEnableLocation,
            onOpenMaps = actions.onOpenMaps,
            onPlaySound = actions.onPlaySound,
            onStopSound = actions.onStopSound,
            modifier = CardWidth.then(Modifier.animateItem()).entrance(2)
        )
    }
    if (state.status.isConnected) item("details") {
        ConnectionDetailsCard(state.status, CardWidth.then(Modifier.animateItem()).entrance(3))
    }
}

private fun LazyListScope.controlsPane(
    state: DashboardUiState,
    permissions: PermissionSnapshot,
    actions: DashboardActions
) {
    item("noise") {
        NoiseControlCard(
            mode = state.status.noiseMode,
            enabled = state.status.isConnected,
            canControlHardware = state.status.canControlHardware,
            level = state.settings.transparencyLevel,
            onModeSelected = actions.onNoiseMode,
            onLevelChanged = actions.onLevel,
            modifier = CardWidth.then(Modifier.animateItem()).entrance(1)
        )
    }
    item("listening") {
        ListeningCard(
            status = state.status,
            settings = state.settings,
            onConversationalAwareness = actions.onConversationalAwareness,
            onRemoveToPause = actions.onRemoveToPause,
            onHeadGestures = actions.onHeadGestures,
            notificationAccess = permissions.notificationAccess,
            onRequestNotificationAccess = actions.onRequestNotificationAccess,
            modifier = CardWidth.entrance(2)
        )
    }
    item("sound") {
        SoundCard(
            spatialAudio = state.spatialAudio,
            airPodsConnected = state.status.isConnected,
            preset = state.settings.eqPreset,
            apps = state.eqApps,
            onPresetSelected = actions.onEqPreset,
            onAppPresetSelected = actions.onAppEqPreset,
            modifier = CardWidth.entrance(3)
        )
    }
    item("automation") {
        AutomationCard(
            settings = state.settings,
            automation = state.automation,
            onEnabled = actions.onAutomation,
            onRulesChanged = actions.onRules,
            modifier = CardWidth.entrance(4)
        )
    }
    item("background") {
        BackgroundCard(
            settings = state.settings,
            notificationsGranted = permissions.notifications,
            batteryOptimized = permissions.batteryOptimized,
            onBackground = actions.onBackground,
            onRequestNotifications = actions.onRequestNotifications,
            onRequestBatteryExemption = actions.onRequestBatteryExemption,
            modifier = CardWidth.entrance(5)
        )
    }
}

@Composable
private fun DevicePickerDialog(
    devices: List<AirPodsDevice>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_choose_device)) },
        text = {
            LazyColumn {
                item { PickerRow(stringResource(R.string.device_automatic), stringResource(R.string.device_automatic_desc), selected == null) { onSelect(null) } }
                devices.forEach { d ->
                    item(d.address) { PickerRow(d.name, d.address, selected == d.address) { onSelect(d.address) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } }
    )
}

@Composable
private fun PickerRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
