/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReserveHourlyTest {
  private val hour = ReserveHourly.HOUR_MS
  private val start = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
  private fun reading(time: Long, imported: Long, exported: Long) =
    ReserveFlowReading(time, listOf(imported, 0, 0), listOf(exported, 0, 0))

  private fun reserve(progress: ReserveHourlyProgress, allowance: Long = 100, retained: Int = 80) = EnergyReserve(
    7, 42, allowance, "2026-05-01", "2027-04-30", "Europe/Warsaw", ReserveReading(start, 0), false,
    hourlyEnabled = true, retainedPercent = retained, hourlyProgress = progress
  )

  @Test fun `balances import and export before applying either discount`() {
    val progress = ReserveHourly.advance(null, listOf(reading(start, 0, 0), reading(start + hour, 40, 100)), start, start + hour)
    assertEquals(0L, progress.importedRaw)
    assertEquals(60L, progress.exportedRaw)
    assertEquals(48L, ReserveHourly.credited(progress.exportedRaw, 80))
    assertEquals(42L, ReserveHourly.credited(progress.exportedRaw, 70))
  }

  @Test fun `separate hours are not netted across the entire period`() {
    val rows = listOf(reading(start, 0, 0), reading(start + hour, 0, 100), reading(start + 2 * hour, 100, 100))
    val progress = ReserveHourly.advance(null, rows, start, start + 2 * hour)
    assertEquals(100L, progress.importedRaw)
    assertEquals(100L, progress.exportedRaw)
  }

  @Test fun `current allowance is untouched while the previous allowance remains`() {
    val first = reading(start, 0, 0)
    val progress = ReserveHourlyProgress(first, first.copy(timestamp = start + hour), importedRaw = 60, exportedRaw = 100)
    val balance = ReserveCalculator.calculate(reserve(progress), start + hour)
    assertEquals(40L, balance.remainingRaw)
    assertEquals(80L, balance.currentRemainingRaw)
    assertEquals(0L, balance.currentUsedRaw)
  }

  @Test fun `only the part exceeding the previous allowance is taken from the current allowance`() {
    val first = reading(start, 0, 0)
    val progress = ReserveHourlyProgress(first, first.copy(timestamp = start + hour), importedRaw = 120, exportedRaw = 100)
    val balance = ReserveCalculator.calculate(reserve(progress), start + hour)
    assertEquals(100L, balance.usedRaw)
    assertEquals(0L, balance.remainingRaw)
    assertEquals(20L, balance.currentUsedRaw)
    assertEquals(60L, balance.currentRemainingRaw)
    assertEquals(0L, balance.excessRaw)
  }

  @Test fun `uncovered import starts only after exhausting both allowances`() {
    val first = reading(start, 0, 0)
    val progress = ReserveHourlyProgress(first, first.copy(timestamp = start + hour), importedRaw = 250, exportedRaw = 100)
    val balance = ReserveCalculator.calculate(reserve(progress), start + hour)
    assertEquals(80L, balance.currentUsedRaw)
    assertEquals(0L, balance.currentRemainingRaw)
    assertEquals(70L, balance.excessRaw)
  }

  @Test fun `new allowance does not expire together with the invoice allowance`() {
    val first = reading(start, 0, 0)
    val progress = ReserveHourlyProgress(first, first.copy(timestamp = start + hour), importedRaw = 20, exportedRaw = 100)
    val value = reserve(progress)
    val balance = ReserveCalculator.calculate(value, value.expiresAt)
    assertEquals(ReserveStatus.EXPIRED, balance.status)
    assertEquals(80L, balance.expiredRaw)
    assertEquals(80L, balance.currentRemainingRaw)
  }

  @Test fun `unfinished hour does not enter the settlement`() {
    val rows = listOf(reading(start, 0, 0), reading(start + hour / 2, 20, 50))
    val progress = ReserveHourly.advance(null, rows, start, start + hour / 2)
    assertEquals(start, progress.boundary.timestamp)
    assertEquals(0L, progress.exportedRaw)
  }

  @Test fun `interpolates midnight and hour boundary using all phase counters`() {
    val rows = listOf(
      reading(start - 300_000, 0, 0),
      reading(start + 300_000, 10, 0),
      reading(start + hour - 300_000, 60, 0),
      reading(start + hour + 300_000, 70, 0)
    )
    val progress = ReserveHourly.advance(null, rows, start, start + hour)
    assertEquals(5L, progress.initial.importedRaw)
    assertEquals(65L, progress.boundary.importedRaw)
    assertEquals(60L, progress.importedRaw)
    assertEquals(2, progress.interpolatedBoundaries)
  }

  @Test fun `resuming from a checkpoint does not count earlier hours again`() {
    val rows = (0..3).map { reading(start + it * hour, it * 20L, it * 50L) }
    val first = ReserveHourly.advance(null, rows.take(2), start, start + hour)
    val resumed = ReserveHourly.advance(first, listOf(first.boundary) + rows.drop(2), start, start + 3 * hour)
    assertEquals(ReserveHourly.advance(null, rows, start, start + 3 * hour), resumed)
  }

  @Test fun `stops at the period deadline even when a later measurement is available`() {
    val rows = (0..3).map { reading(start + it * hour, 0, it * 100L) }
    val progress = ReserveHourly.advance(null, rows, start, start + 2 * hour)
    assertEquals(start + 2 * hour, progress.boundary.timestamp)
    assertEquals(200L, progress.exportedRaw)
  }

  @Test fun `does not invent an hourly distribution across a long gap`() {
    val rows = listOf(reading(start, 0, 0), reading(start + 2 * hour, 50, 100))
    assertThrows(ReserveHourlyGap::class.java) { ReserveHourly.advance(null, rows, start, start + 2 * hour) }
  }

  @Test fun `history starting after midnight cannot silently skip the first part of the period`() {
    val rows = listOf(reading(start + 300_000, 10, 0), reading(start + hour, 20, 0))
    assertThrows(ReserveHourlyGap::class.java) { ReserveHourly.advance(null, rows, start, start + hour) }
  }

  @Test fun `detects an export counter decrease even if import keeps growing`() {
    val rows = listOf(reading(start, 0, 100), reading(start + hour, 200, 90))
    assertThrows(ReserveHourlyReset::class.java) { ReserveHourly.advance(null, rows, start, start + hour) }
  }

  @Test fun `detects a phase decrease hidden by another growing phase`() {
    val first = ReserveFlowReading(start, listOf(100, 100, 100), listOf(0, 0, 0))
    val next = ReserveFlowReading(start + hour, listOf(90, 200, 200), listOf(0, 0, 0))
    assertThrows(ReserveHourlyReset::class.java) { ReserveHourly.advance(null, listOf(first, next), start, start + hour) }
  }

  @Test fun `autumn repeated local hour remains two distinct settlement hours`() {
    val autumn = LocalDate.of(2026, 10, 25).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
    val rows = (0..25).map { reading(autumn + it * hour, it * 10L, 0) }
    val progress = ReserveHourly.advance(null, rows, autumn, autumn + 25 * hour)
    assertEquals(250L, progress.importedRaw)
    assertEquals(autumn + 25 * hour, progress.boundary.timestamp)
  }

  @Test fun `discount calculation avoids overflow and per hour rounding`() {
    assertEquals(7_378_697_629_483_820_645L, ReserveHourly.credited(Long.MAX_VALUE, 80))
    assertEquals(1L, ReserveHourly.credited(2, 80))
    assertThrows(IllegalArgumentException::class.java) { ReserveHourly.credited(100, 90) }
  }

  @Test fun `changing coefficient reuses raw hourly totals`() {
    val first = reading(start, 0, 0)
    val progress = ReserveHourlyProgress(first, first.copy(timestamp = start + hour), exportedRaw = 100)
    assertEquals(80L, ReserveCalculator.calculate(reserve(progress, retained = 80), start + hour).currentRemainingRaw)
    assertEquals(70L, ReserveCalculator.calculate(reserve(progress, retained = 70), start + hour).currentRemainingRaw)
  }

  @Test fun `legacy JSON remains readable without enabling a new calculation silently`() {
    val json = """
      {"profileId":7,"channelId":42,"initialRaw":100,"startDate":"2026-05-01","validUntil":"2027-04-30",
      "zoneId":"Europe/Warsaw","baseline":{"timestamp":$start,"importedRaw":0},"manualBaseline":true}
    """.trimIndent()
    val value = Json.decodeFromString<EnergyReserve>(json)
    assertFalse(value.hourlyEnabled)
    assertNull(value.hourlyProgress)
    assertEquals(80, value.retainedPercent)
  }
}
