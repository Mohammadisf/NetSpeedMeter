package dev.netspeed.meter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Stacked bar chart (no external dependency): one bar per day, mobile portion
 * (accent color) stacked below the WiFi portion (green). Drawn with plain
 * Canvas code in the same spirit as [StatusBarIcon].
 *
 * Informative extras:
 *  - horizontal grid lines at 50 % and 100 % of the scale, with the matching
 *    usage amounts written next to them (Fmt.amount format);
 *  - the total amount written above each bar (when space allows — toggled off
 *    for dense 30-bar views);
 *  - day-of-month labels under the bars (every bar when wide, otherwise
 *    every 5th bar plus the last one); the final (today) label is accented.
 */
class UsageChartView(context: Context) : View(context) {

    private class Day(val label: String, val mobile: Double, val wifi: Double)

    private val days = ArrayList<Day>()
    private var accentColor = 0xFF1565C0.toInt()

    companion object {
        /** WiFi bar color — shared with the legend in the chart dialog. */
        val WIFI_COLOR: Int = 0xFF43A047.toInt()
    }

    private val barPaint = Paint()
    private var gridPaint = Paint().apply { color = 0x26888888.toInt() }
    private var axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.LEFT
    }
    private var labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private var valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    /** Show the total amount above each bar (only when bars are wide enough). */
    var showBarValues = true

    /** Current accent color (applied before drawing, e.g. after a theme change). */
    fun setAccent(color: Int) {
        accentColor = color
        invalidate()
    }

    /** Bar entries in left-to-right order: (label, mobileBytes, wifiBytes). */
    fun setData(entries: List<Triple<String, Double, Double>>) {
        days.clear()
        days.addAll(entries.map { Day(it.first, it.second, it.third) })
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val labelZone = 18f * density   // day labels under the bars
        val topZone = 16f * density     // room for the "above bar" value labels
        val maxLabelZone = 14f * density // room for the y-axis amount labels
        val chartTop = topZone + maxLabelZone
        val chartBottom = height - labelZone
        if (chartBottom <= chartTop || days.isEmpty()) return

        val usable = chartBottom - chartTop

        // Scale: tallest bar + 15 % headroom, at least 1 MB so an all-zero
        // history still renders a readable scale.
        val maxTotal = days.maxOfOrNull { it.mobile + it.wifi } ?: 0.0
        val scaleMax = (maxTotal * 1.15).coerceAtLeast(1048576.0)

        // Baseline + grid lines at 50 % / 100 % of the scale, with amounts.
        gridPaint.strokeWidth = kotlin.math.max(1f, density)
        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, gridPaint)
        for (fraction in listOf(0.5f, 1f)) {
            val y = chartBottom - (usable * fraction).toFloat()
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            canvas.drawText(
                Fmt.amount(scaleMax * fraction),
                2f * density, y - 2f * density, axisPaint
            )
        }

        val slot = width.toFloat() / days.size
        val barWidth = slot * 0.55f
        val dense = days.size > 10 // 30-day view: skip per-bar value labels & most labels

        days.forEachIndexed { i, day ->
            val left = slot * i + (slot - barWidth) / 2f
            val total = day.mobile + day.wifi
            if (total <= 0.0) return@forEachIndexed

            val mobileHeight = (day.mobile / scaleMax * usable).toFloat()
            val wifiHeight = (day.wifi / scaleMax * usable).toFloat()
            val totalHeight = mobileHeight + wifiHeight

            // Stacked: mobile (accent) from the baseline up, WiFi (green) on top.
            if (wifiHeight > 0f) {
                barPaint.color = UsageChartView.WIFI_COLOR
                canvas.drawRect(
                    left, chartBottom - totalHeight, left + barWidth,
                    chartBottom - mobileHeight, barPaint
                )
            }
            if (mobileHeight > 0f) {
                barPaint.color = accentColor
                canvas.drawRect(
                    left, chartBottom - mobileHeight, left + barWidth,
                    chartBottom, barPaint
                )
            }

            // Total amount above the bar (only when there is room).
            if (showBarValues && !dense && total > 0.0) {
                valuePaint.color = if (i == days.lastIndex) accentColor else 0xFF666666.toInt()
                canvas.drawText(
                    Fmt.amount(total), left + barWidth / 2f,
                    chartBottom - totalHeight - 3f * density, valuePaint
                )
            }

            // Day labels: every bar when wide, otherwise every 5th + last.
            if (!dense || i % 5 == 0 || i == days.lastIndex) {
                labelPaint.color = if (i == days.lastIndex) accentColor else 0xFF888888.toInt()
                labelPaint.typeface =
                    if (i == days.lastIndex) android.graphics.Typeface.DEFAULT_BOLD
                    else android.graphics.Typeface.DEFAULT
                canvas.drawText(
                    day.label, left + barWidth / 2f,
                    chartBottom + labelZone * 0.8f, labelPaint
                )
            }
        }
    }
}
