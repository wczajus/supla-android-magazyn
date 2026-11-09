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
import org.supla.android.R
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

  private fun respondToSearch(before: List<ElectricityMeasurement>, after: List<ElectricityMeasurement>) {
    every {
      cloud.getElectricityMeasurements(remoteId = 42, order = "DESC", limit = 20, afterTimestamp = any(), beforeTimestamp = any())
    } returns Observable.just(before)
    every {
      cloud.getElectricityMeasurements(remoteId = 42, order = "ASC", limit = 20, afterTimestamp = any(), beforeTimestamp = any())
    } returns Observable.just(after)
  }

  @Test fun `automatic save requires an explicitly selected reading`() {
    assertThrows(ReserveProblem::class.java) { repository.save(context, ReserveDraft("200", "2026-05-01", "2027-04-30")) }
    verify(exactly = 0) { store.write(any()) }
    verify(exactly = 0) { provider.provide() }
  }

  @Test fun `search returns only the nearest complete reading on each side of midnight`() {
    respondToSearch(
      listOf(measurement(startsAt - 7_200_000), measurement(startsAt - 900_000), measurement(startsAt - 1_800_000)),
      listOf(measurement(startsAt + 3_600_000), measurement(startsAt + 300_000), measurement(startsAt + 1_800_000))
    )
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    assertEquals(listOf(startsAt + 300_000, startsAt - 900_000), result.map { it.timestamp })
  }

  @Test fun `search returns a single nearest sample when only the earlier side exists`() {
    respondToSearch(listOf(measurement(startsAt - 3_600_000), measurement(startsAt - 600_000)), emptyList())
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    assertEquals(listOf(startsAt - 600_000), result.map { it.timestamp })
  }

  @Test fun `exact midnight replaces the earlier candidate and is offered only once`() {
    respondToSearch(
      listOf(measurement(startsAt - 600_000), measurement(startsAt)),
      listOf(measurement(startsAt), measurement(startsAt + 600_000), measurement(startsAt + 1_200_000))
    )
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    assertEquals(listOf(startsAt, startsAt + 600_000), result.map { it.timestamp })
  }

  @Test fun `invalid allowance never overwrites an existing reserve in either baseline mode`() {
    val reading = measurement(startsAt).toReserveReading()
    for (manual in listOf(true, false)) {
      for (amount in listOf("", "   ", "0", "-1", "abc", "0,000001", "9223372036854775808")) {
        val draft = ReserveDraft(amount, "2026-05-01", "2027-04-30", manual, "100", reading)
        val error = assertThrows(ReserveProblem::class.java) { repository.save(context, draft) }
        assertEquals(R.string.reserve_error_amount, error.messageRes)
      }
    }
    verify(exactly = 0) { store.write(any()) }
  }

  @Test fun `search prefers nearest reading after midnight over a much older prior reading`() {
    respondToSearch(listOf(measurement(startsAt - 14_400_000)), listOf(measurement(startsAt + 900_000)))
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    assertEquals(startsAt + 900_000, result.first().timestamp)
    assertEquals(2, result.size)
  }

  @Test fun `accepts a selected reading more than two minutes before midnight`() {
    val reading = measurement(startsAt - 3_600_000).toReserveReading()
    val draft = ReserveDraft("200", "2026-05-01", "2027-04-30", historicalBaseline = reading)
    assertEquals(reading, repository.save(context, draft).reserve!!.baseline)
    verify(exactly = 0) { provider.provide() }
  }

  @Test fun `accepts a selected reading after midnight and retains its actual time`() {
    val reading = measurement(startsAt + 900_000).toReserveReading()
    val draft = ReserveDraft("200", "2026-05-01", "2027-04-30", historicalBaseline = reading)
    val value = repository.save(context, draft).reserve!!
    assertEquals(reading.timestamp, value.readingStartsAt)
    assertEquals(startsAt, value.startsAt)
  }

  @Test fun `search succeeds when only a later sample exists`() {
    respondToSearch(emptyList(), listOf(measurement(startsAt + 600_000)))
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    assertEquals(listOf(startsAt + 600_000), result.map { it.timestamp })
  }

  @Test fun `search removes duplicate exact midnight samples and prefers exact match`() {
    respondToSearch(listOf(measurement(startsAt)), listOf(measurement(startsAt), measurement(startsAt + 600_000)))
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    assertEquals(startsAt, result.first().timestamp)
    assertEquals(2, result.size)
  }

  @Test fun `equally distant samples prefer the one before midnight`() {
    respondToSearch(listOf(measurement(startsAt - 600_000)), listOf(measurement(startsAt + 600_000)))
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    assertEquals(startsAt - 600_000, result.first().timestamp)
  }

  @Test fun `supports a user range reaching into the previous year with bounded queries`() {
    val from = LocalDate.of(2025, 5, 1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
    respondToSearch(listOf(measurement(from + 60_000)), emptyList())
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2025-05-01", "2026-05-01")
    assertEquals(from + 60_000, result.single().timestamp)
    verify {
      cloud.getElectricityMeasurements(
        remoteId = 42,
        order = "DESC",
        limit = 20,
        afterTimestamp = from / 1000 - 1,
        beforeTimestamp =
        startsAt / 1000 + 1
      )
    }
  }

  @Test fun `samples outside the chosen dates or in the future are ignored`() {
    respondToSearch(listOf(measurement(startsAt - 86_400_000)), listOf(measurement(now + 60_000)))
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-05-01", "2026-05-02")
    assertTrue(result.isEmpty())
  }

  @Test fun `incomplete samples do not prevent choosing a complete one`() {
    respondToSearch(listOf(measurement(startsAt).copy(phase2Fae = null)), listOf(measurement(startsAt + 600_000)))
    val result = repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    assertEquals(startsAt + 600_000, result.single().timestamp)
  }

  @Test fun `account change during search prevents querying the second side`() {
    every { profiles.findActiveProfile() } returnsMany listOf(Single.just(profile(7)), Single.just(profile(8)))
    respondToSearch(listOf(measurement(startsAt)), emptyList())
    assertThrows(ReserveProblem::class.java) {
      repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-04-30", "2026-05-02")
    }
    verify(exactly = 0) {
      cloud.getElectricityMeasurements(remoteId = 42, order = "ASC", limit = 20, afterTimestamp = any(), beforeTimestamp = any())
    }
    verify(exactly = 0) { store.write(any()) }
  }

  @Test fun `search rejects reversed dates without querying cloud`() {
    assertThrows(ReserveProblem::class.java) {
      repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-05-01"), "2026-05-02", "2026-05-01")
    }
    verify(exactly = 0) { provider.provide() }
  }

  @Test fun `search date boundaries respect the 23 hour daylight saving day`() {
    val zone = ZoneId.of("Europe/Warsaw")
    val target = LocalDate.of(2026, 3, 29).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = LocalDate.of(2026, 3, 30).atStartOfDay(zone).toInstant().toEpochMilli()
    respondToSearch(listOf(measurement(target)), listOf(measurement(target + 3_600_000)))
    repository.findBaselineCandidates(context, ReserveDraft(startDate = "2026-03-29"), "2026-03-29", "2026-03-29")
    assertEquals(23 * 3_600_000L, end - target)
    verify {
      cloud.getElectricityMeasurements(
        remoteId = 42,
        order = "ASC",
        limit = 20,
        afterTimestamp = target / 1000 - 1,
        beforeTimestamp =
        end / 1000
      )
    }
  }

  private fun hourlyReading(timestamp: Long, imported: Long = 0, exported: Long = 0) = measurement(timestamp).copy(
    phase1Fae = imported,
    phase2Fae = 0,
    phase3Fae = 0,
    phase1Rae = exported,
    phase2Rae = 0,
    phase3Rae = 0
  )

  private fun respondHourly(vararg pages: List<ElectricityMeasurement>) {
    every {
      cloud.getElectricityMeasurements(remoteId = 42, order = "DESC", limit = 1, beforeTimestamp = startsAt / 1000 + 1)
    } returns Observable.just(listOf(hourlyReading(startsAt)))
    every {
      cloud.getElectricityMeasurements(remoteId = 42, order = "ASC", limit = 1000, afterTimestamp = any(), beforeTimestamp = any())
    } returnsMany pages.map { Observable.just(it) }
  }

  @Test fun `hourly refresh paginates even when server pages are smaller than requested`() {
    every { dates.currentTimestamp() } returns startsAt + 2 * ReserveHourly.HOUR_MS
    respondHourly(
      listOf(hourlyReading(startsAt + ReserveHourly.HOUR_MS, exported = 1_000_000)),
      listOf(hourlyReading(startsAt + 2 * ReserveHourly.HOUR_MS, imported = 500_000, exported = 1_000_000))
    )
    val refreshed = repository.refresh(context.copy(reserve = period.copy(hourlyEnabled = true)))
    assertEquals(1_000_000L, refreshed.reserve!!.hourlyProgress!!.exportedRaw)
    assertEquals(500_000L, refreshed.reserve!!.hourlyProgress!!.importedRaw)
    verify(exactly = 2) {
      cloud.getElectricityMeasurements(remoteId = 42, order = "ASC", limit = 1000, afterTimestamp = any(), beforeTimestamp = any())
    }
  }

  @Test fun `hourly refresh refuses missing export phases without writing a false balance`() {
    respondHourly(listOf(hourlyReading(startsAt + ReserveHourly.HOUR_MS).copy(phase3Rae = null)))
    val error = assertThrows(ReserveProblem::class.java) { repository.refresh(context.copy(reserve = period.copy(hourlyEnabled = true))) }
    assertEquals(R.string.reserve_error_hourly_phases, error.messageRes)
    verify(exactly = 0) { store.write(any()) }
  }

  @Test fun `hourly refresh does not write partial results after a long history gap`() {
    respondHourly(listOf(hourlyReading(startsAt + 2 * ReserveHourly.HOUR_MS)), emptyList())
    val error = assertThrows(ReserveProblem::class.java) { repository.refresh(context.copy(reserve = period.copy(hourlyEnabled = true))) }
    assertEquals(R.string.reserve_error_hourly_history, error.messageRes)
    verify(exactly = 0) { store.write(any()) }
  }

  @Test fun `hourly refresh rejects a response after switching accounts`() {
    every { profiles.findActiveProfile() } returnsMany listOf(Single.just(profile(7)), Single.just(profile(7)), Single.just(profile(8)))
    respondHourly(listOf(hourlyReading(startsAt + ReserveHourly.HOUR_MS)))
    assertThrows(ReserveProblem::class.java) { repository.refresh(context.copy(reserve = period.copy(hourlyEnabled = true))) }
    verify(exactly = 0) { store.write(any()) }
  }

  @Test fun `hourly save accepts a coefficient without requiring a manual cumulative baseline`() {
    val saved = repository.save(
      context,
      ReserveDraft("200", period.startDate, period.validUntil, hourlyEnabled = true, retainedPercent = 70)
    )
    assertTrue(saved.reserve!!.hourlyEnabled)
    assertEquals(70, saved.reserve!!.retainedPercent)
    assertNull(saved.reserve!!.hourlyProgress)
    verify(exactly = 0) { provider.provide() }
  }

  @Test fun `hourly save retains totals for coefficient changes and resets them for a new period`() {
    val first = ReserveFlowReading(startsAt, listOf(0, 0, 0), listOf(0, 0, 0))
    val progress = ReserveHourlyProgress(first, first, exportedRaw = 100)
    val previous = context.copy(reserve = period.copy(hourlyEnabled = true, hourlyProgress = progress))
    val draft = ReserveDraft("200", period.startDate, period.validUntil, hourlyEnabled = true, retainedPercent = 70)
    assertEquals(progress, repository.save(previous, draft).reserve!!.hourlyProgress)
    assertNull(repository.save(previous, draft.copy(startDate = "2026-05-02")).reserve!!.hourlyProgress)
  }
}
