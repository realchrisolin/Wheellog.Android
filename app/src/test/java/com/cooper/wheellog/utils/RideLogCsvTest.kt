package com.cooper.wheellog.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class RideLogCsvTest {
    @Test
    fun `legacy row parses the same cells as a comma split`() {
        val line = File("src/test/resources/log_test1.csv").readLines()[1]

        val parsed = RideLogCsv.parseLine(line)

        assertThat(parsed).isEqualTo(line.split(","))
    }

    @Test
    fun `quoted mode and alert keep the other columns in place`() {
        val line = RideLogCsv.dataLine(
            timeUnixMs = 3_600_000L,
            latitude = "43.1",
            longitude = "-77.6",
            gpsSpeed = "1.5",
            gpsAltitude = "180",
            gpsHeading = "90",
            gpsDistanceMeters = 12.0,
            speed = 10.0,
            voltage = 84.2,
            phaseCurrent = 1.25,
            current = 2.5,
            power = 210.5,
            torque = 0.0,
            pwm = 40.0,
            batteryLevel = 80,
            distance = 15,
            totalDistance = 1000L,
            systemTemp = 32,
            temp2 = 28,
            tilt = 0.4,
            roll = -1.2,
            mode = "Drive, night",
            alert = "say \"hi\"",
        )

        val cells = RideLogCsv.parseLine(line)

        assertThat(cells).hasSize(RideLogCsv.HEADER.split(",").size)
        assertThat(cells[0]).isEqualTo("3600000")
        assertThat(cells[1]).isEqualTo("43.1")
        assertThat(cells[6]).isEqualTo("12")
        assertThat(cells[7]).isEqualTo("10.00")
        assertThat(cells[21]).isEqualTo("Drive, night")
        assertThat(cells[22]).isEqualTo("say \"hi\"")
    }

    @Test
    fun `quoteField keeps a row on one line`() {
        assertThat(RideLogCsv.quoteField("a\r\nb\"c")).isEqualTo("\"a b\"\"c\"")
        assertThat(RideLogCsv.parseLine(RideLogCsv.quoteField("a\nb"))).containsExactly("a b")
        assertThat(RideLogCsv.quoteField(null)).isEqualTo("\"\"")
    }

    @Test
    fun `offset round trip`() {
        assertThat(RideLogCsv.formatOffset(0)).isEqualTo("Z")
        assertThat(RideLogCsv.formatOffset(-5 * 3_600_000)).isEqualTo("-05:00")
        assertThat(RideLogCsv.formatOffset(5 * 3_600_000 + 30 * 60_000)).isEqualTo("+05:30")
        assertThat(RideLogCsv.offsetMillisOf(RideLogCsv.preamble(-5 * 3_600_000)))
            .isEqualTo(-5 * 3_600_000)
        assertThat(RideLogCsv.offsetMillisOf(RideLogCsv.preamble(0))).isEqualTo(0)
        assertThat(RideLogCsv.offsetMillisOf("# wheellog_csv=2 timezone_offset=nope")).isEqualTo(0)
    }

    @Test
    fun `civil time applies the file offset`() {
        val utc = RideLogCsv.civilTime(3_600_000L, 0)
        val west = RideLogCsv.civilTime(3_600_000L, -5 * 3_600_000)

        assertThat(utc.date).isEqualTo("1970-01-01")
        assertThat(utc.time).isEqualTo("01:00:00.000")
        assertThat(west.date).isEqualTo("1969-12-31")
        assertThat(west.time).isEqualTo("20:00:00.000")
    }

    @Test
    fun `isCurrentLog accepts only the epoch header`() {
        val current = (RideLogCsv.preamble(0) + "\n" + RideLogCsv.HEADER + "\n").byteInputStream()
        val legacy = "date,time,latitude,longitude\n".byteInputStream()

        assertThat(RideLogCsv.isCurrentLog(current)).isTrue()
        assertThat(RideLogCsv.isCurrentLog(legacy)).isFalse()
    }
}
