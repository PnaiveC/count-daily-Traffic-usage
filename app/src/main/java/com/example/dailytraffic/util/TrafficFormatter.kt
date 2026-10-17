package com.example.dailytraffic.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object TrafficFormatter {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")
    private val numberFormat = DecimalFormat(
        "0.#",
        DecimalFormatSymbols(Locale.US)
    )

    fun format(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes} B"
        } else {
            "${numberFormat.format(value)} ${units[unitIndex]}"
        }
    }
}
