package dev.phosphor.launcher

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/** The persisted alarm, not the view's refresh loop, owns phase transitions. */
internal object PomodoroEngine {
    const val ACTION = "dev.phosphor.launcher.POMODORO_END"
    private const val CHANNEL = "phosphor_timer"
    private const val REQUEST = 7319
    data class State(val phase: String, val round: Int, val running: Boolean, val remaining: Long, val duration: Long)
    private fun prefs(context: Context) = context.getSharedPreferences("phosphor", Context.MODE_PRIVATE)
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun minutes(p: SharedPreferences, phase: String) = p.getInt("pomo_$phase", when (phase) { "focus" -> 25; "short" -> 5; else -> 15 }).coerceIn(1, 180)
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, REQUEST,
        Intent(context, PomodoroReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Synchronized fun state(context: Context): State {
        val p = prefs(context)
        val phase = p.getString("pomo_phase", "focus") ?: "focus"
        val duration = p.getLong("pomo_duration", minutes(p, phase) * 60_000L)
        val running = p.getBoolean("pomo_running", false)
        val remaining = if (!running) p.getLong("pomo_remaining", duration)
        else if (p.getInt("pomo_boot", -2) == boot(context)) p.getLong("pomo_deadline", 0) - SystemClock.elapsedRealtime()
        else p.getLong("pomo_wall_deadline", 0) - System.currentTimeMillis()
        return State(phase, p.getInt("pomo_round", 0), running, remaining.coerceAtLeast(0), duration)
    }
    private fun schedule(context: Context, remaining: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val whenElapsed = SystemClock.elapsedRealtime() + remaining.coerceAtLeast(1)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenElapsed, pending(context))
            } else alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenElapsed, pending(context))
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenElapsed, pending(context))
        }
    }
    private fun save(context: Context, phase: String, round: Int, running: Boolean, remaining: Long, duration: Long) {
        prefs(context).edit().putString("pomo_phase", phase).putInt("pomo_round", round)
            .putBoolean("pomo_running", running).putLong("pomo_remaining", remaining)
            .putLong("pomo_duration", duration).putLong("pomo_deadline", SystemClock.elapsedRealtime() + remaining)
            .putLong("pomo_wall_deadline", System.currentTimeMillis() + remaining).putInt("pomo_boot", boot(context)).commit()
        if (running) schedule(context, remaining) else context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }
    @Synchronized fun toggle(context: Context) {
        val s = state(context)
        if (s.running && s.remaining == 0L) { finish(context); return }
        save(context, s.phase, s.round, !s.running, s.remaining, s.duration)
    }
    @Synchronized fun reset(context: Context) {
        val duration = minutes(prefs(context), "focus") * 60_000L
        save(context, "focus", 0, false, duration, duration)
        context.getSystemService(NotificationManager::class.java).cancel(REQUEST)
    }
    @Synchronized fun skip(context: Context) { transition(context, false) }
    private fun transition(context: Context, alert: Boolean) {
        val s = state(context)
        val round = if (s.phase == "focus") s.round + 1 else s.round
        val next = if (s.phase != "focus") "focus" else if (round % 4 == 0) "long" else "short"
        val duration = minutes(prefs(context), next) * 60_000L
        save(context, next, round, s.running, duration, duration)
        if (alert) signal(context, s.phase, next)
    }
    @Synchronized fun finish(context: Context) {
        val s = state(context)
        if (!s.running) return
        if (s.remaining > 500) { schedule(context, s.remaining); return }
        transition(context, true)
    }
    @Synchronized fun restore(context: Context) {
        val s = state(context)
        if (!s.running) return
        if (s.remaining == 0L) finish(context)
        else save(context, s.phase, s.round, true, s.remaining, s.duration)
    }
    private fun signal(context: Context, old: String, next: String) {
        val p = prefs(context)
        if (p.getBoolean("pomo_sound", true)) {
            try {
                context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
            } catch (_: SecurityException) { }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val audible = p.getBoolean("pomo_sound", true)
        val channelId = if (audible) CHANNEL else "${CHANNEL}_silent"
        val channel = NotificationChannel(channelId, if (audible) "Pomodoro phase alerts" else "Silent pomodoro alerts",
            if (audible) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW)
        if (!audible) { channel.setSound(null, null); channel.enableVibration(false) }
        manager.createNotificationChannel(channel)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(if (old == "focus") "Focus complete" else "Break complete")
            .setContentText(if (next == "focus") "Your next focus session has started." else "Your ${if (next == "long") "long" else "short"} break has started.")
            .setAutoCancel(true).setCategory(Notification.CATEGORY_ALARM)
        if (launch != null) builder.setContentIntent(PendingIntent.getActivity(context, REQUEST, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        manager.notify(REQUEST, builder.build())
    }
}

class PomodoroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PomodoroEngine.ACTION) PomodoroEngine.finish(context)
        else if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) PomodoroEngine.restore(context)
    }
}
