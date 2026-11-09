/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.supla.android.data.source.remote.rest.channel.ElectricityMeasurement
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class EnergyReserveTest {
  private val zone = ZoneId.of("Europe/Warsaw")
  private val start = LocalDate.of(2026, 5, 1).atStartOfDay(zone).toInstant().toEpochMilli()
  private val now = start + 86_400_000

  private fun reserve(importedKwh: Long = 10_150) = EnergyReserve(
    profileId = 1,
    channelId = 42,
    initialRaw = 200 * 100_000L,
    startDate = "2026-05-01",
    validUntil = "2027-04-30",
    zoneId = zone.id,
    baseline = ReserveReading(start, 10_000 * 100_000L),
    manualBaseline = true,
    lastReading = ReserveReading(now, importedKwh * 100_000L)
  )

  @Test fun `subtracts only cumulative import since baseline`() {
    val balance = ReserveCalculator.calculate(reserve(), now)
    assertEquals(ReserveStatus.ACTIVE, balance.status)
    assertEquals(50 * 100_000L, balance.remainingRaw)
    assertEquals(150 * 100_000L, balance.usedRaw)
  }

  @Test fun `exhausted pool stays at zero and reports excess`() {
    val balance = ReserveCalculator.calculate(reserve(10_250), now)
    assertEquals(ReserveStatus.EXHAUSTED, balance.status)
    assertEquals(0L, balance.remainingRaw)
    assertEquals(50 * 100_000L, balance.excessRaw)
    assertEquals(200 * 100_000L, balance.usedRaw)
  }

  @Test fun `exact exhaustion has no excess`() {
    val balance = ReserveCalculator.calculate(reserve(10_200), now)
    assertEquals(ReserveStatus.EXHAUSTED, balance.status)
    assertEquals(0L, balance.excessRaw)
  }

  @Test fun `expired energy is not available`() {
    val value = reserve()
    val balance = ReserveCalculator.calculate(value, value.expiresAt)
    assertEquals(ReserveStatus.EXPIRED, balance.status)
    assertEquals(0L, balance.remainingRaw)
    assertEquals(50 * 100_000L, balance.expiredRaw)
  }

  @Test fun `rejects consumption readings after deadline`() {
    val value = reserve()
    val balance = ReserveCalculator.calculate(
      value.copy(lastReading = value.lastReading!!.copy(timestamp = value.expiresAt + 1)),
      value.expiresAt + 2
    )
    assertEquals(ReserveStatus.INVALID_READING, balance.status)
  }

  @Test fun `accepts a closing reading exactly at deadline`() {
    val value = reserve()
    assertEquals(
      ReserveStatus.EXPIRED,
      ReserveCalculator.calculate(
        value.copy(lastReading = value.lastReading!!.copy(timestamp = value.expiresAt)),
        value.expiresAt + 1
      ).status
    )
  }

  @Test fun `does not consume pool before start`() {
    assertEquals(ReserveStatus.NOT_STARTED, ReserveCalculator.calculate(reserve(), start - 1).status)
  }

  @Test fun `missing measurement is not interpreted as zero`() {
    assertEquals(ReserveStatus.NO_READING, ReserveCalculator.calculate(reserve().copy(lastReading = null), now).status)
  }

  @Test fun `rejects a future measurement`() {
    assertEquals(ReserveStatus.INVALID_READING, ReserveCalculator.calculate(reserve(), start).status)
  }

  @Test fun `detects a cumulative counter decrease`() {
    assertEquals(ReserveStatus.COUNTER_RESET, ReserveCalculator.calculate(reserve(9_999), now).status)
  }

  @Test fun `detects per phase reset even when total increases`() {
    val old = ReserveReading(start, 300, listOf(100, 100, 100))
    val current = ReserveReading(now, 400, listOf(50, 175, 175))
    assertTrue(current.decreasedFrom(old))
  }

  @Test fun `reset remains latched after counter catches up`() {
    assertEquals(ReserveStatus.COUNTER_RESET, ReserveCalculator.calculate(reserve().copy(resetDetected = true), now).status)
  }

  @Test fun `supports Polish decimal comma without floating point rounding`() {
    assertEquals(12345678L, ReserveEnergy.parseKwh("123,45678"))
    assertEquals("123.45678", ReserveEnergy.kwh(12345678).toPlainString())
  }

  @Test fun `rejects negative non finite and excessively precise input`() {
    listOf("-1", "NaN", "Infinity", "1e6", "1.123456", "1,2,3", "99999999999999999999").forEach {
      assertNull(it, ReserveEnergy.parseKwh(it))
    }
  }

  @Test fun `retains precision for large cumulative counters`() {
    val value = reserve().copy(baseline = ReserveReading(start, 9_000_000_000_000L), lastReading = ReserveReading(now, 9_000_000_000_001L))
    assertEquals(1L, ReserveCalculator.calculate(value, now).consumedRaw)
  }

  @Test fun `dates respect daylight saving rather than fixed 24 hour arithmetic`() {
    val spring = reserve().copy(startDate = "2026-03-29", validUntil = "2026-03-29", baseline = ReserveReading(0, 0))
    assertEquals(23 * 60 * 60 * 1000L, spring.expiresAt - spring.startsAt)
    val autumn = spring.copy(startDate = "2026-10-25", validUntil = "2026-10-25")
    assertEquals(25 * 60 * 60 * 1000L, autumn.expiresAt - autumn.startsAt)
  }

  private fun measurement() = ElectricityMeasurement(
    date = Date(now),
    phase1Fae = 100, phase1Rae = 999999, phase1Fre = null, phase1Rre = null,
    phase2Fae = 200, phase2Rae = 999999, phase2Fre = null, phase2Rre = null,
    phase3Fae = 300, phase3Rae = 999999, phase3Fre = null, phase3Rre = null,
    faeBalanced = 99999, raeBalanced = 999999
  )

  @Test fun `API adapter uses only raw import and excludes export balanced and reactive values`() {
    val reading = measurement().toReserveReading()
    assertEquals(600L, reading.importedRaw)
    assertEquals(listOf(100L, 200L, 300L), reading.phases)
  }

  @Test fun `missing phase import is not treated as zero`() {
    assertThrows(ReserveProblem::class.java) { measurement().copy(phase2Fae = null).toReserveReading() }
  }

  @Test fun `zero import on a phase is a valid measurement`() {
    assertEquals(400L, measurement().copy(phase2Fae = 0).toReserveReading().importedRaw)
  }

  @Test fun `local snapshot roundtrip retains identity counters and dates`() {
    val value = reserve().copy(resetDetected = true)
    assertEquals(value, Json.decodeFromString<EnergyReserve>(Json.encodeToString(value)))
  }

  @Test fun `later selected baseline is valid and consumption starts at that reading`() {
    val value = reserve().copy(baseline = ReserveReading(start + 600_000, 10_000 * 100_000L), manualBaseline = false)
    assertEquals(ReserveStatus.ACTIVE, ReserveCalculator.calculate(value, now).status)
    assertEquals(50 * 100_000L, ReserveCalculator.calculate(value, now).remainingRaw)
  }

  @Test fun `a cached measurement before a later baseline is not mistaken for a counter reset`() {
    val value = reserve().copy(
      baseline = ReserveReading(start + 600_000, 10_000 * 100_000L),
      lastReading = ReserveReading(
        start + 300_000,
        9_999 * 100_000L
      )
    )
    assertEquals(ReserveStatus.INVALID_READING, ReserveCalculator.calculate(value, now).status)
  }
}
