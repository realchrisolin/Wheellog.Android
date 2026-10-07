package com.cooper.wheellog.data

import android.content.Context
import android.net.Uri
import android.os.Build
import com.cooper.wheellog.R
import com.cooper.wheellog.utils.LogHeaderEnum
import com.cooper.wheellog.utils.RideLogCsv
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale

object TripParser: KoinComponent {
    private val dao: TripDao by inject()
    private val header = HashMap<LogHeaderEnum, Int>()
    private var lastErrorValue: String? = null

    val lastError: String
        get() {
            val res = lastErrorValue ?: ""
            lastErrorValue = null
            return res
        }

    fun parseFile(context: Context, fileName: String, path: String, uri: Uri): Pair<List<LogTick>, TripDataDbEntry?> {
        lastErrorValue = null
        val inputStream: InputStream?
        try {
            inputStream = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9 or less
                FileInputStream(File(path))
            } else {
                // Android 10+
                context.contentResolver.openInputStream(uri)
            }
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
            return Pair(emptyList(), null)
        }
        return parseFile(context, fileName, inputStream)
    }

    fun parseFile(context: Context, fileName: String, inputStream: InputStream?): Pair<List<LogTick>, TripDataDbEntry?> {
        lastErrorValue = null
        header.clear()
        if (inputStream == null) {
            lastErrorValue = context.getString(R.string.error_inputstream_null)
            Timber.wtf(lastErrorValue)
            return Pair(emptyList(), null)
        }

        val reader = BufferedReader(InputStreamReader(inputStream))
        val ride = RideLogCsv.Reader(reader)
        header.putAll(ride.columns)
        if (!header.containsKey(LogHeaderEnum.LATITUDE) || !header.containsKey(LogHeaderEnum.LONGITUDE)) {
            inputStream.close()
            lastErrorValue = context.getString(R.string.error_this_file_without_gps, fileName)
            Timber.wtf(lastErrorValue)
            return Pair(emptyList(), null)
        }

        val sdfTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val sdfFullDate = SimpleDateFormat("dd HH:mm:ss.SSS", Locale.getDefault())
        val resultList = ArrayList<LogTick>()
        val useEpoch = header.containsKey(LogHeaderEnum.TIME_UNIX_MS)

        try {
            while (true) {
                val row = ride.readRow() ?: break
                // Charts still plot local civil time. The file offset recovers it from the UTC instant.
                val dateString: String
                val timeString: String
                if (useEpoch) {
                    val civil = RideLogCsv.civilTime(
                        ride.field(row, LogHeaderEnum.TIME_UNIX_MS).toLong(),
                        ride.offsetMillis,
                    )
                    dateString = civil.date
                    timeString = civil.time
                } else {
                    timeString = ride.field(row, LogHeaderEnum.TIME)
                    dateString = ride.field(row, LogHeaderEnum.DATE)
                }
                val logTick = LogTick(
                    timeString = timeString,
                    timePlusDayOfWeek = sdfFullDate.parse(
                        "$dateString $timeString",
                        ParsePosition(8)
                    )!!.time / 100f,
                    time = sdfTime.parse(timeString)!!.time / 100f,
                    latitude = ride.field(row, LogHeaderEnum.LATITUDE).toDoubleOrNull() ?: 0.0,
                    longitude = ride.field(row, LogHeaderEnum.LONGITUDE).toDoubleOrNull() ?: 0.0,
                    altitude = ride.field(row, LogHeaderEnum.GPS_ALT).toDoubleOrNull() ?: 0.0,
                    batteryLevel = ride.field(row, LogHeaderEnum.BATTERY_LEVEL).toIntOrNull()
                        ?: 0,
                    voltage = ride.field(row, LogHeaderEnum.VOLTAGE).toDoubleOrNull() ?: 0.0,
                    current = ride.field(row, LogHeaderEnum.CURRENT).toDoubleOrNull() ?: 0.0,
                    power = ride.field(row, LogHeaderEnum.POWER).toDoubleOrNull() ?: 0.0,
                    speed = ride.field(row, LogHeaderEnum.SPEED).toDoubleOrNull() ?: 0.0,
                    speedGps = ride.field(row, LogHeaderEnum.GPS_SPEED).toDoubleOrNull() ?: 0.0,
                    temperature = ride.field(row, LogHeaderEnum.SYSTEM_TEMP).toIntOrNull() ?: 0,
                    pwm = ride.field(row, LogHeaderEnum.PWM).toDoubleOrNull() ?: 0.0,
                    distance = ride.field(row, LogHeaderEnum.DISTANCE).toIntOrNull() ?: 0,
                    totalDistance = ride.field(row, LogHeaderEnum.TOTALDISTANCE).toIntOrNull()
                        ?: 0
                )
                resultList.add(logTick)
            }
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
            return Pair(resultList, null)
        } finally {
            inputStream.close()
        }

        var trip = dao.getTripByFileName(fileName)
        if (trip == null) {
            trip = TripDataDbEntry(fileName = fileName)
            dao.insert(trip)
        }
        try {
            val first = resultList.first()
            val last = resultList.last()
            trip.apply {
                duration = ((last.time - first.time) / 600.0).toInt()
                if (duration < 0) {
                    // +24 hours in minutes
                    duration += 1440
                }
                var timeToExclude = 0f
                var beforeTime = first.time // 1/10 sec
                var firstTotalDistance = 0

                resultList.forEach {
                    // If time between ticks more than 1 sec, we need to exclude this time
                    // or if time between ticks more than -1 hour (360 sec) next day ticks will be excluded
                    val timeBetween = it.time - beforeTime
                    if (-3600_0 > timeBetween || timeBetween > 10) {
                        timeToExclude += timeBetween
                    }
                    beforeTime = it.time

                    if (firstTotalDistance == 0 && it.totalDistance > 0) {
                        firstTotalDistance = it.totalDistance
                    }
                    maxSpeedGps = maxSpeedGps.coerceAtLeast(it.speedGps.toFloat())
                    maxCurrent = maxCurrent.coerceAtLeast(it.current.toFloat())
                    maxPwm = maxPwm.coerceAtLeast(it.pwm.toFloat())
                }

                val maxTotalDistance = resultList.subList(0.coerceAtLeast(resultList.size - 10), resultList.size).maxOf { it.totalDistance }
                val logDuration = (last.time - first.time - timeToExclude) / 600
                distance = maxTotalDistance - firstTotalDistance
                val powers = resultList.map { it.power }
                maxPower = powers.max().toFloat()
                val speeds = resultList.map { it.speed }
                maxSpeed = speeds.max().toFloat()
                avgSpeed = speeds.average().toFloat()
                consumptionTotal = powers.average().toFloat() * logDuration / 60F
                consumptionByKm = if (distance > 0) {
                    consumptionTotal * 1000F / distance
                } else {
                    0F
                }
            }
            dao.update(trip)
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
        }

        return Pair(resultList, trip)
    }
}
