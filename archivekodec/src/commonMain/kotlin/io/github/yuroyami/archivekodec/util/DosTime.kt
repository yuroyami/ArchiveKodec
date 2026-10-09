package io.github.yuroyami.archivekodec.util

/**
 * MS-DOS date and time fields, as used by ZIP (APPNOTE 4.4.6). They carry no time zone;
 * this library reads and writes them as UTC so results do not depend on the machine.
 */
internal object DosTime {
    /** 1980-01-01 00:00:00 UTC, the earliest DOS time. */
    const val MIN_EPOCH_SECONDS = 315_532_800L

    /** 2107-12-31 23:59:58 UTC, the latest DOS time. */
    const val MAX_EPOCH_SECONDS = 4_354_819_198L

    /** Epoch seconds for a DOS [date] and [time], or null when a field is out of range. */
    fun toEpochSeconds(date: Int, time: Int): Long? {
        val year = 1980 + (date shr 9 and 0x7F)
        val month = date shr 5 and 0x0F
        val day = date and 0x1F
        val hour = time shr 11 and 0x1F
        val minute = time shr 5 and 0x3F
        val second = (time and 0x1F) * 2
        if (month !in 1..12 || day !in 1..daysInMonth(year, month) || hour > 23 || minute > 59 || second > 59) return null
        return daysFromCivil(year, month, day) * 86_400L + hour * 3600L + minute * 60L + second
    }

    /**
     * DOS date in the high 16 bits and time in the low 16 bits. Times are clamped to the
     * DOS range, and odd seconds round down to the 2-second DOS precision.
     */
    fun fromEpochSeconds(epochSeconds: Long): Int {
        val clamped = epochSeconds.coerceIn(MIN_EPOCH_SECONDS, MAX_EPOCH_SECONDS)
        val days = clamped / 86_400
        val secondsOfDay = (clamped % 86_400).toInt()
        val (year, month, day) = civilFromDays(days)
        val date = (year - 1980) shl 9 or (month shl 5) or day
        val time = (secondsOfDay / 3600) shl 11 or ((secondsOfDay / 60 % 60) shl 5) or (secondsOfDay % 60 / 2)
        return date shl 16 or time
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    // Howard Hinnant's days_from_civil and civil_from_days, for the proleptic Gregorian calendar.
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = (z - era * 146_097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = (yoe + era * 400).toInt() + if (month <= 2) 1 else 0
        return Triple(year, month, day)
    }
}
