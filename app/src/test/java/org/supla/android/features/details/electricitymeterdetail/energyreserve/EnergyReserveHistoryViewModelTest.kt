/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.Assert.*
import org.junit.Test
import org.supla.android.R
import org.supla.android.core.infrastructure.DateProvider
import org.supla.android.core.networking.suplaclient.SuplaClientMessageHandlerWrapper
import org.supla.android.tools.SuplaThreading

class EnergyReserveHistoryViewModelTest {
  private val repository = mockk<EnergyReserveRepository>()
  private val dates = mockk<DateProvider>(relaxed = true)
  private val threading = mockk<SuplaThreading>(relaxed = true)
  private val messages = mockk<SuplaClientMessageHandlerWrapper>(relaxed = true)
  private val context = ReserveContext(7, 42, "MEW-01", null)
  private val reading = ReserveReading(1, 300, listOf(100, 100, 100))
  private val draft = ReserveDraft("200", "2026-05-31", "2027-05-30")

  private fun model(): EnergyReserveViewModel {
    every { threading.schedulers.io } returns Schedulers.trampoline()
    every { threading.schedulers.ui } returns Schedulers.trampoline()
    every { repository.loadCache(42) } returns context
    return EnergyReserveViewModel(repository, dates, messages, threading).also {
      it.load(42)
      it.edit()
      it.updateDraft(draft)
      it.updateDraft(draft.copy(historicalBaseline = reading))
    }
  }

  @Test fun `changing the period start clears the previously chosen reading`() {
    val model = model()
    model.updateDraft(model.getViewState().value.draft.copy(startDate = "2026-06-01"))
    val state = model.getViewState().value
    assertNull(state.draft.historicalBaseline)
    assertEquals("2026-05-31", state.historyFrom)
    assertEquals("2026-06-02", state.historyUntil)
  }

  @Test fun `changing only the allowance retains the selected historical reading`() {
    val model = model()
    model.updateDraft(model.getViewState().value.draft.copy(initialKwh = "300"))
    assertEquals(reading, model.getViewState().value.draft.historicalBaseline)
    verify(exactly = 0) { repository.save(any(), any()) }
  }

  @Test fun `empty allowance blocks saving even with a selected history reading`() {
    val model = model()
    model.updateDraft(model.getViewState().value.draft.copy(initialKwh = ""))
    assertFalse(model.getViewState().value.canSave)
    model.save()
    val state = model.getViewState().value
    assertTrue(state.editing)
    assertEquals(R.string.reserve_error_amount, state.error)
    verify(exactly = 0) { repository.save(any(), any()) }
  }

  @Test fun `valid allowance enables saving again after clearing and retyping in both modes`() {
    val model = model()
    for (manual in listOf(false, true)) {
      for (amount in listOf("", " ", "0", "-5", "200,000001", "abc")) {
        model.updateDraft(draft.copy(initialKwh = amount, manualBaseline = manual, baselineKwh = "100", historicalBaseline = reading))
        assertFalse(model.getViewState().value.canSave)
      }
      model.updateDraft(model.getViewState().value.draft.copy(initialKwh = "200,5"))
      val state = model.getViewState().value
      assertTrue(state.canSave)
      assertFalse(state.copy(busy = true).canSave)
    }
  }

  @Test fun `save remains unavailable until a baseline is provided`() {
    val model = model()
    model.updateDraft(draft)
    assertFalse(model.getViewState().value.canSave)
    model.updateDraft(draft.copy(manualBaseline = true))
    assertFalse(model.getViewState().value.canSave)
    model.updateDraft(model.getViewState().value.draft.copy(baselineKwh = "0"))
    assertTrue(model.getViewState().value.canSave)
  }

  @Test fun `search previews candidates without saving the reserve`() {
    val model = model()
    model.updateHistoryRange("2025-05-01", "2026-05-31")
    every { repository.findBaselineCandidates(context, any(), "2025-05-01", "2026-05-31") } returns listOf(reading)
    model.searchHistory()
    assertEquals(listOf(reading), model.getViewState().value.historyCandidates)
    assertTrue(model.getViewState().value.historySearched)
    verify(exactly = 0) { repository.save(any(), any()) }
  }

  @Test fun `hourly mode requires an allowance but no legacy baseline selection`() {
    val model = model()
    model.updateDraft(draft.copy(hourlyEnabled = true))
    assertTrue(model.getViewState().value.canSave)
    model.updateDraft(draft.copy(hourlyEnabled = true, initialKwh = ""))
    assertFalse(model.getViewState().value.canSave)
  }
}
