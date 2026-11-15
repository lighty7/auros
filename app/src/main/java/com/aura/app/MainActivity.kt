package com.aura.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aura.app.update.UpdateWorker
import java.util.concurrent.TimeUnit
import com.aura.app.ui.App
import com.aura.app.ui.AuraTheme

class MainActivity : ComponentActivity() {
    private val vm: AuraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "aura-update-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED)).build(),
        )
        setContent { AuraTheme { App(vm) } }
    }
}
