package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.FPSReducerApp
import com.example.ui.theme.FPSReducerTheme
import com.example.viewmodel.VideoViewModel

class MainActivity : ComponentActivity() {
    private val videoViewModel: VideoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FPSReducerTheme {
                FPSReducerApp(viewModel = videoViewModel)
            }
        }
    }
}
