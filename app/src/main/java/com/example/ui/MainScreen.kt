@file:OptIn(ExperimentalPermissionsApi::class)

package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.ui.bulk.BulkOcrScreen
import com.example.ui.camera.CameraScreen
import com.example.ui.no_permission.NoPermissionScreen
import com.example.ui.saved.SavedListScreen
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

private enum class MainDestination {
    CAMERA,
    SAVED_LIST,
    BULK_OCR
}

@Composable
fun MainScreen() {

    val cameraPermissionState: PermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    MainContent(
        hasPermission = cameraPermissionState.status.isGranted,
        onRequestPermission = cameraPermissionState::launchPermissionRequest
    )
}

@Composable
private fun MainContent(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    var currentDestination by rememberSaveable { mutableStateOf(MainDestination.CAMERA) }

    if (hasPermission) {
        when (currentDestination) {
            MainDestination.CAMERA -> {
                CameraScreen(
                    onNavigateToSavedList = { currentDestination = MainDestination.SAVED_LIST },
                    onNavigateToBulkOcr = { currentDestination = MainDestination.BULK_OCR }
                )
            }
            MainDestination.SAVED_LIST -> {
                SavedListScreen(
                    onBack = { currentDestination = MainDestination.CAMERA }
                )
            }
            MainDestination.BULK_OCR -> {
                BulkOcrScreen(
                    onBack = { currentDestination = MainDestination.CAMERA }
                )
            }
        }
    } else {
        NoPermissionScreen(onRequestPermission)
    }
}
