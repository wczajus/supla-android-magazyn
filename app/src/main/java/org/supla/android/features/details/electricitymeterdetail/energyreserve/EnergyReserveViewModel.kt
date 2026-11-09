/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import androidx.annotation.StringRes
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.core.Single
import org.supla.android.R
import org.supla.android.core.infrastructure.DateProvider
import org.supla.android.core.networking.suplaclient.SuplaClientMessageHandlerWrapper
import org.supla.android.core.ui.BaseViewModel
import org.supla.android.core.ui.ViewEvent
import org.supla.android.core.ui.ViewState
import org.supla.android.extensions.subscribeBy
import org.supla.android.tools.SuplaThreading
import org.supla.core.shared.infrastructure.messaging.SuplaClientMessage
import java.time.LocalDate
import javax.inject.Inject

data class EnergyReserveState(
  val context: ReserveContext? = null,
  val editing: Boolean = false,
  val busy: Boolean = false,
  val draft: ReserveDraft = ReserveDraft(),
  val balance: ReserveBalance? = null,
  val historyFrom: String = "",
  val historyUntil: String = "",
  val historyCandidates: List<ReserveReading> = emptyList(),
  val historySearched: Boolean = false,
  @param:StringRes val error: Int? = null
) : ViewState() {
  val canSave: Boolean
    get() = !busy && draft.hasValidAmount && when {
      draft.usesAutomaticHistory -> true
      draft.manualBaseline -> ReserveEnergy.parseKwh(draft.baselineKwh) != null
      else -> draft.historicalBaseline != null
    }
}

sealed class EnergyReserveEvent : ViewEvent

@HiltViewModel
class EnergyReserveViewModel @Inject constructor(
  private val repository: EnergyReserveRepository,
  private val dateProvider: DateProvider,
  messageHandler: SuplaClientMessageHandlerWrapper,
  threading: SuplaThreading
) : BaseViewModel<EnergyReserveState, EnergyReserveEvent>(EnergyReserveState(), threading) {
  private var remoteId: Int = 0
  private var lastRefreshAt: Long = 0
  private var visible = false

  override fun onStart() {
    visible = true
  }

  override fun onStop() {
    visible = false
  }

  init {
    setupSuplaClientMessageHandler(messageHandler)
  }

  override fun handleSuplaMessage(message: SuplaClientMessage) {
    if (visible && message is SuplaClientMessage.ChannelDataChanged && message.channelId == remoteId &&
      !currentState().editing && dateProvider.currentTimestamp() - lastRefreshAt >= 60_000
    ) {
      refresh()
    }
  }

  fun load(remoteId: Int) {
    if (currentState().busy) return
    this.remoteId = remoteId
    runOperation({ repository.loadCache(remoteId) }) { context ->
      showContext(context)
      refresh()
    }
  }

  fun refresh() {
    val context = currentState().context ?: return
    if (currentState().busy || context.reserve == null) return
    lastRefreshAt = dateProvider.currentTimestamp()
    runOperation({ repository.refresh(context) }) { showContext(it) }
  }

  fun rebuildHistory() {
    val state = currentState()
    val context = state.context ?: return
    if (state.busy) return
    lastRefreshAt = dateProvider.currentTimestamp()
    runOperation({ repository.rebuildHistory(context) }) { showContext(it) }
  }

  fun edit() {
    val reserve = currentState().context?.reserve
    val draft = if (reserve == null) {
      ReserveDraft(hourlyEnabled = true)
    } else {
      ReserveDraft(
        initialKwh = ReserveEnergy.kwh(reserve.initialRaw).stripTrailingZeros().toPlainString(),
        startDate = reserve.startDate,
        validUntil = reserve.validUntil,
        manualBaseline = reserve.manualBaseline,
        baselineKwh = ReserveEnergy.kwh(reserve.baseline.importedRaw).stripTrailingZeros().toPlainString(),
        historicalBaseline = if (reserve.manualBaseline || reserve.hourlyEnabled ||
          reserve.currentReserveEnabled
        ) {
          null
        } else {
          reserve.baseline
        },
        hourlyEnabled = reserve.hourlyEnabled,
        retainedPercent = reserve.retainedPercent,
        currentReserveEnabled = reserve.currentReserveEnabled
      )
    }
    val date = LocalDate.parse(draft.startDate)
    updateState {
      it.copy(
        editing = true,
        draft = draft,
        historyFrom = date.minusDays(1).toString(),
        historyUntil = date.plusDays(1).toString(),
        historyCandidates = emptyList(),
        historySearched = false,
        error = null
      )
    }
  }

  fun updateDraft(draft: ReserveDraft) {
    updateState { state ->
      if (draft.startDate != state.draft.startDate || draft.validUntil != state.draft.validUntil) {
        val date = LocalDate.parse(draft.startDate)
        state.copy(
          draft = draft.copy(historicalBaseline = null),
          historyFrom = date.minusDays(1).toString(),
          historyUntil = date.plusDays(1).toString(),
          historyCandidates = emptyList(),
          historySearched = false,
          error = null
        )
      } else {
        state.copy(draft = draft, error = null)
      }
    }
  }

  fun updateHistoryRange(from: String, until: String) {
    updateState {
      it.copy(historyFrom = from, historyUntil = until, historyCandidates = emptyList(), historySearched = false, error = null)
    }
  }

  fun searchHistory() {
    val state = currentState()
    val context = state.context ?: return
    if (state.busy) return
    runOperation({ repository.findBaselineCandidates(context, state.draft, state.historyFrom, state.historyUntil) }) { candidates ->
      updateState { it.copy(historyCandidates = candidates, historySearched = true) }
    }
  }

  fun cancelEdit() {
    updateState { it.copy(editing = false, error = null) }
  }

  fun save() {
    val state = currentState()
    val context = state.context ?: return
    if (state.busy) return
    if (!state.draft.hasValidAmount) {
      updateState { it.copy(error = R.string.reserve_error_amount) }
      return
    }
    runOperation({ repository.save(context, state.draft) }) { saved ->
      updateState { it.copy(editing = false) }
      showContext(saved)
      refresh()
    }
  }

  fun delete() {
    if (currentState().busy) return
    val profileId = currentState().context?.profileId
    runOperation({
      repository.delete(remoteId, profileId)
      repository.loadCache(remoteId)
    }) { context ->
      updateState { EnergyReserveState(context = context) }
    }
  }

  private fun showContext(context: ReserveContext) {
    updateState {
      val previous = it.context
      val state = if (previous != null && (previous.profileId != context.profileId || previous.channelId != context.channelId)) {
        EnergyReserveState()
      } else {
        it
      }
      state.copy(
        context = context,
        balance = context.reserve?.let { value ->
          ReserveCalculator.calculate(value, dateProvider.currentTimestamp())
        }
      )
    }
  }

  private fun <T : Any> runOperation(action: () -> T, onSuccess: (T) -> Unit) {
    updateState { it.copy(busy = true, error = null) }
    Single.fromCallable(action).attachSilent().subscribeBy(
      onSuccess = {
        updateState { state -> state.copy(busy = false) }
        onSuccess(it)
      },
      onError = { error ->
        val message = (error as? ReserveProblem)?.messageRes ?: R.string.reserve_error_download
        updateState {
          if (message == R.string.reserve_error_profile) EnergyReserveState(error = message) else it.copy(busy = false, error = message)
        }
      }
    ).disposeBySelf()
  }
}
