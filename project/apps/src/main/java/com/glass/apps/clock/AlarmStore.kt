package com.glass.apps.clock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.glass.apps.GlassAppsApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Alarm(
    val id: Int, val hour: Int, val minute: Int, val label: String = "Alarm",
    val days: Set<Int> = emptySet(), // Calendar.SUNDAY..SATURDAY; empty = once
    val enabled: Boolean = true, val snooze: Boolean = true,
)

/** Alarms + timer persistence and scheduling (AlarmManager.setAlarmClock = exact, survives Doze). */
object AlarmStore {
    private const val TIMER_ID = 990_001
    private const val SNOOZE_BASE = 500_000

    private fun sp(ctx: Context) = ctx.getSharedPreferences("clock", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Alarm> = runCatching {
        val a = JSONArray(sp(ctx).getString("alarms", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val d = o.optJSONArray("d") ?: JSONArray()
            Alarm(o.getInt("id"), o.getInt("h"), o.getInt("m"), o.optString("l", "Alarm"),
                (0 until d.length()).map { d.getInt(it) }.toSet(), o.optBoolean("e", true), o.optBoolean("s", true))
        }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }.getOrDefault(emptyList())

    private fun write(ctx: Context, list: List<Alarm>) {
        val a = JSONArray()
        list.forEach { x -> a.put(JSONObject().put("id", x.id).put("h", x.hour).put("m", x.minute).put("l", x.label)
            .put("d", JSONArray(x.days.toList())).put("e", x.enabled).put("s", x.snooze)) }
        sp(ctx).edit().putString("alarms", a.toString()).apply()
    }

    fun upsert(ctx: Context, a: Alarm) {
        val list = all(ctx).filter { it.id != a.id } + a
        write(ctx, list)
        if (a.enabled) schedule(ctx, a) else cancel(ctx, a.id)
    }

    fun delete(ctx: Context, id: Int) {
        write(ctx, all(ctx).filter { it.id != id }); cancel(ctx, id)
    }

    fun newId(ctx: Context) = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1

    fun nextTrigger(a: Alarm, from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = from; set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        if (a.days.isNotEmpty()) while (c.get(Calendar.DAY_OF_WEEK) !in a.days) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun firePi(ctx: Context, id: Int, label: String, timer: Boolean): PendingIntent =
        PendingIntent.getBroadcast(ctx, id, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.FIRE)
            .putExtra("id", id).putExtra("label", label).putExtra("timer", timer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun showPi(ctx: Context) = PendingIntent.getActivity(ctx, 1, Intent(ctx, ClockActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun setAt(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(ctx)), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun schedule(ctx: Context, a: Alarm) = setAt(ctx, nextTrigger(a), firePi(ctx, a.id, a.label, false))

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePi(ctx, id, "", false))
    }

    fun snooze(ctx: Context, id: Int, label: String) =
        setAt(ctx, System.currentTimeMillis() + 9 * 60_000, firePi(ctx, SNOOZE_BASE + id, label, false))

    fun rescheduleAll(ctx: Context) {
        all(ctx).filter { it.enabled }.forEach { schedule(ctx, it) }
        timerEnd(ctx).takeIf { it > System.currentTimeMillis() }?.let { startTimer(ctx, it) }
    }

    // ---- timer
    fun timerEnd(ctx: Context) = sp(ctx).getLong("timerEnd", 0L)
    fun timerPausedLeft(ctx: Context) = sp(ctx).getLong("timerPaused", 0L)
    fun timerTotal(ctx: Context) = sp(ctx).getLong("timerTotal", 0L)

    fun startTimer(ctx: Context, endAt: Long, total: Long = timerTotal(ctx)) {
        sp(ctx).edit().putLong("timerEnd", endAt).putLong("timerPaused", 0).putLong("timerTotal", total).apply()
        setAt(ctx, endAt, firePi(ctx, TIMER_ID, "Timer", true))
    }

    fun pauseTimer(ctx: Context) {
        val left = (timerEnd(ctx) - System.currentTimeMillis()).coerceAtLeast(0)
        sp(ctx).edit().putLong("timerEnd", 0).putLong("timerPaused", left).apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(firePi(ctx, TIMER_ID, "", true))
    }

    fun cancelTimer(ctx: Context) {
        sp(ctx).edit().putLong("timerEnd", 0).putLong("timerPaused", 0).putLong("timerTotal", 0).apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(firePi(ctx, TIMER_ID, "", true))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        val label = i.getStringExtra("label") ?: "Alarm"
        val timer = i.getBooleanExtra("timer", false)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        when (i.action) {
            FIRE -> {
                if (timer) AlarmStore.cancelTimer(ctx)
                else AlarmStore.all(ctx).firstOrNull { it.id == id }?.let { a ->
                    if (a.days.isEmpty()) AlarmStore.upsert(ctx, a.copy(enabled = false)) else AlarmStore.schedule(ctx, a)
                }
                val ring = Intent(ctx, AlarmRingActivity::class.java).putExtra("id", id).putExtra("label", label).putExtra("timer", timer)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                val full = PendingIntent.getActivity(ctx, id, ring, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                fun act(a: String, t: String) = Notification.Action.Builder(null, t,
                    PendingIntent.getBroadcast(ctx, id * 10 + a.length, Intent(ctx, AlarmReceiver::class.java).setAction(a).putExtra("id", id).putExtra("label", label),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
                val n = Notification.Builder(ctx, GlassAppsApp.CH_ALARM)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(if (timer) "Timer" else label)
                    .setContentText(if (timer) "Timer done" else java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date()))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setFullScreenIntent(full, true)
                    .setContentIntent(full)
                    .setOngoing(true)
                    .addAction(act(STOP, "Stop"))
                    .apply { if (!timer) addAction(act(SNOOZE, "Snooze")) }
                    .build()
                n.flags = n.flags or Notification.FLAG_INSISTENT // loop the alarm sound until stopped
                nm.notify(NOTIF_BASE + id, n)
                runCatching { ctx.startActivity(ring) }
            }
            STOP -> { nm.cancel(NOTIF_BASE + id); AlarmRingActivity.current?.finish() }
            SNOOZE -> { nm.cancel(NOTIF_BASE + id); AlarmStore.snooze(ctx, id % 500_000, label); AlarmRingActivity.current?.finish() }
        }
    }

    companion object {
        const val FIRE = "com.glass.apps.ALARM_FIRE"
        const val STOP = "com.glass.apps.ALARM_STOP"
        const val SNOOZE = "com.glass.apps.ALARM_SNOOZE"
        const val NOTIF_BASE = 7000
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) = AlarmStore.rescheduleAll(ctx)
}
