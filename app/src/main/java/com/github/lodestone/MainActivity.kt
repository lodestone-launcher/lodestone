package com.github.lodestone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.lodestone.ui.LodestoneApp
import com.github.lodestone.ui.theme.LodestoneTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as LodestoneApplication).graph

        setContent {
            LodestoneTheme {
                LodestoneApp(graph)
            }
        }
    }
}
