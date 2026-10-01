package de.regepower.bootdelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private class AppItem(val label: String, val pkg: String, val icon: Drawable, var selected: Boolean)

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var initial: EditText
    private lateinit var gap: EditText
    private lateinit var search: EditText
    private lateinit var selectedHeader: TextView
    private val selectedAdapter = AppAdapter()
    private val availableAdapter = AppAdapter()
    private var all: List<AppItem> = emptyList()
    private val order = mutableListOf<String>()
    private var query = ""

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = (12 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        status = TextView(this)
        root.addView(status)

        val perms = LinearLayout(this)
        perms.addView(button("Overlay") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }, weight())
        perms.addView(button("Akku") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }, weight())
        perms.addView(button("Test") {
            save()
            LaunchService.start(this)
        }, weight())
        root.addView(perms)

        val delays = LinearLayout(this)
        initial = numberField("Start (Sek.)", prefs.initialDelaySec, delays)
        gap = numberField("Abstand (Sek.)", prefs.gapSec, delays)
        root.addView(delays)

        search = EditText(this).apply {
            hint = "Apps suchen…"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim().lowercase(Locale.getDefault())
                    refreshLists()
                }
            })
        }
        root.addView(search)

        selectedHeader = header()
        root.addView(selectedHeader)
        root.addView(appList(selectedAdapter), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(header().apply { text = "Verfügbar" })
        root.addView(appList(availableAdapter), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f))
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

    private fun header() = TextView(this).apply {
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun appList(a: AppAdapter) = ListView(this).apply {
        adapter = a
        setOnItemClickListener { _, _, pos, _ -> toggle(a.getItem(pos)) }
    }

    /** Selected apps keep their start order (new ones are appended); the rest is filtered alphabetically. */
    private fun toggle(item: AppItem) {
        item.selected = !item.selected
        order.remove(item.pkg)
        if (item.selected) order.add(item.pkg)
        save()
        refreshLists()
    }

    private fun refreshLists() {
        val byPkg = all.associateBy { it.pkg }
        selectedAdapter.set(order.mapNotNull { byPkg[it] })
        selectedHeader.text = "Ausgewählt (${selectedAdapter.count}) – Startreihenfolge"
        availableAdapter.set(
            all.filter { !it.selected }
                .filter { query.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(query) || it.pkg.contains(query) }
                .sortedBy { it.label.lowercase(Locale.getDefault()) },
        )
    }

    private fun save() {
        prefs.initialDelaySec = initial.text.toString().toIntOrNull() ?: 30
        prefs.gapSec = gap.text.toString().toIntOrNull() ?: 10
        if (all.isNotEmpty()) {
            prefs.packages = order.toList()
        }
    }

    private fun loadApps() {
        val selected = prefs.packages.toSet()
        order.clear()
        order.addAll(prefs.packages)
        Thread {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = packageManager.queryIntentActivities(launcher, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    AppItem(
                        it.loadLabel(packageManager).toString(),
                        it.activityInfo.packageName,
                        it.loadIcon(packageManager),
                        it.activityInfo.packageName in selected,
                    )
                }
            runOnUiThread {
                all = items
                order.retainAll(items.map { it.pkg }.toSet())
                refreshLists()
            }
        }.start()
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun numberField(label: String, value: Int, parent: LinearLayout): EditText {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = label })
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
        }
        col.addView(field)
        parent.addView(col, weight())
        return field
    }

    private inner class AppAdapter : BaseAdapter() {
        private var shown: List<AppItem> = emptyList()

        fun set(items: List<AppItem>) {
            shown = items
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (8 * dp).toInt()
                setPadding(p, p, p, p)
                val size = (40 * dp).toInt()
                addView(ImageView(context), LinearLayout.LayoutParams(size, size))
                val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(TextView(context).apply { textSize = 16f })
                texts.addView(TextView(context).apply { textSize = 11f; alpha = 0.6f })
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (12 * dp).toInt()
                    },
                )
                addView(CheckBox(context).apply { isClickable = false; isFocusable = false })
            }
            val item = getItem(position)
            (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = item.label
            (texts.getChildAt(1) as TextView).text = item.pkg
            (row.getChildAt(2) as CheckBox).isChecked = item.selected
            return row
        }
    }
}
