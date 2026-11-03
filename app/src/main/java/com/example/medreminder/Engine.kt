package com.example.medreminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

const val CHANNEL_ID = "doses_v2"
private const val OLD_CHANNEL_ID = "doses_v1"
private const val ACTION_DUE = "com.example.medreminder.DUE"
private const val ACTION_TAKEN = "com.example.medreminder.TAKEN"
private const val ACTION_DISMISSED = "com.example.medreminder.DISMISSED"
private const val EXTRA_MED = "med"
private const val EXTRA_TIME = "time"
private const val DISMISS_REQUEST_BASE = 2_000_000

object Engine {
    private val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
    fun format(ms: Long): String =
        fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    // ---------- schedule math (DST-safe: computed in local wall-clock time) ----------
    fun nextOccurrence(med: Med, after: ZonedDateTime): ZonedDateTime {
        val time = LocalTime.of(med.hour, med.minute)
        var date = med.startDate
        val today = after.toLocalDate()
        if (date.isBefore(today)) {
            val periods = ChronoUnit.DAYS.between(date, today) / med.intervalDays
            date = date.plusDays(periods * med.intervalDays)
        }
        while (true) {
            val candidate = ZonedDateTime.of(date, time, after.zone)
            if (candidate.isAfter(after)) return candidate
            date = date.plusDays(med.intervalDays.toLong())
        }
    }

    // ---------- alarms (scheduling only, no sound) ----------
    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun canExact(c: Context) = Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    private fun setAlarm(c: Context, at: Long, op: PendingIntent) {
        if (canExact(c)) {
            val show = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
        } else {
            am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op) // fallback, may be late
        }
    }

    private fun broadcast(
        c: Context, action: String, request: Int, extras: Intent.() -> Unit = {}
    ): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).setAction(action).apply(extras)
        return PendingIntent.getBroadcast(
            c, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleMed(c: Context, med: Med, after: ZonedDateTime = ZonedDateTime.now()) {
        val at = nextOccurrence(med, after).toInstant().toEpochMilli()
        Store.setScheduledAt(c, med.id, at)
        setAlarm(c, at, broadcast(c, ACTION_DUE, med.id) {
            putExtra(EXTRA_MED, med.id); putExtra(EXTRA_TIME, at)
        })
    }

    // ---------- dose state ----------
    /** Returns true if this due time is new (idempotent if called twice for the same time). */
    private fun markDue(c: Context, medId: Int, dueAt: Long): Boolean {
        synchronized(Store) {
            val list = Store.pending(c).toMutableList()
            val i = list.indexOfFirst { it.medId == medId }
            if (i >= 0) {
                val p = list[i]
                if (p.lastDue >= dueAt) return false
                list[i] = p.copy(lastDue = dueAt, count = p.count + 1)
            } else {
                list += Pending(medId, dueAt, dueAt, 1)
            }
            Store.savePending(c, list)
            return true
        }
    }

    fun addMed(c: Context, med: Med) {
        Store.saveMeds(c, Store.meds(c) + med)
        scheduleMed(c, med)
    }

    fun deleteMed(c: Context, id: Int) {
        am(c).cancel(broadcast(c, ACTION_DUE, id))
        Store.saveMeds(c, Store.meds(c).filter { it.id != id })
        Store.setScheduledAt(c, id, 0)
        Store.savePending(c, Store.pending(c).filter { it.medId != id })
        NotificationManagerCompat.from(c).cancel(id)
    }

    fun onDue(c: Context, medId: Int, dueAt: Long) {
        val med = Store.med(c, medId) ?: return
        val isNew = markDue(c, medId, dueAt)
        if (isNew) Store.log(c, "${format(dueAt)}  ${med.name} due")
        val after = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(maxOf(dueAt, System.currentTimeMillis())), ZoneId.systemDefault()
        )
        scheduleMed(c, med, after) // arm the next occurrence first
        Store.pending(c).find { it.medId == medId }?.let { show(c, it, beep = isNew) }
    }

    fun onTaken(c: Context, medId: Int) {
        synchronized(Store) {
            val p = Store.pending(c).find { it.medId == medId }
            if (p != null) {
                Store.savePending(c, Store.pending(c).filter { it.medId != medId })
                val name = Store.med(c, medId)?.name ?: "?"
                Store.log(
                    c, "${format(System.currentTimeMillis())}  $name TAKEN (due ${format(p.firstDue)})"
                )
            }
        }
        NotificationManagerCompat.from(c).cancel(medId)
    }

    /** User swiped the notification away without confirming: put it back silently. */
    fun onDismissed(c: Context, medId: Int) {
        Store.pending(c).find { it.medId == medId }?.let { show(c, it, beep = false) }
    }

    /** Re-arm everything; catch up on doses missed while the phone was off. */
    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        val newlyDue = mutableSetOf<Int>()
        for (med in Store.meds(c)) {
            val prev = Store.scheduledAt(c, med.id)
            if (prev in 1..now && markDue(c, med.id, prev)) {
                Store.log(c, "${format(prev)}  ${med.name} due (alarm missed, caught up)")
                newlyDue += med.id
            }
            scheduleMed(c, med)
        }
        Store.pending(c).forEach { show(c, it, beep = it.medId in newlyDue) }
    }

    // ---------- notifications ----------
    private fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL_ID) // remove the old alarm-style channel
        val ch = NotificationChannel(
            CHANNEL_ID, "Medication reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Beeps once, stays until you confirm the dose"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // default notification sound + vibration, normal notification volume
        }
        nm.createNotificationChannel(ch)
    }

    @SuppressLint("MissingPermission")
    fun show(c: Context, p: Pending, beep: Boolean) {
        val med = Store.med(c, p.medId) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(c)

        val text = if (p.count > 1)
            "Due since ${format(p.firstDue)}. ${p.count} scheduled doses unconfirmed."
        else "Due ${format(p.firstDue)}. Tap Taken once you've taken it."

        val open = PendingIntent.getActivity(
            c, p.medId,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Take ${med.name}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(!beep)
            .setWhen(p.firstDue)
            .setShowWhen(true)
            .setContentIntent(open)
            .setDeleteIntent(broadcast(c, ACTION_DISMISSED, DISMISS_REQUEST_BASE + p.medId) {
                putExtra(EXTRA_MED, p.medId)
            })
            .addAction(0, "✓ Taken", broadcast(c, ACTION_TAKEN, p.medId) {
                putExtra(EXTRA_MED, p.medId)
            })
            .build()

        NotificationManagerCompat.from(c).notify(p.medId, n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra(EXTRA_MED, -1)
        when (i.action) {
            ACTION_DUE -> Engine.onDue(c, id, i.getLongExtra(EXTRA_TIME, System.currentTimeMillis()))
            ACTION_TAKEN -> Engine.onTaken(c, id)
            ACTION_DISMISSED -> Engine.onDismissed(c, id)
        }
    }
}

class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Engine.rescheduleAll(c)
}
