package com.pika

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pika.ui.MainScreen
import com.pika.ui.theme.PiKATextTheme
import com.pika.ui.theme.PiKATheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PiKATheme {
                PiKATextTheme {
                    MainScreen()
                }
            }
        }
    }
}