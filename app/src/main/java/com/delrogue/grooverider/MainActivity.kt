package com.delrogue.grooverider

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.delrogue.grooverider.onboarding.OnboardingScreen
import com.delrogue.grooverider.ui.DebugPanelScreen
import com.delrogue.grooverider.ui.cloud.CloudScreen
import com.delrogue.grooverider.ui.library.SeedLibraryScreen
import com.delrogue.grooverider.ui.source.SourceScreen
import com.delrogue.grooverider.ui.theme.GROOVERIDERTheme

class MainActivity : ComponentActivity() {

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GROOVERIDERTheme {
                var onboarded by remember { mutableStateOf(AppPrefs.isOnboarded(this@MainActivity)) }
                if (!onboarded) {
                    OnboardingScreen(onFinished = {
                        onboarded = true
                        ensurePermissions()
                    })
                    return@GROOVERIDERTheme
                }

                LaunchedEffect(Unit) { ensurePermissions() }

                var tab by remember { mutableIntStateOf(0) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    // The Cloud screen is a full-screen performance view -- "one
                    // screen, no menus during performance" (spec 5.1). A
                    // persistent tab bar eating into its 4-zone layout is
                    // exactly the bug it warns against, so it's hidden there.
                    bottomBar = {
                        if (tab != 2) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == 0, onClick = { tab = 0 },
                                    icon = { Text("♪") },
                                    label = { Text("Sources") },
                                )
                                NavigationBarItem(
                                    selected = tab == 1, onClick = { tab = 1 },
                                    icon = { Text("≡") },
                                    label = { Text("Engine") },
                                )
                                NavigationBarItem(
                                    selected = tab == 2, onClick = { tab = 2 },
                                    icon = { Text("☁") },
                                    label = { Text("Cloud") },
                                )
                                NavigationBarItem(
                                    selected = tab == 3, onClick = { tab = 3 },
                                    icon = { Text("▦") },
                                    label = { Text("Library") },
                                )
                            }
                        }
                    },
                ) { inner ->
                    when (tab) {
                        0 -> SourceScreen(modifier = Modifier.padding(inner))
                        1 -> DebugPanelScreen(modifier = Modifier.padding(inner))
                        2 -> CloudScreen(modifier = Modifier.padding(inner), onExit = { tab = 1 })
                        else -> SeedLibraryScreen(modifier = Modifier.padding(inner))
                    }
                }
            }
        }
    }

    private fun ensurePermissions() {
        val wanted = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) return

        // Rationale copy (spec M8): shown once, briefly, rather than letting
        // the system prompt appear with no context at all.
        if (Manifest.permission.RECORD_AUDIO in wanted) {
            Toast.makeText(
                this,
                "Grooverider asks for the mic so you can record source material to granulate. " +
                    "Nothing is sent anywhere -- it stays on this device.",
                Toast.LENGTH_LONG,
            ).show()
        }
        requestPerms.launch(wanted.toTypedArray())
    }
}
