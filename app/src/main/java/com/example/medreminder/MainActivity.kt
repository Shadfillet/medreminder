package com.example.medreminder

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    private val askNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onResume() {
        super.onResume()
        Engine.rescheduleAll(this) // self-heal every time the app is opened
        refresh++
    }

    private fun problems(): List<Pair<String, () -> Unit>> {
        val out = mutableListOf<Pair<String, () -> Unit>>()
        val pkg = Uri.parse("package:$packageName")
        val nm = getSystemService(NotificationManager::class.java)
        val channelOff = nm.getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE
        if (!nm.areNotificationsEnabled() || channelOff) out += "Notifications are blocked" to {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        if (!Engine.canExact(this)) out += "Exact alarms not allowed – reminders could be late" to {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            out += "Battery optimization may delay reminders" to {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }
        if (Build.VERSION.SDK_INT >= 30 && !packageManager.isAutoRevokeWhitelisted)
            out += "Turn off \"Pause app activity if unused\"" to {
                startActivity(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, pkg))
            }
        return out
    }

    @Composable
    private fun Screen() {
        val r = refresh
        val meds = remember(r) { Store.meds(this) }
        val next = remember(r) { meds.associate { it.id to Store.scheduledAt(this, it.id) } }
        val pending = remember(r) { Store.pending(this) }
        val history = remember(r) { Store.history(this) }
        val problems = remember(r) { problems() }
        val ctx = this

        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(problems) { (msg, fix) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f))
                        Button(onClick = fix) { Text("Fix") }
                    }
                }
            }
            items(pending) { p ->
                val name = meds.find { it.id == p.medId }?.name ?: "?"
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Take $name", style = MaterialTheme.typography.titleMedium)
                            Text("Due ${Engine.format(p.firstDue)}")
                        }
                        Button(onClick = { Engine.onTaken(ctx, p.medId); refresh++ }) { Text("Taken") }
                    }
                }
            }
            item { Text("Medications", style = MaterialTheme.typography.titleLarge) }
            if (meds.isEmpty()) item { Text("None yet – add one below.") }
            items(meds, key = { it.id }) { m -> MedCard(m, next[m.id] ?: 0L) }
            item { AddMedForm() }
            item { Text("History", style = MaterialTheme.typography.titleLarge) }
            items(history) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    @Composable
    private fun MedCard(m: Med, next: Long) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium)
                val every = if (m.intervalDays == 1) "Every day" else "Every ${m.intervalDays} days"
                Text("$every at %02d:%02d, from ${m.startDate}".format(m.hour, m.minute))
                if (next > 0) Text("Next: ${Engine.format(next)}")
                TextButton(onClick = {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete ${m.name}?")
                        .setPositiveButton("Delete") { _, _ ->
                            Engine.deleteMed(this@MainActivity, m.id); refresh++
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }) { Text("Delete") }
            }
        }
    }

    @Composable
    private fun AddMedForm() {
        var name by remember { mutableStateOf("") }
        var hour by remember { mutableIntStateOf(8) }
        var minute by remember { mutableIntStateOf(0) }
        var every by remember { mutableStateOf("1") }
        var start by remember { mutableStateOf(LocalDate.now()) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add medication", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedButton(onClick = {
                    TimePickerDialog(this@MainActivity, { _, h, m -> hour = h; minute = m },
                        hour, minute, true).show()
                }) { Text("Time: %02d:%02d".format(hour, minute)) }
                OutlinedTextField(
                    every, { every = it.filter(Char::isDigit) },
                    label = { Text("Every N days (1 = daily)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    DatePickerDialog(this@MainActivity, { _, y, mo, d -> start = LocalDate.of(y, mo + 1, d) },
                        start.year, start.monthValue - 1, start.dayOfMonth).show()
                }) { Text("Starting: $start") }
                val n = every.toIntOrNull() ?: 0
                Button(enabled = name.isNotBlank() && n in 1..365, onClick = {
                    Engine.addMed(
                        this@MainActivity,
                        Med(Store.newId(this@MainActivity), name.trim(), hour, minute, n, start)
                    )
                    name = ""; refresh++
                }) { Text("Add") }
            }
        }
    }
}
