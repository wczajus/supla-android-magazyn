/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.core.storage

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.supla.android.data.model.settings.eletricitymeter.ElectricityMeterMeasurementType
import org.supla.android.features.details.electricitymeterdetail.energyreserve.EnergyReserve
import org.supla.android.features.details.electricitymeterdetail.energyreserve.EnergyReserveStore
import org.supla.android.features.details.electricitymeterdetail.energyreserve.ReserveReading
import java.time.LocalDate
import java.time.ZoneId

class UserStateHolderReserveVisibilityTest {
  private val files = mutableMapOf<String, PreferenceFile>()
  private val context = mockk<Context> {
    every { getSharedPreferences(any(), any()) } answers {
      files.getOrPut(firstArg()) { PreferenceFile() }.preferences
    }
  }

  @Test
  fun `visibility defaults to true and is retained separately for each meter and profile`() {
    val holder = UserStateHolder(context)
    val initial = holder.getElectricityMeterSettings(7, 42).copy(metricOnList = ElectricityMeterMeasurementType.VOLTAGE)
    assertTrue(initial.showReserve)

    holder.setElectricityMeterSettings(initial.copy(showReserve = false), 7, 42)
    val reopened = UserStateHolder(context)

    assertFalse(reopened.getElectricityMeterSettings(7, 42).showReserve)
    assertEquals(ElectricityMeterMeasurementType.VOLTAGE, reopened.getElectricityMeterSettings(7, 42).metricOnList)
    assertTrue(reopened.getElectricityMeterSettings(8, 42).showReserve)
    assertTrue(reopened.getElectricityMeterSettings(7, 43).showReserve)
  }

  @Test(timeout = 5000)
  fun `navigation receives visibility changes but ignores other meter and metric changes`() = runBlocking {
    val holder = UserStateHolder(context)
    val changes = Channel<Boolean>(Channel.UNLIMITED)
    val observer = launch { holder.observeEnergyReserveVisibility(7, 42).collect { changes.send(it) } }
    try {
      assertTrue(changes.receive())
      holder.setElectricityMeterSettings(holder.getElectricityMeterSettings(7, 42).copy(showReserve = false), 7, 42)
      assertFalse(changes.receive())

      holder.setElectricityMeterSettings(holder.getElectricityMeterSettings(8, 42).copy(showReserve = false), 8, 42)
      holder.setElectricityMeterSettings(
        holder.getElectricityMeterSettings(7, 42).copy(metricOnList = ElectricityMeterMeasurementType.VOLTAGE),
        7,
        42
      )
      yield()
      assertTrue(changes.tryReceive().isFailure)

      holder.setElectricityMeterSettings(holder.getElectricityMeterSettings(7, 42).copy(showReserve = true), 7, 42)
      assertTrue(changes.receive())
    } finally {
      observer.cancelAndJoin()
      changes.close()
    }
    assertTrue(files.getValue("user_state_preferences").listeners.isEmpty())
  }

  @Test
  fun `hiding and showing a reserve preserves the exact saved reserve and readings`() {
    val start = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
    val reserve = EnergyReserve(
      7, 42, 200 * 100_000L, "2026-05-01", "2027-04-30", "Europe/Warsaw",
      ReserveReading(start, 1000 * 100_000L), true,
      lastReading = ReserveReading(start + 3_600_000, 1050 * 100_000L)
    )
    val store = EnergyReserveStore(context)
    store.write(reserve)
    val original = files.getValue("energy_reserve").values.toMap()
    val holder = UserStateHolder(context)

    for (show in listOf(false, true, false, true)) {
      holder.setElectricityMeterSettings(holder.getElectricityMeterSettings(7, 42).copy(showReserve = show), 7, 42)
      assertEquals(original, files.getValue("energy_reserve").values)
      assertEquals(reserve, EnergyReserveStore(context).read(7, 42))
    }
  }

  private class PreferenceFile {
    val values = mutableMapOf<String, String>()
    val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val pending = mutableMapOf<String, String?>()
    private val editor = mockk<SharedPreferences.Editor>()
    val preferences = mockk<SharedPreferences>()

    init {
      every { preferences.getString(any(), any()) } answers { values[firstArg<String>()] ?: secondArg<String?>() }
      every { preferences.edit() } returns editor
      every { editor.putString(any(), any()) } answers {
        pending[firstArg()] = secondArg()
        editor
      }
      every { editor.apply() } answers { persist() }
      every { editor.commit() } answers {
        persist()
        true
      }
      every { preferences.registerOnSharedPreferenceChangeListener(any()) } answers
        {
          listeners.add(firstArg<SharedPreferences.OnSharedPreferenceChangeListener>())
          Unit
        }
      every { preferences.unregisterOnSharedPreferenceChangeListener(any()) } answers
        {
          listeners.remove(firstArg<SharedPreferences.OnSharedPreferenceChangeListener>())
          Unit
        }
    }

    private fun persist() {
      val changed = pending.keys.toList()
      pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
      pending.clear()
      changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(preferences, key) } }
    }
  }
}
