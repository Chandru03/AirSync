package com.example.airsync.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ComponentName
import com.example.airsync.service.CallNotificationListener
import com.example.airsync.service.IncomingCallTracker
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.airsync.domain.model.LastSeenLocation
import com.example.airsync.ui.dashboard.AirPodsDashboardScreen
import com.example.airsync.ui.dashboard.DashboardActions
import com.example.airsync.ui.dashboard.PermissionSnapshot
import com.example.airsync.ui.onboarding.OnboardingScreen

/** Permissions without which the app can't detect AirPods at all. */
private val RequiredPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable
fun AirSyncRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as Activity
    // Bumped on resume so permission-derived UI refreshes after the user visits Settings.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val hasRequired = remember(resumeTick) { RequiredPermissions.all { context.granted(it) } }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val requiredLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        requestedOnce = true
        resumeTick++
        // Notifications are optional: only the Bluetooth permissions gate the app.
        viewModel.onPermissionsChanged()
        permanentlyDenied = RequiredPermissions.any {
            result[it] != true && !context.granted(it) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeTick++
    }

    if (!hasRequired) {
        OnboardingScreen(
            permanentlyDenied = permanentlyDenied && requestedOnce,
            onContinue = {
                val perms = RequiredPermissions.toMutableList()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
                requiredLauncher.launch(perms.toTypedArray())
            },
            onOpenSettings = { context.openAppSettings() }
        )
        return
    }

    LifecycleStartEffect(Unit) {
        viewModel.onVisibilityChanged(true)
        onStopOrDispose { viewModel.onVisibilityChanged(false) }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(resources.getString(it)) }
    }

    // Feature permissions are requested in context, when the user turns the feature on.
    var pendingAfterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val featureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick++
        pendingAfterGrant?.invoke()
        pendingAfterGrant = null
    }
    fun withPermissions(perms: List<String>, action: () -> Unit) {
        val missing = perms.filterNot { context.granted(it) }
        if (missing.isEmpty()) action() else {
            pendingAfterGrant = action
            featureLauncher.launch(missing.toTypedArray())
        }
    }

    val permissions = remember(resumeTick) {
        PermissionSnapshot(
            notificationAccess = IncomingCallTracker.hasAccess(context),
            location = context.granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                context.granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryOptimized = !context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        )
    }

    val actions = DashboardActions(
        onNoiseMode = viewModel::setNoiseMode,
        onLevel = viewModel::setTransparencyLevel,
        // Conversation Awareness runs on the AirPods' own microphones, so no phone-mic permission.
        onConversationalAwareness = viewModel::setConversationalAwareness,
        onRemoveToPause = viewModel::setRemoveToPause,
        onHeadGestures = { enabled ->
            // Phone permissions cover SIM calls; notification access (prompted on the card)
            // covers WhatsApp and other app calls. Either is enough to turn the feature on.
            if (enabled) withPermissions(
                listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
            ) {
                viewModel.setHeadGestures(true)
                if (!IncomingCallTracker.hasAccess(context)) context.openNotificationAccess()
            } else viewModel.setHeadGestures(false)
        },
        onRequestNotificationAccess = { context.openNotificationAccess() },
        onAutomation = { enabled ->
            if (enabled) {
                val perms = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
                    add(Manifest.permission.RECORD_AUDIO)
                }
                withPermissions(perms) { viewModel.setAutomationEnabled(true) }
            } else viewModel.setAutomationEnabled(false)
        },
        onRules = viewModel::setAutomationRules,
        onEqPreset = viewModel::setEqPreset,
        onAppEqPreset = viewModel::setAppEqPreset,
        onBackground = viewModel::setBackgroundEnabled,
        onEnableBluetooth = { context.requestEnableBluetooth() },
        onBluetoothSettings = { context.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        onConnect = {
            viewModel.connect(
                onBluetoothOff = { context.requestEnableBluetooth() },
                onNotPaired = { context.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            )
        },
        onSelectDevice = viewModel::selectDevice,
        bondedDevices = viewModel::bondedDevices,
        onEnableLocation = {
            withPermissions(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) { viewModel.refreshLocation() }
        },
        onOpenMaps = { context.openMaps(it) },
        onPlaySound = viewModel::playFindSound,
        onStopSound = viewModel::stopFindSound,
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onRequestBatteryExemption = { context.requestBatteryExemption() }
    )

    AirPodsDashboardScreen(state, permissions, actions, snackbar)
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.safeStart(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}

/** Opens this app's notification-access toggle directly where supported. */
private fun Context.openNotificationAccess() {
    val component = ComponentName(this, CallNotificationListener::class.java)
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    } else null
    try {
        startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

private fun Context.openAppSettings() =
    safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

@SuppressLint("MissingPermission")
private fun Context.requestEnableBluetooth() = safeStart(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

@SuppressLint("BatteryLife")
private fun Context.requestBatteryExemption() = safeStart(
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
)

/** Prefers a maps app via geo: URI; falls back to the browser. */
private fun Context.openMaps(location: LastSeenLocation) {
    val label = Uri.encode("AirPods")
    val geo = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}($label)")
    )
    try {
        startActivity(geo)
    } catch (_: ActivityNotFoundException) {
        safeStart(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}")
            )
        )
    }
}
