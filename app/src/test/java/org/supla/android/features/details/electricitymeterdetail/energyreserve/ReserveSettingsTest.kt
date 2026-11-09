/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReserveSettingsTest {
  private val start = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
  private val end = start + 2 * ReserveHourly.HOUR_MS
  private val first = ReserveFlowReading(start, listOf(0, 0, 0), listOf(0, 0, 0))
  private val latest = ReserveFlowReading(end, listOf(150, 0, 0), listOf(100, 0, 0))

  private fun reserve(hourly: Boolean, current: Boolean) = EnergyReserve(
    7, 42, 100, "2026-05-01", "2027-04-30", "Europe/Warsaw", ReserveReading(start, 0), true,
    lastReading = ReserveReading(end, 150), hourlyEnabled = hourly, currentReserveEnabled = current,
    hourlyProgress = ReserveHourlyProgress(first, latest, importedRaw = 80, exportedRaw = 30),
    flowBaseline = first, flowLastReading = latest
  )

  @Test fun `one arithmetic reserve subtracts full import and never credits exports`() {
    val balance = ReserveCalculator.calculate(reserve(hourly = false, current = false), end)
    assertEquals(150L, balance.consumedRaw)
    assertEquals(0L, balance.remainingRaw)
    assertEquals(50L, balance.excessRaw)
    assertEquals(0L, balance.currentCreditedRaw)
  }

  @Test fun `one hourly reserve offsets same hour exports without creating a current pool`() {
    val balance = ReserveCalculator.calculate(reserve(hourly = true, current = false), end)
    assertEquals(80L, balance.consumedRaw)
    assertEquals(20L, balance.remainingRaw)
    assertEquals(0L, balance.currentCreditedRaw)
    assertEquals(0L, balance.currentUsedRaw)
    assertEquals(0L, balance.currentRemainingRaw)
  }

  @Test fun `two arithmetic reserves use full import and discounted full export`() {
    val balance = ReserveCalculator.calculate(reserve(hourly = false, current = true), end)
    assertEquals(150L, balance.consumedRaw)
    assertEquals(100L, balance.exportedRaw)
    assertEquals(80L, balance.currentCreditedRaw)
    assertEquals(50L, balance.currentUsedRaw)
    assertEquals(30L, balance.currentRemainingRaw)
    assertEquals(0L, balance.excessRaw)
  }

  @Test fun `two hourly reserves use balanced import and discounted balanced export`() {
    val balance = ReserveCalculator.calculate(reserve(hourly = true, current = true), end)
    assertEquals(80L, balance.consumedRaw)
    assertEquals(20L, balance.remainingRaw)
    assertEquals(24L, balance.currentCreditedRaw)
    assertEquals(0L, balance.currentUsedRaw)
    assertEquals(24L, balance.currentRemainingRaw)
  }

  @Test fun `disabled current reserve cannot cover import exceeding the invoice allowance`() {
    val value = reserve(hourly = true, current = false)
    val balance = ReserveCalculator.calculate(
      value.copy(hourlyProgress = value.hourlyProgress!!.copy(importedRaw = 150, exportedRaw = 100)),
      end
    )
    assertEquals(50L, balance.excessRaw)
    assertEquals(0L, balance.currentRemainingRaw)
  }

  @Test fun `all four settings combinations survive JSON round trip`() {
    for (hourly in listOf(false, true)) {
      for (current in listOf(false, true)) {
        val decoded = Json.decodeFromString<EnergyReserve>(Json.encodeToString(reserve(hourly, current)))
        assertEquals(hourly, decoded.hourlyEnabled)
        assertEquals(current, decoded.currentReserveEnabled)
      }
    }
  }

  @Test fun `old JSON preserves the former one or two reserve behavior`() {
    for (hourly in listOf(false, true)) {
      val json = """
        {"profileId":7,"channelId":42,"initialRaw":100,"startDate":"2026-05-01","validUntil":"2027-04-30",
        "zoneId":"Europe/Warsaw","baseline":{"timestamp":$start,"importedRaw":0},"manualBaseline":false,"hourlyEnabled":$hourly}
      """.trimIndent()
      val decoded = Json.decodeFromString<EnergyReserve>(json)
      assertEquals(hourly, decoded.hourlyEnabled)
      assertEquals(hourly, decoded.currentReserveEnabled)
    }
  }

  @Test fun `coefficient only applies to new exports in either two reserve method`() {
    for (hourly in listOf(false, true)) {
      val value = reserve(hourly, current = true)
      val a = ReserveCalculator.calculate(value.copy(retainedPercent = 80), end)
      val b = ReserveCalculator.calculate(value.copy(retainedPercent = 70), end)
      assertEquals(a.usedRaw, b.usedRaw)
      assertEquals(a.remainingRaw, b.remainingRaw)
      assertTrue(a.currentCreditedRaw > b.currentCreditedRaw)
    }
  }
}
