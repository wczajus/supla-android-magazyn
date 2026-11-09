/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import androidx.annotation.StringRes
import org.supla.android.R
import org.supla.android.core.infrastructure.DateProvider
import org.supla.android.data.source.ProfileRepository
import org.supla.android.data.source.remote.rest.SuplaCloudService
import org.supla.android.data.source.remote.rest.channel.ElectricityMeasurement
import org.supla.android.usecases.channel.ReadChannelWithChildrenUseCase
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

class ReserveProblem(@StringRes val messageRes: Int) : RuntimeException()

data class ReserveContext(val profileId: Long, val channelId: Int, val caption: String, val reserve: EnergyReserve?)

data class ReserveDraft(
  val initialKwh: String = "",
  val startDate: String = LocalDate.now(ZoneId.of("Europe/Warsaw")).toString(),
  val validUntil: String = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusYears(1).minusDays(1).toString(),
  val manualBaseline: Boolean = false,
  val baselineKwh: String = ""
)

@Singleton
class EnergyReserveRepository @Inject constructor(
  private val store: EnergyReserveStore,
  private val readChannel: ReadChannelWithChildrenUseCase,
  private val profileRepository: ProfileRepository,
  private val cloudProvider: SuplaCloudService.Provider,
  private val dateProvider: DateProvider
) {
  fun loadCache(remoteId: Int): ReserveContext {
    val channel = readChannel(remoteId).blockingGet() ?: throw ReserveProblem(R.string.reserve_error_channel)
    ensureProfile(channel.profileId)
    val reserve = try {
      store.read(channel.profileId, remoteId)?.also {
        require(it.profileId == channel.profileId && it.channelId == remoteId)
      }
    } catch (_: Exception) {
      throw ReserveProblem(R.string.reserve_error_storage)
    }
    return ReserveContext(channel.profileId, remoteId, channel.caption, reserve)
  }

  fun refresh(context: ReserveContext): ReserveContext {
    ensureProfile(context.profileId)
    val reserve = context.reserve ?: return context
    if (reserve.resetDetected || dateProvider.currentTimestamp() < reserve.startsAt) return context
    val cutoff = minOf(dateProvider.currentTimestamp(), reserve.expiresAt)
    val reading = readAt(context.channelId, cutoff)
    ensureProfile(context.profileId)
    if (reading.timestamp < reserve.startsAt || reading.timestamp > cutoff) throw ReserveProblem(R.string.reserve_error_reading)
    if (reserve.lastReading?.let { reading.timestamp < it.timestamp } == true) throw ReserveProblem(R.string.reserve_error_reading)
    val updated = reserve.copy(
      lastReading = reading,
      resetDetected = reading.decreasedFrom(reserve.baseline) || reserve.lastReading?.let { reading.decreasedFrom(it) } == true
    )
    write(updated)
    return context.copy(reserve = updated)
  }

  fun save(context: ReserveContext, draft: ReserveDraft): ReserveContext {
    ensureProfile(context.profileId)
    val initialRaw = ReserveEnergy.parseKwh(draft.initialKwh)?.takeIf { it > 0 }
      ?: throw ReserveProblem(R.string.reserve_error_amount)
    val start: LocalDate
    val end: LocalDate
    try {
      start = LocalDate.parse(draft.startDate)
      end = LocalDate.parse(draft.validUntil)
      require(!end.isBefore(start))
    } catch (_: Exception) {
      throw ReserveProblem(R.string.reserve_error_dates)
    }
    val zone = ZoneId.of("Europe/Warsaw")
    val startsAt = start.atStartOfDay(zone).toInstant().toEpochMilli()
    val baseline = if (draft.manualBaseline) {
      val raw = ReserveEnergy.parseKwh(draft.baselineKwh) ?: throw ReserveProblem(R.string.reserve_error_baseline)
      ReserveReading(startsAt, raw)
    } else {
      readAt(context.channelId, startsAt).also {
        if (it.timestamp > startsAt || startsAt - it.timestamp > 120_000) throw ReserveProblem(R.string.reserve_error_history)
      }
    }
    ensureProfile(context.profileId)
    val reserve = EnergyReserve(
      profileId = context.profileId,
      channelId = context.channelId,
      initialRaw = initialRaw,
      startDate = start.toString(),
      validUntil = end.toString(),
      zoneId = zone.id,
      baseline = baseline,
      manualBaseline = draft.manualBaseline
    )
    val previous = context.reserve
    val sameBaseline = previous != null && previous.baseline == reserve.baseline && previous.startsAt == reserve.startsAt
    val lastReading = if (sameBaseline) previous?.lastReading?.takeIf { it.timestamp <= reserve.expiresAt } else null
    val saved = reserve.copy(lastReading = lastReading, resetDetected = sameBaseline && previous?.resetDetected == true)
    write(saved)
    return context.copy(reserve = saved)
  }

  fun delete(remoteId: Int, expectedProfileId: Long?) {
    expectedProfileId?.let { ensureProfile(it) }
    val channel = readChannel(remoteId).blockingGet() ?: throw ReserveProblem(R.string.reserve_error_channel)
    ensureProfile(channel.profileId)
    try {
      store.delete(channel.profileId, remoteId)
    } catch (_: Exception) {
      throw ReserveProblem(R.string.reserve_error_storage)
    }
  }

  fun ensureProfile(profileId: Long) {
    if (profileRepository.findActiveProfile().blockingGet().id != profileId) throw ReserveProblem(R.string.reserve_error_profile)
  }

  private fun readAt(remoteId: Int, timestamp: Long): ReserveReading {
    val measurement = cloudProvider.provide().getElectricityMeasurements(
      remoteId = remoteId,
      order = "DESC",
      limit = 1,
      beforeTimestamp = timestamp / 1000 + 1
    ).blockingFirst().firstOrNull() ?: throw ReserveProblem(R.string.reserve_error_history)
    return measurement.toReserveReading()
  }

  private fun write(reserve: EnergyReserve) {
    ensureProfile(reserve.profileId)
    try {
      store.write(reserve)
    } catch (_: Exception) {
      throw ReserveProblem(R.string.reserve_error_storage)
    }
  }
}

/** MEW-01: require all three import counters; export and reactive energy never enter the balance. */
internal fun ElectricityMeasurement.toReserveReading(): ReserveReading {
  val phases = listOf(phase1Fae, phase2Fae, phase3Fae)
  if (phases.any { it == null || it < 0 }) throw ReserveProblem(R.string.reserve_error_phases)
  val values = phases.filterNotNull()
  return ReserveReading(date.time, values.fold(0L, Math::addExact), values)
}
