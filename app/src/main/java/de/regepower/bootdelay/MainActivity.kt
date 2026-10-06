package de.regepower.bootdelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private class AppItem(
        val label: String,
        val pkg: String,
        val icon: Drawable,
        var selected: Boolean,
    )

    private lateinit var prefs: Prefs
    private lateinit var overlayBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var initial: EditText
    private lateinit var gap: EditText
    private lateinit var selectedHeader: TextView
    private lateinit var loading: View
    private val selectedAdapter = AppAdapter()
    private val availableAdapter = AppAdapter()
    private var all: List<AppItem> = emptyList()
    private val order = mutableListOf<String>()
    private var query = ""

    /** True after a config import: the old UI must not write its values back (onPause before recreate). */
    private var configLoaded = false

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.lastBootCount == -1) prefs.markBootHandled(this)

        val pad = (8 * dp).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                fitsSystemWindows = true
            }

        root.addView(AppShell.header(this, prefs.sp, Prefs.DEVICE_KEYS::contains))

        val buttons = LinearLayout(this)
        overlayBtn =
            button(
                getString(R.string.btn_overlay),
                getString(R.string.help_overlay),
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
        batteryBtn =
            button(
                getString(R.string.btn_battery),
                getString(R.string.help_battery),
            ) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            }
        buttons.addView(overlayBtn, weight())
        buttons.addView(batteryBtn, weight())
        buttons.addView(
            button(
                getString(R.string.btn_test),
                getString(R.string.help_test),
            ) {
                save()
                LaunchService.start(this, LaunchService.SOURCE_TEST)
            }.also { styleButton(it, R.color.md_primary, R.color.md_on_primary) },
            weight(),
        )
        root.addView(buttons)

        val delays = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        initial =
            delayField(
                getString(R.string.label_start),
                prefs.initialDelaySec,
                delays,
                getString(R.string.help_start),
            )
        delays.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        gap =
            delayField(
                getString(R.string.label_gap),
                prefs.gapSec,
                delays,
                getString(R.string.help_gap),
            )
        root.addView(delays)

        val search =
            EditText(this).apply {
                hint = getString(R.string.search_hint)
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            st: Int,
                            c: Int,
                            a: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            st: Int,
                            b: Int,
                            c: Int,
                        ) = Unit

                        override fun afterTextChanged(s: Editable?) {
                            query =
                                s
                                    ?.toString()
                                    .orEmpty()
                                    .trim()
                                    .lowercase(Locale.getDefault())
                            refreshLists()
                        }
                    },
                )
            }
        root.addView(search)

        // Both lists in one box; a loading overlay covers them until the app list is read (1-3 s).
        val lists = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        selectedHeader = header()
        lists.addView(selectedHeader)
        val selectedList = appList(selectedAdapter)
        lists.addView(selectedList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        lists.addView(header().apply { text = getString(R.string.header_available) })
        lists.addView(
            appList(availableAdapter),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f),
        )
        loading = loadingOverlay()
        val box = FrameLayout(this)
        box.addView(lists, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        attachDragSorting(selectedList)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        loadApps()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        AppShell.onResult(this, requestCode, resultCode, data, prefs.sp, Prefs.DEVICE_KEYS::contains) {
            configLoaded = true
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        showStatus(overlayBtn, getString(R.string.btn_overlay), Settings.canDrawOverlays(this))
        showStatus(
            batteryBtn,
            getString(R.string.btn_battery),
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    /** Tonal (primary container) when granted, error container when missing. */
    private fun showStatus(
        b: Button,
        label: String,
        ok: Boolean,
    ) {
        b.text = if (ok) "$label ✓" else label
        if (ok) {
            styleButton(b, R.color.md_container, R.color.md_on_container)
        } else {
            styleButton(b, R.color.md_error_container, R.color.md_on_error_container)
        }
    }

    private fun styleButton(
        b: Button,
        fill: Int,
        text: Int,
    ) {
        b.setBackgroundResource(R.drawable.bg_btn)
        b.backgroundTintList = ColorStateList.valueOf(getColor(fill))
        b.setTextColor(getColor(text))
        b.isAllCaps = false
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = (40 * dp).toInt()
    }

    private fun header() =
        TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.md_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
        }

    private fun appList(a: AppAdapter) =
        ListView(this).apply {
            adapter = a
            divider = null
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            setPadding(dp.toInt(), dp.toInt(), dp.toInt(), dp.toInt())
            clipToPadding = true
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> toggle(a.getItem(pos)) }
        }

    // Drag sorting without RecyclerView (~110 KB saved): long-press starts a framework drag, the
    // list's OnDragListener moves the item to the row under the finger; near the top/bottom edge
    // the list scrolls on its own while the finger rests there.
    private var dragPkg: String? = null
    private var dragY = 0f
    private var scrollDir = 0
    private val handler = Handler(Looper.getMainLooper())

    private fun attachDragSorting(list: ListView) {
        list.onItemLongClickListener =
            AdapterView.OnItemLongClickListener { _, row, pos, _ ->
                dragPkg = selectedAdapter.getItem(pos).pkg
                row.startDragAndDrop(null, View.DragShadowBuilder(row), null, 0)
                selectedAdapter.notifyDataSetChanged()
                true
            }
        val edge = 56 * dp
        val autoScroll =
            object : Runnable {
                override fun run() {
                    if (dragPkg == null || scrollDir == 0) return
                    list.smoothScrollBy(scrollDir * (12 * dp).toInt(), 0)
                    moveDraggedTo(list, dragY)
                    handler.postDelayed(this, 30)
                }
            }
        list.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> dragPkg != null
                DragEvent.ACTION_DRAG_LOCATION -> {
                    dragY = e.y
                    moveDraggedTo(list, e.y)
                    val dir =
                        if (e.y < edge) {
                            -1
                        } else if (e.y > list.height - edge) {
                            1
                        } else {
                            0
                        }
                    if (dir != scrollDir) {
                        scrollDir = dir
                        handler.removeCallbacks(autoScroll)
                        if (dir != 0) handler.post(autoScroll)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    scrollDir = 0
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    scrollDir = 0
                    handler.removeCallbacks(autoScroll)
                    if (dragPkg != null) {
                        dragPkg = null
                        save()
                        selectedAdapter.notifyDataSetChanged()
                    }
                    true
                }
                else -> true
            }
        }
    }

    /** Moves the dragged app to the row under [y] (list coordinates). */
    private fun moveDraggedTo(
        list: ListView,
        y: Float,
    ) {
        val pkg = dragPkg ?: return
        val to = list.pointToPosition((list.width / 2), y.toInt())
        val from = order.indexOf(pkg)
        if (to == AdapterView.INVALID_POSITION || from < 0 || to == from) return
        order.removeAt(from)
        order.add(to, pkg)
        refreshLists()
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
        selectedHeader.text = getString(R.string.header_selected, selectedAdapter.count)
        availableAdapter.set(
            all
                .filter { !it.selected }
                .filter { query.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(query) || it.pkg.contains(query) }
                .sortedBy { it.label.lowercase(Locale.getDefault()) },
        )
    }

    private fun save() {
        if (configLoaded) return
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
            val items =
                packageManager
                    .queryIntentActivities(launcher, 0)
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
                loading.visibility = View.GONE
            }
        }.start()
    }

    /** Spinner + "Loading apps…" on the surface colour; swallows taps while visible. */
    private fun loadingOverlay() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.md_surface))
            isClickable = true
            addView(
                ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(getColor(R.color.md_primary))
                },
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.loading_apps)
                    setTextColor(getColor(R.color.md_on_surface))
                    setPadding(0, (12 * dp).toInt(), 0, 0)
                },
            )
        }

    private fun weight() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            val m = (3 * dp).toInt()
            setMargins(m, 0, m, 0)
        }

    private fun button(
        text: String,
        help: String,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        tooltipText = help
        setOnClickListener { onClick() }
    }

    /** "Label [ 123 ] Sek" in one row; long-press shows [help] as tooltip. */
    private fun delayField(
        label: String,
        value: Int,
        parent: LinearLayout,
        help: String,
    ): EditText {
        val field =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(3))
                setText(value.toString())
                gravity = Gravity.END
                hint = "0"
                minEms = 3
                tooltipText = help
                contentDescription = help
            }
        val labelView =
            TextView(this).apply {
                text = label
                tooltipText = help
            }
        val unit = TextView(this).apply { text = getString(R.string.unit_sec) }
        val m = (6 * dp).toInt()
        parent.addView(labelView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = m })
        parent.addView(field)
        parent.addView(unit, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = m * 2 })
        return field
    }

    private class Holder(
        val icon: ImageView,
        val name: TextView,
        val pkg: TextView,
        val check: CheckBox,
    )

    private inner class AppAdapter : BaseAdapter() {
        private val items = mutableListOf<AppItem>()

        fun set(newItems: List<AppItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getCount() = items.size

        override fun getItem(position: Int) = items[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val row = convertView ?: newRow(parent)
            val h = row.tag as Holder
            val item = items[position]
            h.icon.setImageDrawable(item.icon)
            h.name.text = item.label
            h.pkg.text = item.pkg
            h.check.isChecked = item.selected
            // The dragged app stays visible as a faded placeholder at its new position.
            row.alpha = if (item.pkg == dragPkg) 0.3f else 1f
            return row
        }

        private fun newRow(parent: ViewGroup): View {
            val ctx = parent.context
            val p = (6 * dp).toInt()
            val row =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(p, p, p, p)
                }
            val size = (36 * dp).toInt()
            val icon = ImageView(ctx)
            row.addView(icon, LinearLayout.LayoutParams(size, size))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(ctx).apply { textSize = 15f }
            val pkg =
                TextView(ctx).apply {
                    textSize = 10f
                    setTextColor(getColor(R.color.md_on_surface_variant))
                }
            texts.addView(name)
            texts.addView(pkg)
            row.addView(
                texts,
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (10 * dp).toInt() },
            )
            val check =
                CheckBox(ctx).apply {
                    isClickable = false
                    isFocusable = false
                }
            row.addView(check)
            row.tag = Holder(icon, name, pkg, check)
            return row
        }
    }
}
