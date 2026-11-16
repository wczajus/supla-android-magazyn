/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import org.junit.Assert.*
import org.junit.Test
import org.supla.android.R
import java.time.LocalDate
import java.time.ZoneId

class ReserveCardStatusTest {
  private val start = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
  private val now = start + ReserveHourly.HOUR_MS

  private fun reserve(imported: Long = 20, exported: Long = 100, currentEnabled: Boolean = true): EnergyReserve {
    val initial = ReserveFlowReading(start, listOf(0, 0, 0), listOf(0, 0, 0))
    return EnergyReserve(
      1, 42, 100, "2026-05-01", "2027-04-30", "Europe/Warsaw", ReserveReading(start, 0), false,
      hourlyEnabled = true,
      currentReserveEnabled = currentEnabled,
      hourlyProgress = ReserveHourlyProgress(initial, initial.copy(timestamp = now), importedRaw = imported, exportedRaw = exported)
    )
  }

  private fun states(reserve: EnergyReserve, balance: ReserveBalance) = listOf(
    ReserveCardStatuses.previous(balance),
    ReserveCardStatuses.current(balance),
    ReserveCardStatuses.total(reserve, balance)
  )

  @Test fun `available pools have no warning`() {
    val value = reserve()
    states(value, ReserveCalculator.calculate(value, now)).forEach {
      assertTrue(it.hasBalance)
      assertNull(it.message)
    }
  }

  @Test fun `exhausting previous pool does not mark current pool or total as exhausted`() {
    val value = reserve(imported = 120)
    val balance = ReserveCalculator.calculate(value, now)
    assertEquals(ReserveStatus.EXHAUSTED, balance.status)
    assertEquals(R.string.reserve_exhausted, ReserveCardStatuses.previous(balance).message)
    assertNull(ReserveCardStatuses.current(balance).message)
    assertNull(ReserveCardStatuses.total(value, balance).message)
  }

  @Test fun `all cards show exhaustion only after both pools have been used`() {
    val value = reserve(imported = 180)
    val balance = ReserveCalculator.calculate(value, now)
    states(value, balance).forEach {
      assertTrue(it.hasBalance)
      assertEquals(R.string.reserve_exhausted, it.message)
    }
    val refilled = reserve(imported = 180, exported = 200)
    val updated = ReserveCalculator.calculate(refilled, now)
    assertEquals(R.string.reserve_exhausted, ReserveCardStatuses.previous(updated).message)
    assertNull(ReserveCardStatuses.current(updated).message)
    assertNull(ReserveCardStatuses.total(refilled, updated).message)
  }

  @Test fun `no earned current energy is different from used up energy`() {
    val value = reserve(exported = 0)
    val balance = ReserveCalculator.calculate(value, now)
    assertEquals(R.string.reserve_not_accumulated, ReserveCardStatuses.current(balance).message)
    assertNull(ReserveCardStatuses.total(value, balance).message)
    val exhausted = reserve(imported = 100, exported = 0)
    val updated = ReserveCalculator.calculate(exhausted, now)
    assertEquals(R.string.reserve_not_accumulated, ReserveCardStatuses.current(updated).message)
    assertEquals(R.string.reserve_exhausted, ReserveCardStatuses.total(exhausted, updated).message)
  }

  @Test fun `total ignores disabled current pool including stale values`() {
    val value = reserve(imported = 100, currentEnabled = false)
    val balance = ReserveCalculator.calculate(value, now).copy(currentCreditedRaw = 80, currentRemainingRaw = 80)
    assertEquals(R.string.reserve_exhausted, ReserveCardStatuses.total(value, balance).message)
  }

  @Test fun `blocking conditions have the same specific message on all cards`() {
    val value = reserve()
    val messages = mapOf(
      ReserveStatus.NOT_STARTED to R.string.reserve_not_started,
      ReserveStatus.NO_READING to R.string.reserve_no_reading,
      ReserveStatus.COUNTER_RESET to R.string.reserve_reset,
      ReserveStatus.INVALID_READING to R.string.reserve_invalid
    )
    messages.forEach { (status, message) ->
      val balance = ReserveBalance(status, remainingRaw = 50, currentCreditedRaw = 80, currentRemainingRaw = 80)
      states(value, balance).forEach {
        assertFalse(it.hasBalance)
        assertEquals(message, it.message)
      }
    }
  }

  @Test fun `expiry of invoice does not imply expiry of earned current energy`() {
    val value = reserve()
    val balance = ReserveCalculator.calculate(value, value.expiresAt)
    assertEquals(80L, balance.currentRemainingRaw)
    assertEquals(R.string.reserve_expired, ReserveCardStatuses.previous(balance).message)
    assertEquals(R.string.reserve_period_ended, ReserveCardStatuses.current(balance).message)
    assertEquals(R.string.reserve_period_ended, ReserveCardStatuses.total(value, balance).message)
    assertTrue(ReserveCardStatuses.total(value, balance).hasBalance)
    assertEquals(R.string.reserve_expired, ReserveCardStatuses.total(value.copy(currentReserveEnabled = false), balance).message)
  }

  @Test fun `positive energy below display precision is not exhausted`() {
    val value = reserve(imported = 99, exported = 1)
    val balance = ReserveCalculator.calculate(value, now)
    assertEquals(1L, balance.remainingRaw)
    assertNull(ReserveCardStatuses.previous(balance).message)
    assertNull(ReserveCardStatuses.total(value, balance).message)
  }
}
