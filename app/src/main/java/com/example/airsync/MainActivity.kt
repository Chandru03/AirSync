package com.example.airsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.airsync.ui.main.AirSyncRoot
import com.example.airsync.ui.main.MainViewModel
import com.example.airsync.ui.theme.AirSyncTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirSyncTheme {
                AirSyncRoot(viewModel)
            }
        }
    }
}
