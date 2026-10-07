package com.cooper.wheellog.utils

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Ride log written by LoggingService.
 * The first line stores the schema version and the device UTC offset at file creation.
 * time_unix_ms is UTC epoch milliseconds. mode and alert are quoted per RFC 4180.
 * Older logs have no preamble and use date,time. Both shapes still parse.
 */
object RideLogCsv {
    const val VERSION = 2
    const val HEADER =
        "time_unix_ms,latitude,longitude,gps_speed,gps_alt,gps_heading,gps_distance,speed,voltage,phase_current,current,power,torque,pwm,battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"

    data class CivilTime(val date: String, val time: String)

    fun preamble(offsetMillis: Int): String {
        return "# wheellog_csv=$VERSION timezone_offset=${formatOffset(offsetMillis)}"
    }

    fun formatOffset(offsetMillis: Int): String {
        if (offsetMillis == 0) {
            return "Z"
        }
        val sign = if (offsetMillis < 0) "-" else "+"
        val abs = Math.abs(offsetMillis)
        val hours = abs / 3_600_000
        val minutes = (abs % 3_600_000) / 60_000
        return String.format(Locale.US, "%s%02d:%02d", sign, hours, minutes)
    }

    fun offsetMillisOf(preambleLine: String): Int {
        val token = preambleLine.split(Regex("\\s+"))
            .firstOrNull { it.startsWith("timezone_offset=") }
            ?.substringAfter("timezone_offset=")
            ?: return 0
        return parseOffsetToken(token) ?: 0
    }

    /**
     * mode and alert are always quoted. A comma, quote, or line break in either
     * would otherwise shift the columns that follow, including coordinates.
     */
    fun quoteField(value: String?): String {
        val singleLine = (value ?: "")
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
        return "\"" + singleLine.replace("\"", "\"\"") + "\""
    }

    fun dataLine(
        timeUnixMs: Long,
        latitude: String,
        longitude: String,
        gpsSpeed: String,
        gpsAltitude: String,
        gpsHeading: String,
        gpsDistanceMeters: Double,
        speed: Double,
        voltage: Double,
        phaseCurrent: Double,
        current: Double,
        power: Double,
        torque: Double,
        pwm: Double,
        batteryLevel: Int,
        distance: Int,
        totalDistance: Long,
        systemTemp: Int,
        temp2: Int,
        tilt: Double,
        roll: Double,
        mode: String?,
        alert: String?,
    ): String {
        return String.format(
            Locale.US,
            "%d,%s,%s,%s,%s,%s,%.0f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%.2f,%.2f,%s,%s",
            timeUnixMs,
            latitude,
            longitude,
            gpsSpeed,
            gpsAltitude,
            gpsHeading,
            gpsDistanceMeters,
            speed,
            voltage,
            phaseCurrent,
            current,
            power,
            torque,
            pwm,
            batteryLevel,
            distance,
            totalDistance,
            systemTemp,
            temp2,
            tilt,
            roll,
            quoteField(mode),
            quoteField(alert),
        )
    }

    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i++
                    continue
                }
                field.append(c)
                i++
                continue
            }
            when (c) {
                ',' -> {
                    out.add(field.toString())
                    field.setLength(0)
                    i++
                }
                '"' -> {
                    inQuotes = true
                    i++
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
        out.add(field.toString())
        return out
    }

    /** Local civil time for chart axes. The file offset is applied before reading the clock. */
    fun civilTime(epochMs: Long, offsetMillis: Int): CivilTime {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = epochMs + offsetMillis
        val date = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        val time = String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND),
        )
        return CivilTime(date, time)
    }

    fun isCurrentLog(input: InputStream): Boolean {
        return try {
            BufferedReader(InputStreamReader(input)).use { reader ->
                val first = reader.readLine() ?: return@use false
                val header = if (first.startsWith("#")) {
                    reader.readLine() ?: return@use false
                } else {
                    first
                }
                header == HEADER
            }
        } catch (ex: IOException) {
            false
        }
    }

    private fun parseOffsetToken(token: String): Int? {
        if (token == "Z" || token == "z" || token == "+00:00" || token == "-00:00") {
            return 0
        }
        val match = Regex("""^([+-])(\d{2}):(\d{2})$""").matchEntire(token) ?: return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val hours = match.groupValues[2].toInt()
        val minutes = match.groupValues[3].toInt()
        if (hours > 18 || minutes > 59) {
            return null
        }
        return sign * (hours * 3_600_000 + minutes * 60_000)
    }

    class Reader(private val reader: BufferedReader) {
        val columns: Map<LogHeaderEnum, Int>
        val offsetMillis: Int

        init {
            var line = reader.readLine()
            var offset = 0
            if (line != null && line.startsWith("#")) {
                offset = offsetMillisOf(line)
                line = reader.readLine()
            }
            val headerCells = if (line.isNullOrEmpty()) emptyList() else parseLine(line)
            val map = HashMap<LogHeaderEnum, Int>()
            for (i in headerCells.indices) {
                try {
                    map[LogHeaderEnum.valueOf(headerCells[i].uppercase(Locale.US))] = i
                } catch (ignored: IllegalArgumentException) {
                }
            }
            columns = map
            offsetMillis = offset
        }

        fun readRow(): List<String>? {
            while (true) {
                val line = reader.readLine() ?: return null
                if (line.isEmpty()) {
                    continue
                }
                return parseLine(line)
            }
        }

        fun field(row: List<String>, key: LogHeaderEnum): String {
            return row[columns[key]!!]
        }
    }
}
