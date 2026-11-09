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

  @Test fun `long gap uses uniform allocation with explicit metadata when no profile exists`() {
    val rows = listOf(reading(start, 0, 0), reading(start + 2 * hour, 50, 100))
    val result = ReserveHourly.advance(null, rows, start, start + 2 * hour)
    assertEquals(50L, result.exportedRaw)
    assertEquals(50L, result.boundary.importedRaw)
    assertEquals(100L, result.boundary.exportedRaw)
    assertEquals(ReserveGapMethod.UNIFORM, result.gaps.single().method)
    assertEquals(2 * hour, result.gaps.single().until - result.gaps.single().from)
  }

  @Test fun `history starting after midnight explicitly marks the missing beginning`() {
    val rows = listOf(reading(start + 300_000, 10, 0), reading(start + hour, 20, 0))
    val result = ReserveHourly.advance(null, rows, start, start + hour)
    assertEquals(start + 300_000, result.missingStartUntil)
    assertEquals(10L, result.importedRaw)
    val balance = ReserveCalculator.calculate(reserve(result), start + hour)
    assertTrue(balance.partial)
    assertTrue(balance.approximate)
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

  private fun profiles(days: Int = 3): List<ReserveProfileHour> = (1..days).flatMap { day ->
    val midnight = start - day * 24 * hour
    listOf(
      ReserveProfileHour(midnight, listOf(0, 0, 0), listOf(10, 0, 0)),
      ReserveProfileHour(midnight + hour, listOf(10, 0, 0), listOf(0, 0, 0))
    )
  }

  @Test fun `nearby matching hour profile separates import and export and preserves their totals`() {
    val first = reading(start, 0, 0)
    val existing = ReserveHourlyProgress(first, first, profileHours = profiles())
    val result = ReserveHourly.advance(existing, listOf(first, reading(start + 2 * hour, 10, 10)), start, start + 2 * hour)
    assertEquals(10L, result.importedRaw)
    assertEquals(10L, result.exportedRaw)
    assertEquals(10L, result.boundary.importedRaw)
    assertEquals(10L, result.boundary.exportedRaw)
    assertEquals(ReserveGapMethod.PROFILE, result.gaps.single().method)
    assertEquals(8L, ReserveHourly.credited(result.exportedRaw, 80))
  }

  @Test fun `fewer than three profile days falls back to uniform allocation`() {
    val first = reading(start, 0, 0)
    val existing = ReserveHourlyProgress(first, first, profileHours = profiles(2))
    val result = ReserveHourly.advance(existing, listOf(first, reading(start + 2 * hour, 10, 10)), start, start + 2 * hour)
    assertEquals(ReserveGapMethod.UNIFORM, result.gaps.single().method)
    assertEquals(0L, result.importedRaw)
    assertEquals(10L, result.boundary.importedRaw)
    assertEquals(10L, result.boundary.exportedRaw)
  }

  @Test fun `zero reference flow falls back only for that counter and records a mixed method`() {
    val first = reading(start, 0, 0)
    val reference = profiles().map { it.copy(imports = listOf(0, 0, 0)) }
    val existing = ReserveHourlyProgress(first, first, profileHours = reference)
    val result = ReserveHourly.advance(existing, listOf(first, reading(start + 2 * hour, 10, 10)), start, start + 2 * hour)
    assertEquals(ReserveGapMethod.MIXED, result.gaps.single().method)
    assertEquals(10L, result.boundary.importedRaw)
    assertEquals(10L, result.boundary.exportedRaw)
  }

  @Test fun `allocation preserves each phase and integer rounding for a multi hour gap`() {
    val first = ReserveFlowReading(start, listOf(1, 2, 3), listOf(4, 5, 6))
    val last = ReserveFlowReading(start + 7 * hour, listOf(12, 25, 38), listOf(47, 58, 69))
    val result = ReserveHourly.advance(null, listOf(first, last), start, last.timestamp)
    assertEquals(last.imports, result.boundary.imports)
    assertEquals(last.exports, result.boundary.exports)
    assertEquals(last.importedRaw - first.importedRaw, result.gaps.single().importedRaw)
    assertEquals(last.exportedRaw - first.exportedRaw, result.gaps.single().exportedRaw)
  }

  @Test fun `resuming after an estimated boundary does not duplicate a gap or learn from its tail`() {
    val first = reading(start, 0, 0)
    val middle = reading(start + 2 * hour + hour / 2, 150, 0)
    val initial = ReserveHourly.advance(null, listOf(first, middle), start, start + 2 * hour)
    assertTrue(initial.anchorEstimated)
    val last = reading(start + 3 * hour, 180, 0)
    val resumed = ReserveHourly.advance(initial, listOf(initial.boundary, middle, last), start, last.timestamp)
    assertEquals(180L, resumed.importedRaw)
    assertEquals(1, resumed.gaps.size)
    assertTrue(resumed.profileHours.isEmpty())
  }

  @Test fun `reconstructed multi day intervals do not teach a synthetic profile`() {
    val rows = (0..4).map { reading(start + it * 24 * hour, it * 100L, it * 150L) }
    val result = ReserveHourly.advance(null, rows, start, rows.last().timestamp)
    assertTrue(result.profileHours.isEmpty())
    assertEquals(4, result.gaps.size)
    assertEquals(400L, result.boundary.importedRaw)
    assertEquals(600L, result.boundary.exportedRaw)
  }

  @Test fun `a delayed measurement can close a past period across a multi day gap`() {
    val rows = listOf(reading(start - 2 * hour, 0, 0), reading(start + 3 * hour, 50, 0))
    val result = ReserveHourly.advance(null, rows, start, start + hour)
    assertEquals(start + hour, result.boundary.timestamp)
    assertEquals(10L, result.importedRaw)
    assertEquals(1, result.gaps.size)
  }

  @Test fun `missing tail marks partial data and does not fill the period with zeros`() {
    val rows = listOf(reading(start, 0, 0), reading(start + hour, 10, 0))
    val result = ReserveHourly.advance(null, rows, start, start + 4 * hour)
    val balance = ReserveCalculator.calculate(reserve(result), start + 4 * hour)
    assertTrue(balance.partial)
    assertTrue(balance.approximate)
    assertEquals(start + hour, result.boundary.timestamp)
  }

  @Test fun `counter reset remains blocking rather than being interpolated away`() {
    val rows = listOf(reading(start, 100, 100), reading(start + 8 * hour, 90, 200))
    assertThrows(ReserveHourlyReset::class.java) { ReserveHourly.advance(null, rows, start, start + 8 * hour) }
  }

  @Test fun `invoice amount is never discounted even when the new pool uses a different rate`() {
    val first = reading(start, 0, 0)
    val progress = ReserveHourlyProgress(first, first.copy(timestamp = start + hour), importedRaw = 20, exportedRaw = 100)
    for (rate in listOf(70, 80)) {
      val balance = ReserveCalculator.calculate(reserve(progress, allowance = 100, retained = rate), start + hour)
      assertEquals(80L, balance.remainingRaw)
      assertEquals(rate.toLong(), balance.currentRemainingRaw)
    }
  }

  @Test fun `profiles are learned from real nearby days available later in the same download`() {
    val rows = mutableListOf(reading(start, 0, 0), reading(start + 2 * hour, 10, 10))
    var imported = 10L
    var exported = 10L
    for (h in 3..74) {
      when ((h - 1) % 24) {
        0 -> exported += 10
        1 -> imported += 10
      }
      rows += reading(start + h * hour, imported, exported)
    }
    val result = ReserveHourly.advance(null, rows, start, rows.last().timestamp)
    assertEquals(ReserveGapMethod.PROFILE, result.gaps.single().method)
    assertEquals(40L, result.importedRaw)
    assertEquals(40L, result.exportedRaw)
  }
}
