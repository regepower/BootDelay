package de.regepower.bootdelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var initial: EditText
    private lateinit var gap: EditText
    private lateinit var list: ListView
    private var apps: List<Pair<String, String>> = emptyList() // label to package

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            fitsSystemWindows = true
        }
        status = TextView(this)
        root.addView(status)
        root.addView(button("Overlay-Berechtigung") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        })
        root.addView(button("Akku: nicht optimieren") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        })
        initial = numberField("Start-Verzögerung nach Boot (Sek.)", prefs.initialDelaySec, root)
        gap = numberField("Abstand zwischen Apps (Sek.)", prefs.gapSec, root)
        root.addView(button("Speichern + jetzt testen") {
            save()
            LaunchService.start(this)
        })

        list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Settings.canDrawOverlays(this)
        val battOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        status.text = "Overlay: ${if (overlayOk) "OK" else "FEHLT"}  |  Akku: ${if (battOk) "OK" else "optimiert"}"
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        prefs.initialDelaySec = initial.text.toString().toIntOrNull() ?: 30
        prefs.gapSec = gap.text.toString().toIntOrNull() ?: 10
        val checked = list.checkedItemPositions
        prefs.packages = apps.indices.filter { checked.get(it) }.map { apps[it].second }
    }

    private fun loadApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        list.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_multiple_choice, apps.map { "${it.first}\n${it.second}" },
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View =
                (super.getView(position, convertView, parent) as CheckedTextView)
        }
        val selected = prefs.packages.toSet()
        apps.forEachIndexed { i, a -> list.setItemChecked(i, a.second in selected) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun numberField(label: String, value: Int, parent: LinearLayout): EditText {
        parent.addView(TextView(this).apply { text = label })
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            parent.addView(this)
        }
    }
}
