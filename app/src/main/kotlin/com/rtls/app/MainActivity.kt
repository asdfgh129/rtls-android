package com.rtls.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rtls.app.ui.RtlsApp
import com.rtls.app.ui.theme.RtlsTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RtlsApplication).container
        setContent {
            RtlsTheme {
                RtlsApp(container)
            }
        }
    }
}
