/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import androidx.annotation.StringRes
import org.supla.android.R

internal data class ReserveCardStatus(val hasBalance: Boolean, @param:StringRes val message: Int? = null)

internal object ReserveCardStatuses {
  fun previous(balance: ReserveBalance): ReserveCardStatus = resolve(balance) {
    when {
      balance.status == ReserveStatus.EXPIRED -> R.string.reserve_expired
      balance.remainingRaw == 0L -> R.string.reserve_exhausted
      else -> null
    }
  }

  fun current(balance: ReserveBalance): ReserveCardStatus = resolve(balance) {
    when {
      balance.status == ReserveStatus.EXPIRED -> R.string.reserve_period_ended
      balance.currentCreditedRaw == 0L -> R.string.reserve_not_accumulated
      balance.currentRemainingRaw == 0L -> R.string.reserve_exhausted
      else -> null
    }
  }

  fun total(reserve: EnergyReserve, balance: ReserveBalance): ReserveCardStatus = resolve(balance) {
    when {
      balance.status == ReserveStatus.EXPIRED ->
        if (reserve.currentReserveEnabled) R.string.reserve_period_ended else R.string.reserve_expired
      balance.remainingRaw == 0L && (!reserve.currentReserveEnabled || balance.currentRemainingRaw == 0L) ->
        R.string.reserve_exhausted
      else -> null
    }
  }

  private fun resolve(balance: ReserveBalance, message: () -> Int?): ReserveCardStatus = when (balance.status) {
    ReserveStatus.ACTIVE, ReserveStatus.EXHAUSTED, ReserveStatus.EXPIRED -> ReserveCardStatus(true, message())
    ReserveStatus.NOT_STARTED -> ReserveCardStatus(false, R.string.reserve_not_started)
    ReserveStatus.NO_READING -> ReserveCardStatus(false, R.string.reserve_no_reading)
    ReserveStatus.COUNTER_RESET -> ReserveCardStatus(false, R.string.reserve_reset)
    ReserveStatus.INVALID_READING -> ReserveCardStatus(false, R.string.reserve_invalid)
  }
}
