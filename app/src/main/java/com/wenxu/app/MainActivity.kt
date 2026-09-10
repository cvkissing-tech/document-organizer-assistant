package com.wenxu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.wenxu.app.ui.theme.WenxuTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WenxuApplication).container
        container.incomingIntentStore.accept(intent)
        setContent {
            WenxuTheme {
                WenxuApp(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as WenxuApplication).container.incomingIntentStore.accept(intent)
    }
}
