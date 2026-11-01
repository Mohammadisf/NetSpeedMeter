package dev.netspeed.meter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.netspeed.meter.databinding.ActivityMainBinding

/**
 * Main screen — a re-implementation of the reference app's usage table:
 *
 *     Date | Mobile | WiFi | Total
 *     19-10-2026  512.4 KB  1.2 GB  1.2 GB
 *     ...
 *     Today
 *     Last 7 Days / Last 30 Days / This Month
 *
 * The archived day rows behind the summary can be expanded/collapsed
 * ("Show previous days ▾") — the choice is remembered in [Store].
 *
 * The ⚙ button in the top bar opens a draggable, expandable
 * [BottomSheetDialog] holding every option (live tracking, boot, lock-screen
 * notification, up/down speed, speed units, usage alerts, data limits,
 * usage chart, stats reset, language, theme and accent color) — instead of
 * the old ⋮ overflow menu and the settings block below the table.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var suppress = false // keep programmatic isChecked changes from re-triggering listeners
    /** Displayed node of the "Today" row, refreshed by the UPDATE receiver. */
    private var todayRowCells: List<TextView> = emptyList()

    private val rows = LinkedHashMap<String, List<TextView>>()
    /** Day rows whose visibility follows the expand/collapse state. */
    private val dayRowViews = ArrayList<View>()

    /** Live-tracking switch inside the bottom sheet (kept for the permission flow). */
    private var sheetLiveSwitch: SwitchCompat? = null

    /** Sub-value labels of the sheet rows, refreshed when a setting changes. */
    private val sheetValues = HashMap<String, TextView>()

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            binding.downSpeedValue.text = speedText(Engine.currentDown)
            binding.upSpeedValue.text = speedText(Engine.currentUp)
            updateTodayRow()
        }
    }

    // ----------------------------------------------------------------- theme

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme + accent must be applied before super.onCreate/setContentView.
        applyThemeBeforeInflate()
        super.onCreate(savedInstanceState)
        Engine.load(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildTable()

        // Expand/collapse the archived day history (from the title or the tail link).
        binding.usageTitle.setOnClickListener { toggleTable() }
        binding.tableToggle.setOnClickListener { toggleTable() }

        // ⚙ options button in the top bar → bottom sheet.
        binding.optionsButton.setOnClickListener { showOptionsSheet() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, updateReceiver,
            IntentFilter(SpeedService.ACTION_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(updateReceiver) }
        Engine.flush(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.downSpeedValue.text = speedText(Engine.currentDown)
        binding.upSpeedValue.text = speedText(Engine.currentUp)
        rebuildTable()
    }

    /** Speed in the user-selected unit (bytes or bits). */
    private fun speedText(bytesPerSec: Double): String =
        Fmt.speed(bytesPerSec, Store.useBits(this))

    // ----------------------------------------------------------------- theme

    private fun nightModeFor(mode: Store.ThemeMode): Int = when (mode) {
        Store.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        Store.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        Store.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Called from onCreate before super: night mode + accent theme style. */
    private fun applyThemeBeforeInflate() {
        delegate.localNightMode = nightModeFor(Store.themeMode(this))
        setTheme(themeResId())
    }

    private fun themeResId(): Int = when (Store.accentId(this)) {
        "green" -> R.style.Theme_NetSpeed_Green
        "purple" -> R.style.Theme_NetSpeed_Purple
        "orange" -> R.style.Theme_NetSpeed_Orange
        "red" -> R.style.Theme_NetSpeed_Red
        "teal" -> R.style.Theme_NetSpeed_Teal
        else -> R.style.Theme_NetSpeed // blue (default)
    }

    private fun setThemeModeAndApply(mode: Store.ThemeMode) {
        delegate.localNightMode = nightModeFor(mode)
        Store.setThemeMode(this, mode)
        recreate()
    }

    /** Resolves the theme's accent color with a blue fallback. */
    private fun accentColor(): Int {
        val v = TypedValue()
        if (theme.resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, v, true
            )
        ) {
            try {
                return ContextCompat.getColor(this, v.resourceId)
            } catch (_: Exception) {
            }
        }
        return 0xFF1565C0.toInt()
    }

    // ---------------------------------------------------------- bottom sheet

    /** Opens the draggable/expandable options sheet (replaces the ⋮ menu and
     *  the settings block that used to sit below the table). */
    private fun showOptionsSheet() {
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(buildSheetContent())
        // Collapsed at half the screen height; dragging up expands it fully
        // and the inner NestedScrollView reveals the remaining options.
        sheet.behavior.peekHeight =
            (resources.displayMetrics.heightPixels * 0.5).toInt()
        sheet.behavior.isFitToContents = true
        sheet.show()
    }

    /** Builds the sheet content: drag handle + scrollable option stack. */
    private fun buildSheetContent(): View {
        val density = resources.displayMetrics.density

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), (16 * density).toInt())
        }

        // Drag handle: swipe up on it (or anywhere) to expand the sheet.
        column.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (4 * density).toInt()
                ).also { it.setMargins(0, (10 * density).toInt(), 0, (10 * density).toInt()) }
                background = GradientDrawable().apply {
                    setColor(0x44888888.toInt())
                    cornerRadius = 2f * density
                }
            }
        )

        column.addView(
            TextView(this).apply {
                text = getString(R.string.options)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        column.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                ).also { it.setMargins(0, (10 * density).toInt(), 0, (6 * density).toInt()) }
                setBackgroundColor(0x33888888.toInt())
            }
        )

        // Options, in the required order.

        // 1. Live Speed Tracking
        val live = sheetSwitch(
            getString(R.string.service_toggle), SpeedService.running
        ) { checked ->
            if (checked) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    // Start after the permission flow completes; keep the
                    // switch off in the meantime.
                    suppress = true
                    sheetLiveSwitch?.isChecked = false
                    suppress = false
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                } else {
                    SpeedService.start(this)
                }
            } else {
                SpeedService.stop(this)
            }
        }
        sheetLiveSwitch = live
        column.addView(live)

        // Start-after-boot (used to sit below the table; kept next to its
        // companion toggles so it does not disappear).
        column.addView(sheetSwitch(
            getString(R.string.boot_toggle), Store.isStartOnBoot(this)
        ) { Store.setStartOnBoot(this, it) })

        // 2. Hide Lock Screen Notification
        column.addView(sheetSwitch(
            getString(R.string.hide_lockscreen_notif), Store.isHideLockscreenNotif(this)
        ) { Store.setHideLockscreenNotif(this, it) })

        // 3. Show Upload/Download Speed
        column.addView(sheetSwitch(
            getString(R.string.show_updown), Store.isShowUpDown(this)
        ) { Store.setShowUpDown(this, it) })

        // 4. Speed Units
        column.addView(sheetItem(R.string.speed_units) { askSpeedUnits() })

        // 5. Configure Usage Alerts
        column.addView(sheetItem(R.string.usage_alerts) { askUsageAlert() })

        // 6. Set Data Limit
        column.addView(sheetItem(R.string.limit_button) { askLimit() })

        // 6b. Set Hotspot Limit
        column.addView(sheetItem(R.string.hotspot_limit) { askHotspot() })

        // 7. Usage Chart
        column.addView(sheetItem(R.string.usage_chart) { showUsageChart() })

        // 8. Reset Statistics
        column.addView(sheetItem(R.string.reset_button) { confirmReset() })

        // 9. App Language
        column.addView(sheetItem(R.string.menu_language) { askLanguage() })

        // 10. Theme
        column.addView(sheetItem(R.string.menu_theme) { askTheme() })

        // 11. Accent Color
        column.addView(sheetItem(R.string.menu_accent) { askAccent() })

        // 12. About
        column.addView(sheetItem(R.string.menu_about) { showAbout() })

        updateSheetValues()

        return NestedScrollView(this).apply { addView(column) }
    }

    /**
     * A clickable, two-line row: the title on its own line and the live
     * sub-value underneath, both reading in the ambient layout direction.
     * Stacking them vertically keeps titles, values, switches and icons
     * apart in every language — RTL Persian included — so nothing overlaps
     * or gets squeezed against the row end.
     */
    private fun sheetItem(titleRes: Int, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (52 * density).toInt()
            background = resolveAttrBackground(android.R.attr.selectableItemBackground)
            setPadding(0, (11 * density).toInt(), 0, (11 * density).toInt())
            setOnClickListener { onClick() }
        }
        val title = TextView(this).apply {
            text = getString(titleRes)
            textSize = 15f
        }
        val value = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF888888.toInt())
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        row.addView(title)
        row.addView(value)
        sheetValues[cellKey(titleRes)] = value
        return row
    }

    /** Stable key per row so [updateSheetValues] can find the right label. */
    private fun cellKey(titleRes: Int): String = when (titleRes) {
        R.string.speed_units -> "units"
        R.string.usage_alerts -> "alerts"
        R.string.limit_button -> "limits"
        R.string.hotspot_limit -> "hotspot"
        R.string.menu_language -> "language"
        R.string.menu_theme -> "theme"
        R.string.menu_accent -> "accent"
        else -> "misc$titleRes"
    }

    private fun sheetSwitch(
        title: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): SwitchCompat = SwitchCompat(this).apply {
        text = title
        textSize = 15f
        isChecked = checked
        setPadding(
            0, (10 * resources.displayMetrics.density).toInt(),
            0, (10 * resources.displayMetrics.density).toInt()
        )
        setOnCheckedChangeListener { _, c -> if (!suppress) onChange(c) }
    }

    /** Refreshes all sheet row sub-values after a setting changed. */
    private fun updateSheetValues() {
        sheetValues.forEach { (key, tv) ->
            when (key) {
                "units" -> tv.text = if (Store.useBits(this))
                    getString(R.string.speed_unit_bits_short) else getString(R.string.speed_unit_bytes_short)
                "alerts" -> tv.text = alertsValue()
                "hotspot" -> tv.text = hotspotValue()
                "limits" -> tv.text = limitsValue()
                "language" -> tv.text = if (AppCompatDelegate.getApplicationLocales()
                        .toLanguageTags().startsWith("fa")
                ) "فارسی" else "English"
                "theme" -> tv.text = when (Store.themeMode(this)) {
                    Store.ThemeMode.LIGHT -> getString(R.string.theme_light)
                    Store.ThemeMode.DARK -> getString(R.string.theme_dark)
                    Store.ThemeMode.SYSTEM -> getString(R.string.theme_system)
                }
                "accent" -> tv.text = accentLabel(Store.accentId(this))
            }
        }
    }

    private fun alertsValue(): String =
        if (Store.alertLimitMb(this) > 0) getString(
            R.string.alerts_value, Store.alertLimitMb(this), Store.alertPeriodHours(this)
        ) else getString(R.string.alerts_off)

    private fun hotspotValue(): String =
        if (Store.hotspotLimitMb(this) > 0) getString(
            R.string.alerts_value, Store.hotspotLimitMb(this), Store.hotspotPeriodHours(this)
        ) else getString(R.string.alerts_off)

    private fun limitsValue(): String {
        val month = Store.sumThisMonth(this)
        val lines = ArrayList<String>(3)
        val wifiLimit = Store.limitWifiMb(this)
        val mobileLimit = Store.limitMobileMb(this)
        val combinedLimit = Store.limitCombinedMb(this)
        if (wifiLimit > 0) lines.add(
            getString(R.string.limit_line, getString(R.string.limit_name_wifi),
                Fmt.amount(month.second), Fmt.amount(wifiLimit * 1048576.0))
        )
        if (mobileLimit > 0) lines.add(
            getString(R.string.limit_line, getString(R.string.limit_name_mobile),
                Fmt.amount(month.first), Fmt.amount(mobileLimit * 1048576.0))
        )
        if (combinedLimit > 0) lines.add(
            getString(R.string.limit_line, getString(R.string.limit_name_combined),
                Fmt.amount(month.first + month.second), Fmt.amount(combinedLimit * 1048576.0))
        )
        return if (lines.isEmpty()) getString(R.string.limit_summary_off)
        else lines.joinToString("  ·  ")
    }

    private fun accentLabel(id: String): String = when (id) {
        "green" -> getString(R.string.accent_green)
        "purple" -> getString(R.string.accent_purple)
        "orange" -> getString(R.string.accent_orange)
        "red" -> getString(R.string.accent_red)
        "teal" -> getString(R.string.accent_teal)
        else -> getString(R.string.accent_blue)
    }

    // --------------------------------------------------------------- dialogs

    private fun askSpeedUnits() {
        val items = arrayOf(
            getString(R.string.speed_unit_bytes), getString(R.string.speed_unit_bits)
        )
        val current = if (Store.useBits(this)) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.speed_units))
            .setSingleChoiceItems(items, current) { dialog, which ->
                dialog.dismiss()
                Store.setUseBits(this, which == 1)
                refresh()
                updateSheetValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Usage-alert dialog: MB limit + rolling time-period in hours. */
    private fun askUsageAlert() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val limitInput = EditText(this).apply {
            hint = getString(R.string.alert_limit_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (Store.alertLimitMb(this@MainActivity) > 0)
                setText(Store.alertLimitMb(this@MainActivity).toString())
        }
        val periodInput = EditText(this).apply {
            hint = getString(R.string.alert_period_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Store.alertPeriodHours(this@MainActivity).toString())
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(
                TextView(context).apply {
                    text = getString(R.string.alerts_message)
                    setPadding(0, 0, 0, (12 * density).toInt())
                }
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.alert_limit_label)
                    setPadding(0, (8 * density).toInt(), 0, (2 * density).toInt())
                }
            )
            addView(limitInput)
            addView(
                TextView(context).apply {
                    text = getString(R.string.alert_period_label)
                    setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
                }
            )
            addView(periodInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.usage_alerts))
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Store.setAlertLimitMb(this, limitInput.text.toString().toLongOrNull() ?: 0L)
                val hours = periodInput.text.toString().toLongOrNull() ?: 24L
                if (hours > 0) Store.setAlertPeriodHours(this, hours)
                Store.setAlertFiredAt(this, 0L) // re-arm after a config change
                updateSheetValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askLanguage() {
        val items = arrayOf("English", "فارسی")
        val current = if (AppCompatDelegate.getApplicationLocales()
                .toLanguageTags().startsWith("fa")
        ) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.menu_language))
            .setSingleChoiceItems(items, current) { dialog, which ->
                dialog.dismiss()
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(if (which == 1) "fa" else "en")
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askTheme() {
        val items = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val current = when (Store.themeMode(this)) {
            Store.ThemeMode.LIGHT -> 1
            Store.ThemeMode.DARK -> 2
            Store.ThemeMode.SYSTEM -> 0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.menu_theme))
            .setSingleChoiceItems(items, current) { dialog, which ->
                dialog.dismiss()
                setThemeModeAndApply(
                    when (which) {
                        1 -> Store.ThemeMode.LIGHT
                        2 -> Store.ThemeMode.DARK
                        else -> Store.ThemeMode.SYSTEM
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askAccent() {
        val ids = listOf("blue", "green", "purple", "orange", "red", "teal")
        val items = ids.map { accentLabel(it) }.toTypedArray()
        val current = ids.indexOf(Store.accentId(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.menu_accent))
            .setSingleChoiceItems(items, current) { dialog, which ->
                dialog.dismiss()
                Store.setAccentId(this, ids[which])
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Usage chart dialog: 7-day / 30-day range switcher, a totals + daily
     * average summary line, and the stacked bar chart.
     */
    private fun showUsageChart() {
        val density = resources.displayMetrics.density

        val summary = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF888888.toInt())
            setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
        }
        val empty = TextView(this).apply {
            text = getString(R.string.chart_empty)
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (24 * density).toInt(), 0, (24 * density).toInt())
        }
        val chart = UsageChartView(this)
        chart.setAccent(accentColor())

        // Legend: colored swatch + label for each stacked segment.
        fun legendEntry(label: String, color: Int): LinearLayout {
            val entry = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, (16 * resources.displayMetrics.density).toInt(), 0)
            }
            entry.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = 3f * resources.displayMetrics.density
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        (12 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt()
                    )
                }
            )
            entry.addView(
                TextView(this@MainActivity).apply {
                    text = label
                    textSize = 12.5f
                    setTextColor(0xFF888888.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginStart = (7 * resources.displayMetrics.density).toInt() }
                }
            )
            return entry
        }

        var range = 7

        /** Fills chart + summary + visibility for the current range. */
        fun chartContent() {
            val data = chartData(range)
            chart.showBarValues = range <= 7
            chart.setData(data)
            val mob = data.sumOf { it.second }
            val wifi = data.sumOf { it.third }
            val total = mob + wifi
            summary.text = getString(
                R.string.chart_summary,
                Fmt.amount(total), Fmt.amount(mob), Fmt.amount(wifi)
            ) + "\n" + getString(R.string.chart_average, Fmt.amount(total / range))
            val hasData = total > 0.0
            chart.visibility = if (hasData) View.VISIBLE else View.GONE
            summary.visibility = if (hasData) View.VISIBLE else View.GONE
            empty.visibility = if (hasData) View.GONE else View.VISIBLE
        }

        // Range switcher: two pill buttons, the active one filled with the accent.
        fun mkButton(days: Int): TextView = TextView(this).apply {
            text = getString(if (days == 7) R.string.last7 else R.string.last30)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(
                (14 * density).toInt(), (7 * density).toInt(),
                (14 * density).toInt(), (7 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (10 * density).toInt() }
        }

        val button7 = mkButton(7)
        val button30 = mkButton(30)

        fun select(days: Int) {
            range = days
            styleRangeButton(button7, days == 7)
            styleRangeButton(button30, days == 30)
            chartContent()
        }
        button7.setOnClickListener { select(7) }
        button30.setOnClickListener { select(30) }
        styleRangeButton(button7, true)
        styleRangeButton(button30, false)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(), (10 * density).toInt(),
                (20 * density).toInt(), (4 * density).toInt()
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(button7)
                    addView(button30)
                }
            )
            addView(summary)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
                    addView(legendEntry(getString(R.string.col_mobile), accentColor()))
                    addView(legendEntry(getString(R.string.col_wifi), UsageChartView.WIFI_COLOR))
                }
            )
            addView(
                chart,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (260 * density).toInt()
                ).also { it.topMargin = (8 * density).toInt() }
            )
            addView(empty)
        }
        chartContent()

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.usage_chart))
            .setView(column)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Pill styling for the chart's range switcher buttons. */
    private fun styleRangeButton(button: TextView, selected: Boolean) {
        val density = resources.displayMetrics.density
        button.background = GradientDrawable().apply {
            cornerRadius = 18f * density
            if (selected) {
                setColor(accentColor())
                button.setTextColor(Color.WHITE)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(kotlin.math.max(1, dp(1)), accentColor())
                button.setTextColor(accentColor())
            }
        }
    }

    /** (label, mobile, wifi) triples for the last [n] days, oldest → today. */
    private fun chartData(n: Int): List<Triple<String, Double, Double>> {
        val entries = ArrayList<Triple<String, Double, Double>>(n)
        val cal = java.util.Calendar.getInstance()
        for (i in (n - 1) downTo 0) {
            val day = cal.clone() as java.util.Calendar
            day.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val key = Store.keyOf(day.time)
            val label = key.substring(0, 2) // dd of dd-MM-yyyy
            if (key == Store.todayKey()) {
                entries.add(Triple(label, Engine.todayMobileBytes, Engine.todayWifiBytes))
            } else {
                val usage = Store.day(this, key)
                entries.add(Triple(label, usage?.first ?: 0.0, usage?.second ?: 0.0))
            }
        }
        return entries
    }

    // ------------------------------------------------------------------ table

    private fun toggleTable() {
        val expanded = !Store.isTableExpanded(this)
        Store.setTableExpanded(this, expanded)
        applyTableExpanded()
    }

    private fun applyTableExpanded() {
        val expanded = Store.isTableExpanded(this)
        for (row in dayRowViews) row.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.tableToggle.text = getString(
            if (expanded) R.string.hide_previous_days else R.string.show_previous_days
        )
    }

    private fun buildTable() {
        val table = binding.usageTable
        table.removeAllViews()
        rows.clear()

        // Header row: Date | Mobile | Wifi | Total
        val header = newRow()
        header.forEach { applyCell(it, bold = true, grey = false) }
        header[0].text = getString(R.string.col_date)
        header[1].text = getString(R.string.col_mobile)
        header[2].text = getString(R.string.col_wifi)
        header[3].text = getString(R.string.col_total)
        table.addView(makeRowView(header), headerRowLayoutParams())

        // Today row (bold, like the reference "Today" line).
        val today = newRow()
        today.forEach { applyCell(it, bold = true, grey = false) }
        today[0].text = getString(R.string.today)
        table.addView(makeRowView(today), headerRowLayoutParams())
        rows["today"] = today

        // ~30 day rows (reference main.xml had a fixed row per previous day).
        // These are the expandable/collapsible rows.
        for (i in 0 until 30) {
            val cells = newRow()
            cells.forEach { applyCell(it, bold = false, grey = true) }
            val row = makeRowView(cells)
            table.addView(row, headerRowLayoutParams())
            rows["day$i"] = cells
            dayRowViews.add(row)
        }

        // Summary rows: bold, same style as the reference footers.
        val sum7 = newRow(); createSummaryCell(sum7)
        sum7[0].text = getString(R.string.last7)
        val sum30 = newRow(); createSummaryCell(sum30)
        sum30[0].text = getString(R.string.last30)
        val sumMonth = newRow(); createSummaryCell(sumMonth)
        sumMonth[0].text = getString(R.string.this_month)

        table.addView(makeRowView(sum7), headerRowLayoutParams())
        table.addView(makeRowView(sum30), headerRowLayoutParams())
        table.addView(makeRowView(sumMonth), headerRowLayoutParams())

        rows["sum7"] = sum7
        rows["sum30"] = sum30
        rows["sumMonth"] = sumMonth
        rebuildTable()
    }

    private fun newRow(): List<TextView> {
        val ctx = this
        val cells = ArrayList<TextView>(4)
        for (i in 0 until 4) {
            val tv = TextView(ctx).apply {
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                // START/END are direction-relative: they track the layout
                // direction (inherited from the table's locale direction), so
                // column alignment follows RTL together with the column order.
                gravity = if (i == 0) Gravity.START else Gravity.END
                // Subtle cell border for a distinct grid; padding keeps the
                // text off the stroke.
                background = cellBorderDrawable()
                setPadding(dp(6), dp(4), dp(6), dp(4))
            }
            cells.add(tv)
        }
        return cells
    }

    /** 1px translucent line around the cell — visible in light and dark. */
    private fun cellBorderDrawable(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(kotlin.math.max(1, dp(1)), 0x2E888888.toInt())
    }

    private fun boldCell(tv: TextView) {
        tv.setTypeface(null, Typeface.BOLD)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun greyCell(tv: TextView) {
        tv.setTypeface(null, Typeface.NORMAL)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setTextColor(0xFF888888.toInt())
    }

    private fun applyCell(tv: TextView, bold: Boolean, grey: Boolean) {
        if (bold) boldCell(tv) else greyCell(tv)
    }

    private fun createSummaryCell(cells: List<TextView>) {
        cells.forEachIndexed { idx, tv ->
            applyCell(tv, bold = true, grey = false)
            if (idx != 0) tv.text = ""
        }
    }

    private fun makeRowView(cells: List<TextView>): TableRow {
        val row = TableRow(this)
        cells.forEach { row.addView(it) }
        return row
    }

    private fun headerRowLayoutParams(): TableLayout.LayoutParams =
        TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Fills the table from [Store]; called after data changes. */
    private fun rebuildTable() {
        val today = listOf(Engine.todayMobileBytes, Engine.todayWifiBytes)

        // Today row.
        rows["today"]?.let { cells ->
            cells[0].text = getString(R.string.today)
            fillUsage(cells, today[0], today[1])
            todayRowCells = cells
        }

        // History days.
        val keys = Store.historyKeys(this)
        val dayCells = (0 until 30).mapNotNull { rows["day$it"] }
        dayCells.forEach { it.forEach { tv -> tv.text = "" } }
        var shown = 0
        for (key in keys) {
            if (shown >= dayCells.size) break
            val usage = Store.day(this, key) ?: continue
            val cells = dayCells[shown++]
            cells[0].text = key
            fillUsage(cells, usage.first, usage.second)
        }

        // Summary rows: computed from the source of truth.
        val cal7 = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -6)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val cal30 = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -29)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }

        val (sum7mob, sum7wifi) = sumTodayInRange(cal7)
        val (sum30mob, sum30wifi) = sumTodayInRange(cal30)
        val (monthMob, monthWifi) = Store.sumThisMonth(this)

        rows["sum7"]?.let { fillUsage(it, sum7mob, sum7wifi) }
        rows["sum30"]?.let { fillUsage(it, sum30mob, sum30wifi) }
        rows["sumMonth"]?.let { fillUsage(it, monthMob, monthWifi) }

        applyTableExpanded()
    }

    /** Archived + live usage since [cal] (inclusive). */
    private fun sumTodayInRange(cal: java.util.Calendar): Pair<Double, Double> {
        val (mob, wifi) = Store.sumRange(this, cal.time, java.util.Date())
        var thisMob = mob; var thisWifi = wifi
        if (!java.util.Date().before(cal.time)) {
            thisMob += Engine.todayMobileBytes
            thisWifi += Engine.todayWifiBytes
        }
        return thisMob to thisWifi
    }

    private fun fillUsage(cells: List<TextView>, mobBytes: Double, wifiBytes: Double) {
        cells[1].text = Fmt.amount(mobBytes)
        cells[2].text = Fmt.amount(wifiBytes)
        cells[3].text = Fmt.amount(mobBytes + wifiBytes)
    }

    private fun updateTodayRow() {
        val cells = todayRowCells
        if (cells.isEmpty()) return
        fillUsage(cells, Engine.todayMobileBytes, Engine.todayWifiBytes)
    }

    // -------------------------------------------------------------- settings

        /**
     * Hotspot-limit dialog: MB limit for the tethered clients' usage + a
     * rolling time period, with an optional best-effort auto-shutdown of the
     * hotspot. Requests the hotspot-control permission when needed.
     */
    private fun askHotspot() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val limitInput = EditText(this).apply {
            hint = getString(R.string.alert_limit_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (Store.hotspotLimitMb(this@MainActivity) > 0)
                setText(Store.hotspotLimitMb(this@MainActivity).toString())
        }
        val periodInput = EditText(this).apply {
            hint = getString(R.string.alert_period_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Store.hotspotPeriodHours(this@MainActivity).toString())
        }
        val enforceSwitch = sheetSwitch(
            getString(R.string.enforce_hotspot), Store.isEnforceHotspot(this)
        ) { checked ->
            if (checked) requestHotspotPermissionIfNeeded()
        }

        // Honest platform-capability note under the enforcement switch.
        val note = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            if (!ConnectionEnforcer.canDisableHotspot(this@MainActivity))
                text = getString(R.string.enforce_hotspot_note)
        }
        if (enforceSwitch.isChecked && note.text.isNotEmpty()) note.visibility = View.VISIBLE

        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(
                TextView(context).apply {
                    text = getString(R.string.hotspot_message)
                    setPadding(0, 0, 0, (12 * density).toInt())
                }
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.hotspot_limit_label)
                    setPadding(0, (8 * density).toInt(), 0, (2 * density).toInt())
                }
            )
            addView(limitInput)
            addView(
                TextView(context).apply {
                    text = getString(R.string.alert_period_label)
                    setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
                }
            )
            addView(periodInput)
            addView(enforceSwitch)
            addView(note)
            addView(
                TextView(context).apply {
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                    setPadding(0, (6 * density).toInt(), 0, 0)
                    text = getString(R.string.hotspot_usage_note)
                }
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hotspot_limit))
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Store.setHotspotLimitMb(this, limitInput.text.toString().toLongOrNull() ?: 0L)
                val hours = periodInput.text.toString().toLongOrNull() ?: 24L
                if (hours > 0) Store.setHotspotPeriodHours(this, hours)
                Store.setEnforceHotspot(this, enforceSwitch.isChecked)
                if (enforceSwitch.isChecked) requestHotspotPermissionIfNeeded()
                Store.setHotspotFiredAt(this, 0L) // re-arm after a config change
                afterEnforcementConfigured(enforceSwitch.isChecked)
                // Hotspot counters need the platform's Usage data access on
                // Android 16-like devices; ask once while we're in the
                // foreground.
                if (enforceSwitch.isChecked && HotspotUsage.needsUsageAccess(this)) {
                    try {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
                updateSheetValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Runtime hotspot-control permission (Android 13+). */
    private fun requestHotspotPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), REQ_HOTSPOT_PERM
            )
        }
    }

    /**
     * Shared OK-handler for both limit dialogs: with enforcement enabled it
     * requests the one-time VPN consent needed by the kill-switch fallback
     * and keeps the speed service alive (it is the monitor); with
     * enforcement off it releases any active block (with a 24 h grace so a
     * still-exceeded limit is not instantly re-armed).
     */
    private fun afterEnforcementConfigured(enforceCheckedNow: Boolean) {
        if (enforceCheckedNow) {
            ConnectionEnforcer.requestConsentIfNeeded(this)
            ensureMonitorRunning()
        } else {
            EnforceVpnService.release(this)
        }
    }

    /** limit monitoring runs on the speed service; keep it alive. */
    private fun ensureMonitorRunning() {
        if (SpeedService.running) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            SpeedService.start(this)
        }
    }

    /** Data-limit dialog: three independent monthly limits, MB per field. */
    private fun askLimit() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        data class Field(val label: String, val existing: Long)

        val fields = listOf(
            Field(getString(R.string.limit_wifi_label), Store.limitWifiMb(this)),
            Field(getString(R.string.limit_mobile_label), Store.limitMobileMb(this)),
            Field(getString(R.string.limit_combined_label), Store.limitCombinedMb(this))
        )

        val inputs = ArrayList<EditText>(fields.size)
        val enforceSwitch = sheetSwitch(
            getString(R.string.enforce_limits), Store.isEnforceLimits(this)
        ) { /* saved with OK */ }
        enforceSwitch.isChecked = Store.isEnforceLimits(this)

        // Honest platform-capability note under the enforcement switch.
        val note = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            if (!ConnectionEnforcer.canEnforceDataLimits(this@MainActivity))
                text = getString(R.string.enforce_unsupported_note)
        }
        if (enforceSwitch.isChecked && note.text.isNotEmpty()) note.visibility = View.VISIBLE

        val monitorHint = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            text = getString(R.string.limit_monitor_hint)
        }

        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(
                TextView(context).apply {
                    text = getString(R.string.limit_message)
                    setPadding(0, 0, 0, (6 * density).toInt())
                }
            )
        }
        fields.forEach { field ->
            holder.addView(
                TextView(this).apply {
                    text = field.label
                    setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
                }
            )
            val input = EditText(this).apply {
                hint = getString(R.string.limit_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                if (field.existing > 0) setText(field.existing.toString())
            }
            holder.addView(input)
            inputs.add(input)
        }
        holder.addView(enforceSwitch)
        holder.addView(note)
        holder.addView(monitorHint)

        // The dialog body sits in a scroll view so the OK / Cancel button
        // bar stays visible whatever the content height.
        val scroll = NestedScrollView(this).apply { addView(holder) }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.limit_button))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Store.setLimitWifiMb(this, inputs[0].text.toString().toLongOrNull() ?: 0L)
                Store.setLimitMobileMb(this, inputs[1].text.toString().toLongOrNull() ?: 0L)
                Store.setLimitCombinedMb(this, inputs[2].text.toString().toLongOrNull() ?: 0L)
                Store.setEnforceLimits(this, enforceSwitch.isChecked)
                Store.clearLimitFiredFlags(this) // re-arm: a new limit may already be exceeded
                afterEnforcementConfigured(enforceSwitch.isChecked)
                updateSheetValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** About dialog: app name, version and developer. */
    private fun showAbout() {
        val density = resources.displayMetrics.density
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            addView(
                TextView(context).apply {
                    // Brand name in Latin script in every language.
                    text = getString(R.string.about_brand)
                    textSize = 21f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, (4 * density).toInt(), 0, 0)
                }
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.about_version, version)
                    textSize = 13.5f
                    setTextColor(0xFF888888.toInt())
                }
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.about_developer)
                    textSize = 13.5f
                    setTextColor(0xFF888888.toInt())
                    setPadding(0, (10 * density).toInt(), 0, 0)
                }
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.menu_about))
            .setView(holder)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reset_button))
            .setMessage(getString(R.string.reset_message))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Engine.resetHistory(this)
                rebuildTable()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            // The user enabled live tracking from the sheet but needed the
            // notification permission first; start the service now.
            SpeedService.start(this)
        }
        sheetLiveSwitch = null
    }

    companion object {
        private const val REQ_NOTIF = 1
        private const val REQ_HOTSPOT_PERM = 2
    }
}

/** Resolves a theme attribute into a drawable (row ripple background). */
private fun Context.resolveAttrBackground(attr: Int): android.graphics.drawable.Drawable? {
    val v = TypedValue()
    if (!theme.resolveAttribute(attr, v, true)) return null
    if (v.resourceId == 0) return null
    return try {
        ContextCompat.getDrawable(this, v.resourceId)
    } catch (_: Exception) {
        null
    }
}
