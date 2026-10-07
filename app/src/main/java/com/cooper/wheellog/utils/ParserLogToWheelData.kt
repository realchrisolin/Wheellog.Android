package com.cooper.wheellog.utils

import com.cooper.wheellog.R
import com.cooper.wheellog.WheelData
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

class ParserLogToWheelData {
    fun parseFile(fileUtil: FileUtil) {
        val inputStream = fileUtil.inputStream
        if (inputStream == null) {
            Timber.wtf(fileUtil.context.getString(R.string.error_inputstream_null) + " " + fileUtil.fileName)
            return
        }

        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            val ride = RideLogCsv.Reader(reader)
            if (!ride.columns.containsKey(LogHeaderEnum.LATITUDE) || !ride.columns.containsKey(LogHeaderEnum.LONGITUDE)) {
                Timber.wtf(fileUtil.context.getString(R.string.error_this_file_without_gps, fileUtil.fileName))
                return
            }
            val firstRow = ride.readRow() ?: return
            val epoch = ride.columns.containsKey(LogHeaderEnum.TIME_UNIX_MS)
            val sdfFull = SimpleDateFormat("yyyy-MM-dd,HH:mm:ss.SSS", Locale.US)
            val sdfTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

            fun rowTime(row: List<String>): Long {
                return if (epoch) {
                    ride.field(row, LogHeaderEnum.TIME_UNIX_MS).toLong()
                } else {
                    sdfTime.parse(ride.field(row, LogHeaderEnum.TIME))!!.time
                }
            }

            val startTime = if (epoch) {
                ride.field(firstRow, LogHeaderEnum.TIME_UNIX_MS).toLong()
            } else {
                sdfFull.parse(
                    ride.field(firstRow, LogHeaderEnum.DATE) + "," + ride.field(firstRow, LogHeaderEnum.TIME)
                )!!.time
            }
            val wd = WheelData.getInstance()
            wd.setStartParameters(
                startTime,
                ride.field(firstRow, LogHeaderEnum.TOTALDISTANCE).toLongOrNull() ?: 0L
            )

            var lastTime = rowTime(firstRow)
            while (true) {
                val row = ride.readRow() ?: break
                val speed = (100 * (ride.field(row, LogHeaderEnum.SPEED).toDoubleOrNull() ?: 0.0)).toInt()
                wd.topSpeed = speed
                val voltage = (100 * (ride.field(row, LogHeaderEnum.VOLTAGE).toDoubleOrNull() ?: 0.0)).toInt()
                wd.voltage = voltage
                wd.voltageSag
                wd.phaseCurrent = (100 * (ride.field(row, LogHeaderEnum.PHASE_CURRENT).toDoubleOrNull() ?: 0.0)).toInt()
                wd.current = (100 * (ride.field(row, LogHeaderEnum.CURRENT).toDoubleOrNull() ?: 0.0)).toInt()
                wd.batteryLevel = ride.field(row, LogHeaderEnum.BATTERY_LEVEL).toIntOrNull() ?: 0
                wd.totalDistance = ride.field(row, LogHeaderEnum.TOTALDISTANCE).toLongOrNull() ?: 0
                wd.maxTemp = ride.field(row, LogHeaderEnum.SYSTEM_TEMP).toIntOrNull() ?: 0
                wd.maxPwm = (ride.field(row, LogHeaderEnum.PWM).toDoubleOrNull() ?: 0.0) / 100

                val time = rowTime(row)
                if (time >= lastTime + 1000 && speed > 200) {
                    wd.incrementRidingTime()
                    lastTime = time
                }
            }
            wd.updateRideTime()
        } catch (ex: Exception) {
            Timber.wtf(ex.localizedMessage)
        } finally {
            inputStream.close()
        }
    }
}
