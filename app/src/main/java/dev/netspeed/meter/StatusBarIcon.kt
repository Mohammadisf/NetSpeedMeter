package dev.netspeed.meter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat
import java.util.Locale
import kotlin.math.abs

/**
 * Live status-bar icon — faithful port of the reference app's renderer
 * (class `f7b.a` + the value/unit picker inside `aa.l.o`):
 *
 *  - The reference shipped ~1,300 pre-rendered white icons (`wkb000`–
 *    `wkb999`, `wmb000`–`wmb281`: a big bold VALUE with a small bold
 *    UNIT line below) and picked one by the current speed each tick.
 *    Above ~28 MB/s it switched to the same rendering done live with a
 *    Canvas. We draw the identical glyphs for every value with the same
 *    Canvas code — one path, same look, always crisp at the device density.
 *  - Square bitmap, one size per density bucket exactly like the reference
 *    checked in `f7b.<clinit>` (120/160 dpi → 24 px, 240 → 36, 320 → 48,
 *    480 → 72, 640 → 96, else 72) and reused across ticks: the canvas is
 *    cleared and redrawn, exactly like the reference (`f7b.a`).
 *  - Text metrics are ported verbatim: number paint = white 1.2 × 0.65·H,
 *    baseline `|asc+desc| + |0.65·H − |asc+desc|| / 3`; unit paint
 *    (bottom 0.35·H strip) white 1.2 × 0.35·H with baseline
 *    `H − |(0.35·H − |asc+desc|| / 4|`.
 *  - Condensing matches the reference too: 3-char values → 0.75 horizontal
 *    scale, other values → 0.9; unit scaleX = 1.05 for "KB/s", 1.0 "MB/s".
 *  - Value ranges (reference `aa.l.o`): below 1 KB/s → "0 KB/s"; up to
 *    1 MB/s → whole KB 0…999; up to 10 MB/s → one decimal (e.g. "1.7");
 *    up to 999 MB/s → whole MB; ≥ 1 GB/s → "1+" GB/s. Speeds are the
 *    **combined download + upload** of the active connection.
 */
object StatusBarIcon {

    private const val NUMBER_ZONE = 0.65f   // top strip for the value
    private const val UNIT_ZONE = 0.35f     // bottom strip for the unit

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var speedPaint: Paint? = null
    private var unitPaint: Paint? = null
    private var centerX = 0f
    private var speedBaseline = 0f
    private var unitBaseline = 0f
    private var builtForDpi = 0

    /** Bitmap icon for [combined] bytes/s, in the reference app's style. */
    @Synchronized
    fun icon(context: Context, combined: Double): IconCompat {
        ensure(context)
        val (value, unit) = parts(combined.toLong().coerceAtLeast(0))

        val cv = canvas ?: return IconCompat.createWithBitmap(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        )
        val sp = speedPaint!!
        val up = unitPaint!!

        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        sp.textScaleX = if (value.length == 3) 0.75f else 0.9f
        cv.drawText(value, centerX, speedBaseline, sp)
        up.textScaleX = when (unit) {
            "KB/s" -> 1.05f
            else -> 1.0f
        }
        cv.drawText(unit, centerX, unitBaseline, up)
        return IconCompat.createWithBitmap(bitmap!!)
    }

    /**
     * Template value formatting (`aa.l.o` thresholds):
     * "0"/KB below 1 KB, whole KB up to 1 MB, one short decimal up to 10 MB,
     * whole MB beyond, "1+" GB/s past 999 MB/s.
     */
    private fun parts(bytesPerSec: Long): Pair<String, String> = when {
        bytesPerSec < 1024L -> "0" to "KB/s"
        bytesPerSec < MB -> (bytesPerSec / 1024L).toString() to "KB/s"
        bytesPerSec < 10 * MB -> {
            // Reference math: tenths = int(speed / MB*10 + 0.5), shown as x.y.
            val tenths = ((bytesPerSec.toDouble() / MB) * 10.0 + 0.5).toInt()
            val text = if (tenths % 10 == 0) (tenths / 10).toString()
            else String.format(Locale.US, "%.1f", tenths / 10.0)
            text to "MB/s"
        }
        bytesPerSec >= 999L * MB -> "1+" to "GB/s"
        else -> ((bytesPerSec.toDouble() / MB + 0.5).toInt()).toString() to "MB/s"
    }

    /**
     * (Re)builds the shared bitmap / canvas / paints. Same density buckets
     * as the reference renderer; rebuilding only on a density change keeps
     * the per-tick cost down to a couple of drawText calls.
     */
    private fun ensure(context: Context) {
        val density = context.resources.displayMetrics.densityDpi
        if (bitmap != null && builtForDpi == density) return
        builtForDpi = density

        val size = when (density) {
            120, 160 -> 24
            240 -> 36
            320 -> 48
            480 -> 72
            640 -> 96
            else -> 72 // reference fallback for unusual densities
        }
        bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)

        centerX = size / 2f
        val top = size * NUMBER_ZONE
        val bottom = size * UNIT_ZONE

        speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 1.2f * top
            val a = abs(ascent() + descent())
            speedBaseline = abs(top - a) / 3f + a
        }
        unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 1.2f * bottom
            val a = abs(ascent() + descent())
            unitBaseline = size - abs((bottom - a) / 4f)
        }
    }

    private const val MB = 1024L * 1024L
}
