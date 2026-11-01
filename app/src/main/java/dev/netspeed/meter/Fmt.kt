package dev.netspeed.meter

import java.text.DecimalFormat
import java.util.Locale

/**
 * Formatting helpers matching the reference app's notification/UI style:
 * speeds in bytes-per-second units with `##.#`-style precision, and usage
 * amounts in B/KB/MB/GB (binary multiples), like the reference "Usage… MB/GB".
 */
object Fmt {

    /** Template-style speed: <1024 B/s raw, then KB/s, then MB/s.
     *  When [bits] is set the value is shown in bits with SI (1000) steps. */
    fun speed(bytesPerSec: Double, bits: Boolean = false): String = if (bits) {
        val bps = bytesPerSec * 8.0
        when {
            bps < 0 -> "0 bit/s"
            bps < 1000 -> f.i.format(bps.toLong()) + " bit/s"
            bps < 1000.0 * 1000 -> f.k.format(bps / 1000.0) + " kbit/s"
            else -> f.m.format(bps / 1000.0 / 1000.0) + " Mbit/s"
        }
    } else when {
        bytesPerSec < 0 -> "0 B/s"
        bytesPerSec < 1024 -> f.i.format(bytesPerSec.toLong()) + " B/s"
        bytesPerSec < 1024.0 * 1024 -> f.k.format(bytesPerSec / 1024.0) + " KB/s"
        else -> f.m.format(bytesPerSec / 1024.0 / 1024.0) + " MB/s"
    }

    /** Usage amount from bytes: B / KB / MB / GB (binary multiples). */
    fun amount(bytes: Double): String = when {
        bytes < 1024 -> f.i.format(bytes.toLong().coerceAtLeast(0)) + " B"
        bytes < 1024.0 * 1024 -> f.k.format(bytes / 1024.0) + " KB"
        bytes < 1024.0 * 1024 * 1024 -> f.k.format(bytes / 1024.0 / 1024.0) + " MB"
        else -> f.m.format(bytes / 1024.0 / 1024.0 / 1024.0) + " GB"
    }

    private object f {
        /** Whole numbers, no grouping (reference "##" pattern). */
        val i = DecimalFormat("##", java.text.DecimalFormatSymbols(Locale.US))
        /** One decimal (reference "##.#" pattern). */
        val k = DecimalFormat("##.#", java.text.DecimalFormatSymbols(Locale.US))
        /** Two decimals for very large values (reference "##.##" pattern). */
        val m = DecimalFormat("##.##", java.text.DecimalFormatSymbols(Locale.US))
    }
}
