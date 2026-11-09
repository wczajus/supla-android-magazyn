/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** One raw unit is 0.00001 kWh, matching SUPLA's cumulative energy API. */
object ReserveEnergy {
  fun parseKwh(text: String): Long? = try {
    val normalized = text.trim().replace(',', '.')
    if (!normalized.matches(Regex("[0-9]+(\\.[0-9]{1,5})?"))) {
      null
    } else {
      BigDecimal(normalized).movePointRight(5).longValueExact()
    }
  } catch (_: ArithmeticException) {
    null
  }

  fun kwh(raw: Long): BigDecimal = BigDecimal.valueOf(raw, 5)
}

@Serializable
data class ReserveReading(
  val timestamp: Long,
  val importedRaw: Long,
  val phases: List<Long>? = null
) {
  init {
    require(importedRaw >= 0)
    if (phases != null) {
      require(phases.isNotEmpty() && phases.all { it >= 0 })
      require(phases.fold(0L, Math::addExact) == importedRaw)
    }
  }

  fun decreasedFrom(previous: ReserveReading): Boolean =
    importedRaw < previous.importedRaw ||
      (
        phases != null && previous.phases != null &&
          (phases.size != previous.phases.size || phases.zip(previous.phases).any { (current, old) -> current < old })
        )
}

@Serializable
data class EnergyReserve(
  val profileId: Long,
  val channelId: Int,
  val initialRaw: Long,
  val startDate: String,
  val validUntil: String,
  val zoneId: String,
  val baseline: ReserveReading,
  val manualBaseline: Boolean,
  val lastReading: ReserveReading? = null,
  val resetDetected: Boolean = false
) {
  val startsAt: Long get() = LocalDate.parse(startDate).atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
  val expiresAt: Long get() = LocalDate.parse(validUntil).plusDays(1).atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()

  init {
    require(initialRaw > 0)
    require(expiresAt > startsAt)
    require(baseline.timestamp <= startsAt)
  }
}

enum class ReserveStatus { ACTIVE, EXHAUSTED, EXPIRED, NOT_STARTED, NO_READING, COUNTER_RESET, INVALID_READING }

data class ReserveBalance(
  val status: ReserveStatus,
  val consumedRaw: Long = 0,
  val usedRaw: Long = 0,
  val remainingRaw: Long = 0,
  val excessRaw: Long = 0,
  val expiredRaw: Long = 0
)

object ReserveCalculator {
  fun calculate(reserve: EnergyReserve, now: Long): ReserveBalance {
    if (now < reserve.startsAt) return ReserveBalance(ReserveStatus.NOT_STARTED)
    if (reserve.resetDetected) return ReserveBalance(ReserveStatus.COUNTER_RESET)
    val reading = reserve.lastReading ?: return ReserveBalance(ReserveStatus.NO_READING)
    if (reading.timestamp < reserve.startsAt || reading.timestamp > minOf(now, reserve.expiresAt)) {
      return ReserveBalance(ReserveStatus.INVALID_READING)
    }
    if (reading.decreasedFrom(reserve.baseline)) return ReserveBalance(ReserveStatus.COUNTER_RESET)
    val consumed = reading.importedRaw - reserve.baseline.importedRaw
    val remaining = (reserve.initialRaw - consumed).coerceAtLeast(0)
    val expired = now >= reserve.expiresAt
    return ReserveBalance(
      status = when {
        expired -> ReserveStatus.EXPIRED
        remaining == 0L -> ReserveStatus.EXHAUSTED
        else -> ReserveStatus.ACTIVE
      },
      consumedRaw = consumed,
      usedRaw = minOf(consumed, reserve.initialRaw),
      remainingRaw = if (expired) 0 else remaining,
      excessRaw = (consumed - reserve.initialRaw).coerceAtLeast(0),
      expiredRaw = if (expired) remaining else 0
    )
  }
}
