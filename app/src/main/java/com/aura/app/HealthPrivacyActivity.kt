package com.aura.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.app.ui.AuraTheme
import com.aura.app.ui.C
import com.aura.app.ui.T

/** Shown by Health Connect when the user asks why Aura wants its permissions. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuraTheme {
                Column(
                    Modifier.fillMaxSize().background(C.bg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Aura and Health Connect", style = T.title)
                    Text("Aura only reads from Health Connect; it never writes to it.", style = T.body)
                    Text(
                        "Aura reads your steps, exercise sessions, sleep, active calories, heart rate and distance. It uses them to check the goals you set, " +
                            "to add your watch's numbers to the routes you record, and to show your heart rate next to the apps you had open.",
                        style = T.label,
                    )
                    Text(
                        "The readings stay on your phone. Only what you choose to share leaves it: a goal's name, its target and points, " +
                            "or an activity's distance, time and average heart rate.",
                        style = T.label,
                    )
                    Text("Which apps were open comes from Android's usage access, which you grant separately in system settings. It also stays on your phone.", style = T.label)
                }
            }
        }
    }
}
