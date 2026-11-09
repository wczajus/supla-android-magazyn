/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import org.junit.Assert.*
import org.junit.Test
import org.supla.android.core.infrastructure.DateProvider
import org.supla.android.data.source.ProfileRepository
import org.supla.android.data.source.local.entity.ProfileEntity
import org.supla.android.data.source.local.entity.custom.ChannelWithChildren
import org.supla.android.data.source.remote.rest.SuplaCloudService
import org.supla.android.data.source.remote.rest.channel.ElectricityMeasurement
import org.supla.android.usecases.channel.ReadChannelWithChildrenUseCase
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class EnergyReserveRepositoryTest {
  private val store: EnergyReserveStore = mockk(relaxed = true)
  private val channels: ReadChannelWithChildrenUseCase = mockk()
  private val profiles: ProfileRepository = mockk()
  private val provider: SuplaCloudService.Provider = mockk()
  private val cloud: SuplaCloudService = mockk()
  private val dates: DateProvider = mockk()
  private val repository = EnergyReserveRepository(store, channels, profiles, provider, dates)
  private val startsAt = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
  private val now = startsAt + 86_400_000L
  private val period =
    EnergyReserve(7, 42, 20_000_000, "2026-05-01", "2027-04-30", "Europe/Warsaw", ReserveReading(startsAt, 10_000_000), true)
  private val context = ReserveContext(7, 42, "MEW-01", period)

  init {
    every { dates.currentTimestamp() } returns now
    every { profiles.findActiveProfile() } returns Single.just(profile(7))
    every { provider.provide() } returns cloud
  }

  private fun profile(id: Long): ProfileEntity = mockk<ProfileEntity>().also { every { it.id } returns id }

  private fun measurement(timestamp: Long = now) = ElectricityMeasurement(
    Date(timestamp),
    3_000_000, 999_999, null, null,
    4_000_000, 999_999, null, null,
    5_000_000, 999_999, null, null,
    null, null
  )

  private fun respondWith(measurement: ElectricityMeasurement) {
    every { cloud.getElectricityMeasurements(remoteId = 42, order = "DESC", limit = 1, beforeTimestamp = any()) } returns
      Observable.just(listOf(measurement))
  }

  @Test fun `loads configuration by both profile and channel`() {
    val channel = mockk<ChannelWithChildren>()
    every { channel.profileId } returns 7
    every { channel.caption } returns "MEW-01"
    every { channels(42) } returns Maybe.just(channel)
    every { store.read(7, 42) } returns period
    assertEquals(period, repository.loadCache(42).reserve)
    verify(exactly = 1) { store.read(7, 42) }
    verify(exactly = 0) { store.read(8, 42) }
  }

  @Test fun `saving a manual baseline works without a cloud request`() {
    val saved = repository.save(context, ReserveDraft("200,5", "2026-05-01", "2027-04-30", true, "10000,12345"))
    assertEquals(1_000_012_345L, saved.reserve!!.baseline.importedRaw)
    assertEquals(20_050_000L, saved.reserve!!.initialRaw)
    verify(exactly = 0) { provider.provide() }
    verify(exactly = 1) { store.write(match { it.profileId == 7L && it.channelId == 42 }) }
  }

  @Test fun `caps refresh at deadline and never consumes later import`() {
    val ended = period.copy(validUntil = "2026-05-01")
    every { dates.currentTimestamp() } returns ended.expiresAt + 86_400_000
    respondWith(measurement(ended.expiresAt))
    val refreshed = repository.refresh(context.copy(reserve = ended))
    assertEquals(ended.expiresAt, refreshed.reserve!!.lastReading!!.timestamp)
    verify(exactly = 1) {
      cloud.getElectricityMeasurements(
        remoteId = 42,
        order = "DESC",
        limit = 1,
        beforeTimestamp =
        ended.expiresAt / 1000 + 1
      )
    }
  }

  @Test fun `rejects a response received after account switches and does not save it`() {
    every { profiles.findActiveProfile() } returnsMany listOf(Single.just(profile(7)), Single.just(profile(8)))
    respondWith(measurement())
    assertThrows(ReserveProblem::class.java) { repository.refresh(context) }
    verify(exactly = 0) { store.write(any()) }
  }

  @Test fun `latches per phase decrease despite increasing total`() {
    val previous = period.copy(
      baseline = ReserveReading(startsAt, 3_000_000, listOf(1_000_000, 1_000_000, 1_000_000)),
      lastReading = ReserveReading(now - 60_000, 6_000_000, listOf(2_000_000, 2_000_000, 2_000_000))
    )
    respondWith(measurement().copy(phase1Fae = 1_500_000, phase2Fae = 3_000_000, phase3Fae = 3_000_000))
    assertTrue(repository.refresh(context.copy(reserve = previous)).reserve!!.resetDetected)
    verify { store.write(match { it.resetDetected }) }
  }

  @Test fun `an older response cannot replace a newer cached reading`() {
    val cached = period.copy(lastReading = ReserveReading(now, 12_000_000))
    respondWith(measurement(now - 60_000))
    assertThrows(ReserveProblem::class.java) { repository.refresh(context.copy(reserve = cached)) }
    verify(exactly = 0) { store.write(any()) }
  }

  @Test fun `does not silently clear reset when only the allowance is edited`() {
    val value = period.copy(resetDetected = true, lastReading = ReserveReading(now, 12_000_000))
    val saved = repository.save(context.copy(reserve = value), ReserveDraft("300", value.startDate, value.validUntil, true, "100"))
    assertTrue(saved.reserve!!.resetDetected)
    assertEquals(value.lastReading, saved.reserve!!.lastReading)
  }

  @Test fun `initial history must be close to the boundary and not arbitrarily old`() {
    respondWith(measurement(startsAt - 121_000))
    assertThrows(ReserveProblem::class.java) { repository.save(context, ReserveDraft("200", "2026-05-01", "2027-04-30")) }
    verify(exactly = 0) { store.write(any()) }
  }
}
