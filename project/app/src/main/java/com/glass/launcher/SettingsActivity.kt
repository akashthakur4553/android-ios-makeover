package com.glass.launcher

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.glass.launcher.services.IslandService
import com.glass.launcher.services.NotificationService
import com.glass.launcher.ui.AlertButton
import com.glass.launcher.ui.Ios
import com.glass.launcher.ui.pressable

class SettingsActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    private var alsoLock = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) GlassApp.instance.wallpaper.usePhoto(uri, alsoLock)
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    private val role = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    override fun onResume() { super.onResume(); refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = GlassApp.instance
        val prefs = app.prefs
        setContent {
            @Suppress("UNUSED_VARIABLE") val r = refresh
            var confirmReset by remember { mutableStateOf(false) }
            val isDefault = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == packageName
            fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

            LazyColumn(Modifier.fillMaxSize().background(Color.Black), contentPadding = PaddingValues(bottom = 60.dp)) {
                item {
                    Text("Glass", style = Ios.largeTitle, modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 24.dp, bottom = 4.dp))
                    Text("An iPhone-style home screen for your phone.", style = Ios.footnote, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
                }
                item {
                    Group("HOME SCREEN") {
                        Row1(Icons.Rounded.Home, Ios.Blue, if (isDefault) "Glass is your Home Screen" else "Make Glass your Home Screen", value = if (isDefault) "On" else null) {
                            val rm = getSystemService(RoleManager::class.java)
                            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                            else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                        }
                        Row1(Icons.Rounded.Restore, Ios.Gray, "Switch back to original launcher") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                        Toggle(Icons.Rounded.TextFields, Ios.Indigo, "Show App Names", prefs.showLabels) { prefs.showLabels = it }
                        Toggle(Icons.Rounded.AutoAwesome, Ios.Purple, "Liquid Glass Refraction", prefs.liquidGlass) { prefs.liquidGlass = it }
                        Toggle(Icons.Rounded.Vibration, Ios.Red, "Haptics", prefs.haptics) { prefs.haptics = it }
                        Row1(Icons.Rounded.Apps, Ios.Orange, if (confirmReset) "Tap again to reset layout" else "Reset Home Screen Layout") {
                            if (confirmReset) { app.home.resetLayout(); confirmReset = false } else confirmReset = true
                        }
                    }
                }
                item {
                    Group("WALLPAPER") {
                        Row1(Icons.Rounded.Wallpaper, Ios.Blue, "Use phone's wallpaper", value = if (prefs.followSystemWallpaper) "✓" else null) {
                            if (!granted(Manifest.permission.READ_EXTERNAL_STORAGE)) perm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            app.wallpaper.useSystem()
                        }
                        Row1(Icons.Rounded.Image, Ios.Green, "Choose from Photos (home only)") { alsoLock = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        Row1(Icons.Rounded.Image, Ios.Green, "Choose from Photos (home + lock)") { alsoLock = true; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        listOf("Aurora Blue", "Aurora Green", "Aurora Sunset").forEachIndexed { i, n ->
                            Row1(Icons.Rounded.AutoAwesome, Ios.Indigo, "Glass $n") { app.wallpaper.useGenerated(i) }
                        }
                    }
                }
                item {
                    Group("DYNAMIC ISLAND & GESTURES") {
                        val islandOn = IslandService.isEnabled(this@SettingsActivity)
                        Row1(Icons.Rounded.AccessibilityNew, Color.Black, "Dynamic Island", value = if (islandOn) "On" else "Off") {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        Toggle(Icons.Rounded.TouchApp, Ios.Blue, "Top-right swipe → Control Centre", prefs.cornerControlCentre) { prefs.cornerControlCentre = it }
                        Toggle(Icons.Rounded.Vibration, Ios.Green, "Show charging in Dynamic Island", prefs.islandCharging) { prefs.islandCharging = it }
                        Toggle(Icons.Rounded.Notifications, Ios.Red, "iPhone-style notification banners", prefs.islandBanners) {
                            prefs.islandBanners = it
                            runCatching { Settings.Global.putInt(contentResolver, "heads_up_notifications_enabled", if (it) 0 else 1) }
                        }
                        Footnote("Gesture changes apply after turning the Dynamic Island off and on. The island never reads what's on your screen.")
                    }
                }
                item {
                    Group("PERMISSIONS") {
                        val nl = NotificationService.isEnabled(this@SettingsActivity)
                        Row1(Icons.Rounded.Notifications, Ios.Red, "Notification access", value = if (nl) "On" else "Off") {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        Row1(Icons.Rounded.CalendarMonth, Ios.Red, "Calendar", value = if (granted(Manifest.permission.READ_CALENDAR)) "On" else "Off") { perm.launch(Manifest.permission.READ_CALENDAR) }
                        Row1(Icons.Rounded.LocationOn, Ios.Blue, "Location (weather)", value = if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) "On" else "Off") { perm.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                        Row1(Icons.Rounded.Contacts, Ios.Gray, "Contacts (search)", value = if (granted(Manifest.permission.READ_CONTACTS)) "On" else "Off") { perm.launch(Manifest.permission.READ_CONTACTS) }
                        Row1(Icons.Rounded.Settings, Ios.Gray, "Brightness & rotation control", value = if (Settings.System.canWrite(this@SettingsActivity)) "On" else "Off") {
                            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                        }
                    }
                }
                item {
                    Group("SAFETY") {
                        Row1(Icons.Rounded.Shield, Ios.Green, "Crash protection", value = "On") {}
                        Footnote("If Glass ever crashes, your phone instantly switches to the phone's original launcher, and Glass steps aside automatically if it crashes repeatedly. Your original launcher is never removed.")
                    }
                }
            }
        }
    }

    @Composable
    private fun Group(title: String, content: @Composable () -> Unit) {
        Text(title, style = Ios.footnote.copy(color = Ios.Label2), modifier = Modifier.padding(start = 36.dp, top = 26.dp, bottom = 6.dp))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Ios.Cell, RoundedCornerShape(12.dp))) { content() }
    }

    @Composable
    private fun Footnote(t: String) {
        Text(t, style = Ios.footnote.copy(color = Ios.Label2, fontSize = 12.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }

    @Composable
    private fun Badge(icon: ImageVector, color: Color) {
        Box(Modifier.size(30.dp).background(color, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
    }

    @Composable
    private fun Row1(icon: ImageVector, color: Color, title: String, value: String? = null, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().pressable(onClick, scaleTo = 0.99f).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(icon, color)
            Text(title, style = Ios.body.copy(fontSize = 17.sp), modifier = Modifier.padding(start = 14.dp).weight(1f))
            if (value != null) Text(value, style = Ios.body.copy(fontSize = 17.sp, color = Ios.Label2))
            Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.padding(start = 8.dp).size(14.dp))
        }
    }

    @Composable
    private fun Toggle(icon: ImageVector, color: Color, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(icon, color)
            Text(title, style = Ios.body.copy(fontSize = 17.sp), modifier = Modifier.padding(start = 14.dp).weight(1f))
            Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Ios.Green, checkedThumbColor = Color.White, uncheckedTrackColor = Ios.Cell2, uncheckedBorderColor = Ios.Cell2, uncheckedThumbColor = Color.White))
        }
    }
}
