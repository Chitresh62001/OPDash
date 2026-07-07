package com.opdash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opdash.ui.MainScreen
import com.opdash.ui.MainViewModel
import com.opdash.ui.theme.OPDashV2Theme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OPDashV2Theme {
                val vm: MainViewModel = viewModel()
                MainScreen(vm)
            }
        }
    }
}