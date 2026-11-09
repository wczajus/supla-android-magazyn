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
import javax.inject.Inject

data class EnergyReserveState(
  val context: ReserveContext? = null,
  val editing: Boolean = false,
  val busy: Boolean = false,
  val draft: ReserveDraft = ReserveDraft(),
  val balance: ReserveBalance? = null,
  @param:StringRes val error: Int? = null
) : ViewState()

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

  init {
    setupSuplaClientMessageHandler(messageHandler)
  }

  override fun handleSuplaMessage(message: SuplaClientMessage) {
    if (message is SuplaClientMessage.ChannelDataChanged && message.channelId == remoteId &&
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

  fun edit() {
    val reserve = currentState().context?.reserve
    val draft = if (reserve == null) {
      ReserveDraft()
    } else {
      ReserveDraft(
        initialKwh = ReserveEnergy.kwh(reserve.initialRaw).stripTrailingZeros().toPlainString(),
        startDate = reserve.startDate,
        validUntil = reserve.validUntil,
        manualBaseline = reserve.manualBaseline,
        baselineKwh = ReserveEnergy.kwh(reserve.baseline.importedRaw).stripTrailingZeros().toPlainString()
      )
    }
    updateState { it.copy(editing = true, draft = draft, error = null) }
  }

  fun updateDraft(draft: ReserveDraft) {
    updateState { it.copy(draft = draft, error = null) }
  }

  fun cancelEdit() {
    updateState { it.copy(editing = false, error = null) }
  }

  fun save() {
    val state = currentState()
    val context = state.context ?: return
    if (state.busy) return
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
    }) {
      updateState { EnergyReserveState(context = it) }
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
