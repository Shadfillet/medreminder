package com.example.medreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class Med(
    val id: Int,
    val name: String,
    val hour: Int,
    val minute: Int,
    val intervalDays: Int,
    val startDate: LocalDate,
)

/** A dose that has come due and hasn't been confirmed yet. */
data class Pending(val medId: Int, val firstDue: Long, val lastDue: Long, val count: Int)

/** Tiny persistent store. commit() = written to disk before we continue. */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("store", Context.MODE_PRIVATE)

    @Synchronized
    fun meds(c: Context): List<Med> {
        val a = JSONArray(prefs(c).getString("meds", "[]"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Med(
                o.getInt("id"), o.getString("name"), o.getInt("h"), o.getInt("m"),
                o.getInt("every"), LocalDate.parse(o.getString("start"))
            )
        }
    }

    @Synchronized
    fun saveMeds(c: Context, meds: List<Med>) {
        val a = JSONArray()
        meds.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("h", it.hour)
                    .put("m", it.minute).put("every", it.intervalDays)
                    .put("start", it.startDate.toString())
            )
        }
        prefs(c).edit().putString("meds", a.toString()).commit()
    }

    fun med(c: Context, id: Int) = meds(c).find { it.id == id }

    @Synchronized
    fun newId(c: Context): Int {
        val id = prefs(c).getInt("nextId", 1)
        prefs(c).edit().putInt("nextId", id + 1).commit()
        return id
    }

    @Synchronized
    fun pending(c: Context): List<Pending> {
        val a = JSONArray(prefs(c).getString("pending", "[]"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Pending(o.getInt("med"), o.getLong("first"), o.getLong("last"), o.getInt("count"))
        }
    }

    @Synchronized
    fun savePending(c: Context, list: List<Pending>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("med", it.medId).put("first", it.firstDue)
                    .put("last", it.lastDue).put("count", it.count)
            )
        }
        prefs(c).edit().putString("pending", a.toString()).commit()
    }

    fun scheduledAt(c: Context, id: Int) = prefs(c).getLong("sched_$id", 0L)

    fun setScheduledAt(c: Context, id: Int, t: Long) {
        prefs(c).edit().putLong("sched_$id", t).commit()
    }

    @Synchronized
    fun history(c: Context): List<String> {
        val a = JSONArray(prefs(c).getString("history", "[]"))
        return List(a.length()) { a.getString(it) }
    }

    @Synchronized
    fun log(c: Context, line: String) {
        val a = JSONArray()
        (listOf(line) + history(c)).take(100).forEach { a.put(it) }
        prefs(c).edit().putString("history", a.toString()).commit()
    }
}
