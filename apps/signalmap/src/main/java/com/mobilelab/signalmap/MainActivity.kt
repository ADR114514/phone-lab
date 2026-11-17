package com.mobilelab.signalmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.mobilelab.core.ui.theme.LabTheme
import com.mobilelab.signalmap.vm.SurveyViewModel

/**
 * Single activity host. The whole survey workflow lives in [SurveyViewModel] so
 * rotation (configChanges) and process death both resume from persisted state.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: SurveyViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[SurveyViewModel::class.java]
        setContent {
            LabTheme {
                SignalMapApp(viewModel)
            }
        }
    }
}
