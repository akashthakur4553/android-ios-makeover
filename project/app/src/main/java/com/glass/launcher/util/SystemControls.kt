package com.glass.launcher.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.glass.launcher.services.NotificationService

/** Thin wrappers over the system toggles Control Centre needs. Every call is failure-safe. */
object Sys {
    fun wifiOn(ctx: Context) = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, on: Boolean) {
        val ok = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java).setWifiEnabled(on) }.getOrDefault(false)
        if (!ok) panel(ctx, Settings.Panel.ACTION_WIFI)
    }

    @SuppressLint("MissingPermission")
    fun btOn(): Boolean = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun setBt(ctx: Context, on: Boolean) {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        val ok = runCatching { if (on) a.enable() else a.disable() }.getOrDefault(false)
        if (!ok) open(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    fun airplaneOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    fun mobileDataOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "mobile_data", 1) == 1

    // ---- torch
    private var torchId: String? = null
    var torchOn = false; private set
    private var torchCbRegistered = false

    fun torchSetup(ctx: Context, onChange: (Boolean) -> Unit) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        if (torchId == null) torchId = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
        if (!torchCbRegistered) {
            torchCbRegistered = true
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId) { torchOn = enabled; onChange(enabled) }
                }
            }, null)
        }
    }

    fun setTorch(ctx: Context, on: Boolean) {
        val id = torchId ?: return
        runCatching { ctx.getSystemService(CameraManager::class.java).setTorchMode(id, on) }
    }

    // ---- brightness (0..1, perceptual)
    fun canWriteSettings(ctx: Context) = Settings.System.canWrite(ctx)

    private fun maxBrightness(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        return if (id != 0) ctx.resources.getInteger(id).coerceAtLeast(1) else 255
    }

    fun brightness(ctx: Context): Float {
        val v = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val lin = (v / maxBrightness(ctx).toFloat()).coerceIn(0f, 1f)
        return Math.pow(lin.toDouble(), 1 / 2.2).toFloat()
    }

    fun setBrightness(ctx: Context, perceptual: Float) {
        if (!canWriteSettings(ctx)) return
        val lin = Math.pow(perceptual.coerceIn(0.02f, 1f).toDouble(), 2.2)
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (lin * maxBrightness(ctx)).toInt().coerceAtLeast(1))
        }
    }

    // ---- volume
    fun volume(ctx: Context): Float {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
    }

    fun setVolume(ctx: Context, v: Float) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v.coerceIn(0f, 1f) * max), 0) }
    }

    // ---- rotation lock
    fun rotationLocked(ctx: Context) = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 0
    fun setRotationLocked(ctx: Context, locked: Boolean) {
        if (!canWriteSettings(ctx)) { requestWriteSettings(ctx); return }
        runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (locked) 0 else 1) }
    }

    fun requestWriteSettings(ctx: Context) =
        open(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    // ---- focus
    fun dnd(ctx: Context) = runCatching { NotificationService.isDnd(ctx) }.getOrDefault(false)
    fun setDnd(ctx: Context, on: Boolean) = runCatching { NotificationService.setDnd(ctx, on) }

    // ---- system shade (fallback to the stock quick-settings panel)
    @SuppressLint("WrongConstant")
    fun expandSystemShade(ctx: Context, quickSettings: Boolean) {
        runCatching {
            val sbm = ctx.getSystemService("statusbar")
            val m = sbm.javaClass.getMethod(if (quickSettings) "expandSettingsPanel" else "expandNotificationsPanel")
            m.invoke(sbm)
        }
    }

    // ---- extra toggles for the "More Controls" page
    fun autoBrightness(ctx: Context) = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
    fun setAutoBrightness(ctx: Context, on: Boolean) {
        if (!canWriteSettings(ctx)) { requestWriteSettings(ctx); return }
        runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0) }
    }
    fun silent(ctx: Context) = ctx.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL
    fun setSilent(ctx: Context, on: Boolean) {
        runCatching { ctx.getSystemService(AudioManager::class.java).ringerMode = if (on) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL }
    }
    /** Screenshot / lock / power menu through the Glass accessibility service (no root needed). */
    fun globalAction(action: Int): Boolean = com.glass.launcher.services.IslandService.instance?.performGlobalAction(action) ?: false

    // ---- launchers
    fun panel(ctx: Context, action: String) = open(ctx, Intent(action))

    fun open(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun openPkg(ctx: Context, pkg: String, fallback: Intent? = null) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null || !open(ctx, i)) fallback?.let { open(ctx, it) }
    }

    fun camera(ctx: Context) = open(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    fun timer(ctx: Context) = open(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS)) || open(ctx, Intent(AlarmClock.ACTION_SET_TIMER))
    fun calculator(ctx: Context) = openPkg(ctx, "com.miui.calculator", Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR))
    fun screenRecorder(ctx: Context) = openPkg(ctx, "com.miui.screenrecorder")
    fun qrScanner(ctx: Context) = openPkg(ctx, "com.xiaomi.scanner")
    fun hotspot(ctx: Context) = open(ctx, Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")))
        || open(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS))
    fun display(ctx: Context) = open(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS))
    fun batterySaver(ctx: Context) = open(ctx, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
}
