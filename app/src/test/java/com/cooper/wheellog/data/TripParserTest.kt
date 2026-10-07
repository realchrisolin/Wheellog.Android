package com.cooper.wheellog.data

import android.content.Context
import com.cooper.wheellog.ElectroClub
import com.cooper.wheellog.WheelLog
import com.cooper.wheellog.utils.RideLogCsv
import com.google.common.collect.Range
import com.google.common.truth.Truth.*
import io.mockk.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.io.InputStream
import kotlin.system.measureTimeMillis

class TripParserTest {
    companion object {
        private val dao = mockkClass(TripDao::class, relaxed = false)

        @JvmStatic
        @BeforeClass
        fun startKoinOnce() {
            startKoin {
                modules(
                    module {
                        single { dao }
                    }
                )
            }
        }

        @JvmStatic
        @AfterClass
        fun stopKoinOnce() {
            stopKoin()
            unmockkAll()
        }
    }

    @Before
    fun setUp() {
        clearAllMocks()
    }

    @Test
    fun parseFile() {
        // Arrange.
        val context = mockkClass(Context::class, relaxed = true)
        val trip = TripDataDbEntry(fileName = "test")
        every { dao.getTripByFileName(any()) } returns trip
        justRun { dao.update(any()) }
        val inputStream: InputStream = File("src/test/resources/log_test1.csv").inputStream()

        // Act.
        measureTimeMillis {
            TripParser.parseFile(context, "test", inputStream)
        }.also {
            println("parseFile took $it ms")
        }

        // Assert.
        assertThat(trip.duration).isEqualTo(880)
        assertThat(trip.distance).isEqualTo(19_493)
        assertThat(trip.maxSpeedGps).isIn(Range.closed(66f, 66.2f))
        assertThat(trip.maxCurrent).isIn(Range.closed(86.8f, 86.9f))
        assertThat(trip.maxPwm).isIn(Range.closed(86.7f, 86.8f))
        assertThat(trip.maxPower).isIn(Range.closed(7825f, 7826f))
        assertThat(trip.maxSpeed).isIn(Range.closed(71f, 71.05f))
        assertThat(trip.avgSpeed).isIn(Range.closed(29.7f, 29.8f))
        assertThat(trip.consumptionTotal).isIn(Range.closed(1059f, 1060f))
        assertThat(trip.consumptionByKm).isIn(Range.closed(54.3f, 54.4f))
    }

    @Test
    fun `parseFile reads utc epoch rows in the file offset`() {
        val context = mockkClass(Context::class, relaxed = true)
        val trip = TripDataDbEntry(fileName = "epoch")
        every { dao.getTripByFileName(any()) } returns trip
        justRun { dao.update(any()) }
        val csv = buildString {
            append(RideLogCsv.preamble(-5 * 3_600_000))
            append('\n')
            append(RideLogCsv.HEADER)
            append('\n')
            append(row(3_600_000L, 43.1, 10.0, 1.0, 1000L, "Drive"))
            append('\n')
            append(row(7_200_000L, 43.2, 20.0, 2.0, 2500L, "Drive, night"))
            append('\n')
        }

        val parsed = TripParser.parseFile(context, "epoch", csv.byteInputStream())

        assertThat(parsed.first).hasSize(2)
        assertThat(parsed.first[0].timeString).isEqualTo("20:00:00.000")
        assertThat(parsed.first[1].timeString).isEqualTo("21:00:00.000")
        assertThat(parsed.first[0].latitude).isEqualTo(43.1)
        assertThat(parsed.first[0].speed).isEqualTo(10.0)
        assertThat(parsed.first[1].speed).isEqualTo(20.0)
        assertThat(parsed.second).isSameInstanceAs(trip)
        assertThat(trip.duration).isEqualTo(60)
        assertThat(trip.distance).isEqualTo(1500)
        assertThat(trip.maxSpeed).isEqualTo(20f)
        assertThat(TripParser.lastError).isEmpty()
    }

    private fun row(
        epochMs: Long,
        latitude: Double,
        speed: Double,
        current: Double,
        totalDistance: Long,
        mode: String,
    ): String {
        return RideLogCsv.dataLine(
            timeUnixMs = epochMs,
            latitude = latitude.toString(),
            longitude = "-77.6",
            gpsSpeed = "0",
            gpsAltitude = "100",
            gpsHeading = "0",
            gpsDistanceMeters = 0.0,
            speed = speed,
            voltage = 80.0,
            phaseCurrent = 0.0,
            current = current,
            power = 80.0,
            torque = 0.0,
            pwm = 10.0,
            batteryLevel = 90,
            distance = 0,
            totalDistance = totalDistance,
            systemTemp = 20,
            temp2 = 0,
            tilt = 0.0,
            roll = 0.0,
            mode = mode,
            alert = "",
        )
    }
}