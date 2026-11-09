/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

@Serializable
data class ReserveFlowReading(val timestamp: Long, val imports: List<Long>, val exports: List<Long>) {
  init {
    require(imports.size == 3 && exports.size == 3)
    require((imports + exports).all { it >= 0 })
  }

  val importedRaw: Long get() = imports.fold(0L, Math::addExact)
  val exportedRaw: Long get() = exports.fold(0L, Math::addExact)

  fun decreasedFrom(previous: ReserveFlowReading): Boolean =
    (imports + exports).zip(previous.imports + previous.exports).any { (current, old) -> current < old }
}

@Serializable
enum class ReserveGapMethod { PROFILE, UNIFORM, MIXED }

@Serializable
data class ReserveHistoryGap(
  val from: Long,
  val until: Long,
  val importedRaw: Long,
  val exportedRaw: Long,
  val method: ReserveGapMethod
)

@Serializable
data class ReserveProfileHour(val timestamp: Long, val imports: List<Long>, val exports: List<Long>)

@Serializable
data class ReserveHourlyProgress(
  val initial: ReserveFlowReading,
  val boundary: ReserveFlowReading,
  val importedRaw: Long = 0,
  val exportedRaw: Long = 0,
  val interpolatedBoundaries: Int = 0,
  val gaps: List<ReserveHistoryGap> = emptyList(),
  val profileHours: List<ReserveProfileHour> = emptyList(),
  val missingStartUntil: Long? = null,
  val incompleteReadings: List<Long> = emptyList(),
  val anchorEstimated: Boolean = false,
  val calculationVersion: Int = 1
)

class ReserveHourlyGap : RuntimeException()
class ReserveHourlyReset : RuntimeException()

/** Epoch hours keep the repeated autumn hour separate. All allocations preserve raw phase totals. */
object ReserveHourly {
  const val HOUR_MS = 3_600_000L
  const val MAX_SAMPLE_GAP_MS = HOUR_MS
  const val CALCULATION_VERSION = 2
  private const val PROFILE_WINDOW_MS = 7 * 24 * HOUR_MS
  private val zone = ZoneId.of("Europe/Warsaw")

  fun nextHour(timestamp: Long): Long = (Math.floorDiv(timestamp, HOUR_MS) + 1) * HOUR_MS

  fun advance(
    existing: ReserveHourlyProgress?,
    readings: List<ReserveFlowReading>,
    startsAt: Long,
    cutoff: Long
  ): ReserveHourlyProgress {
    require(startsAt % HOUR_MS == 0L)
    val ordered = readings.sortedBy { it.timestamp }
    require(ordered.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
    val profiles = ((existing?.profileHours ?: emptyList()) + profileHours(ordered, existing?.anchorEstimated == true))
      .distinctBy { it.timestamp }.sortedBy { it.timestamp }
    val gaps = existing?.gaps?.toMutableList() ?: mutableListOf()
    fun record(plan: IntervalPlan) {
      if (plan.after.timestamp - plan.before.timestamp <= HOUR_MS) return
      if (gaps.none { it.from == plan.before.timestamp && it.until == plan.after.timestamp }) {
        gaps += ReserveHistoryGap(
          plan.before.timestamp,
          plan.after.timestamp,
          plan.after.importedRaw - plan.before.importedRaw,
          plan.after.exportedRaw - plan.before.exportedRaw,
          plan.method
        )
      }
    }
    var progress = existing ?: run {
      val before = ordered.lastOrNull { it.timestamp <= startsAt }
      val after = ordered.firstOrNull { it.timestamp >= startsAt } ?: throw ReserveHourlyGap()
      val initial = if (before == null || before.timestamp == startsAt) {
        before ?: after
      } else {
        val plan = IntervalPlan(before, after, profiles)
        record(plan)
        plan.at(startsAt)
      }
      if (initial.timestamp > cutoff) throw ReserveHourlyGap()
      ReserveHourlyProgress(
        initial,
        initial,
        interpolatedBoundaries = if (before != null && before.timestamp != startsAt) 1 else 0,
        missingStartUntil = initial.timestamp.takeIf { it > startsAt },
        calculationVersion = CALCULATION_VERSION
      )
    }
    require(progress.initial.timestamp >= startsAt && progress.boundary.timestamp <= cutoff)
    var previous = ordered.lastOrNull { it.timestamp <= progress.boundary.timestamp } ?: progress.boundary
    for (reading in ordered.filter { it.timestamp > progress.boundary.timestamp }) {
      if (nextHour(progress.boundary.timestamp) > cutoff) break
      if (reading.decreasedFrom(previous)) throw ReserveHourlyReset()
      val plan = IntervalPlan(previous, reading, profiles)
      var nextBoundary = nextHour(progress.boundary.timestamp)
      while (nextBoundary <= reading.timestamp && nextBoundary <= cutoff) {
        val next = plan.at(nextBoundary)
        if (next.decreasedFrom(progress.boundary)) throw ReserveHourlyReset()
        val imported = next.importedRaw - progress.boundary.importedRaw
        val exported = next.exportedRaw - progress.boundary.exportedRaw
        record(plan)
        progress = progress.copy(
          boundary = next,
          importedRaw = Math.addExact(progress.importedRaw, (imported - exported).coerceAtLeast(0)),
          exportedRaw = Math.addExact(progress.exportedRaw, (exported - imported).coerceAtLeast(0)),
          interpolatedBoundaries = progress.interpolatedBoundaries + if (nextBoundary == reading.timestamp) 0 else 1,
          anchorEstimated = reading.timestamp != nextBoundary &&
            (reading.timestamp - previous.timestamp > HOUR_MS || (existing?.let { previous == it.boundary && it.anchorEstimated } == true))
        )
        nextBoundary += HOUR_MS
      }
      previous = reading
    }
    return progress.copy(
      gaps = gaps.sortedBy { it.from },
      profileHours = profiles.filter { it.timestamp >= progress.boundary.timestamp - 2 * PROFILE_WINDOW_MS }.takeLast(336),
      calculationVersion = CALCULATION_VERSION
    )
  }

  /** Only well-covered hours teach the profile; estimates across long gaps never feed back into it. */
  private fun profileHours(readings: List<ReserveFlowReading>, skipFirst: Boolean): List<ReserveProfileHour> {
    val result = mutableListOf<ReserveProfileHour>()
    var boundary: ReserveFlowReading? = null
    readings.zipWithNext().forEachIndexed { index, (a, b) ->
      if ((skipFirst && index == 0) || b.timestamp - a.timestamp > HOUR_MS || b.decreasedFrom(a)) {
        boundary = null
      } else {
        if (boundary == null && a.timestamp % HOUR_MS == 0L) boundary = a
        val nextTime = nextHour(a.timestamp)
        if (nextTime <= b.timestamp) {
          val next = uniform(a, b, nextTime)
          val prior = boundary
          if (prior != null && prior.timestamp + HOUR_MS == nextTime && !next.decreasedFrom(prior)) {
            result += ReserveProfileHour(
              prior.timestamp,
              next.imports.zip(prior.imports).map { it.first - it.second },
              next.exports.zip(prior.exports).map { it.first - it.second }
            )
          }
          boundary = next
        }
      }
    }
    return result
  }

  private class IntervalPlan(
    val before: ReserveFlowReading,
    val after: ReserveFlowReading,
    profiles: List<ReserveProfileHour>
  ) {
    private val times = mutableListOf(before.timestamp)
    private val cumulative = mutableListOf<List<BigInteger>>()
    val method: ReserveGapMethod

    init {
      if (after.decreasedFrom(before)) throw ReserveHourlyReset()
      var t = nextHour(before.timestamp)
      while (t < after.timestamp) {
        times += t
        t += HOUR_MS
      }
      times += after.timestamp
      val longGap = after.timestamp - before.timestamp > HOUR_MS
      val candidates = times.dropLast(1).map { time ->
        if (!longGap) return@map emptyList<ReserveProfileHour>()
        val local = Instant.ofEpochMilli(time).atZone(zone)
        profiles.filter {
          abs(it.timestamp - time) <= PROFILE_WINDOW_MS && Instant.ofEpochMilli(it.timestamp).atZone(zone).hour == local.hour
        }.sortedBy { abs(it.timestamp - time) }
          .distinctBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.take(7)
      }
      var profiled = 0
      var fallback = 0
      val deltas = (after.imports + after.exports).zip(before.imports + before.exports).map { it.first - it.second }
      val weights = (0..5).map { counter ->
        val averages = candidates.map { hours ->
          if (hours.size < 3) {
            null
          } else {
            hours.map { (it.imports + it.exports)[counter] }
              .fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }.divide(BigInteger.valueOf(hours.size.toLong()))
          }
        }
        val useProfile = longGap && averages.all { it != null } && averages.any { it!! > BigInteger.ZERO }
        if (deltas[counter] > 0 && longGap) {
          if (useProfile) profiled++ else fallback++
        }
        times.zipWithNext().mapIndexed { index, (a, b) ->
          BigInteger.valueOf(b - a).multiply(if (useProfile) averages[index]!! else BigInteger.ONE)
        }
      }
      cumulative += List(6) { BigInteger.ZERO }
      times.zipWithNext().indices.forEach { segment ->
        cumulative += (0..5).map { counter -> cumulative.last()[counter] + weights[counter][segment] }
      }
      method = when {
        profiled == 0 -> ReserveGapMethod.UNIFORM
        fallback == 0 -> ReserveGapMethod.PROFILE
        else -> ReserveGapMethod.MIXED
      }
    }

    fun at(timestamp: Long): ReserveFlowReading {
      require(timestamp in before.timestamp..after.timestamp)
      if (timestamp == before.timestamp) return before
      if (timestamp == after.timestamp) return after
      val index = times.binarySearch(timestamp)
      val weights = if (index >= 0) {
        cumulative[index]
      } else {
        val segment = -index - 2
        (0..5).map { counter ->
          val weight = cumulative[segment + 1][counter] - cumulative[segment][counter]
          cumulative[segment][counter] + weight.multiply(BigInteger.valueOf(timestamp - times[segment]))
            .divide(BigInteger.valueOf(times[segment + 1] - times[segment]))
        }
      }
      val values = (before.imports + before.exports).zip(after.imports + after.exports).mapIndexed { counter, (a, b) ->
        val allocated = BigInteger.valueOf(b - a).multiply(weights[counter]).divide(cumulative.last()[counter]).longValueExact()
        Math.addExact(a, allocated)
      }
      return ReserveFlowReading(timestamp, values.take(3), values.drop(3))
    }
  }

  private fun uniform(before: ReserveFlowReading, after: ReserveFlowReading, timestamp: Long): ReserveFlowReading {
    if (timestamp == after.timestamp) return after
    val values = (before.imports + before.exports).zip(after.imports + after.exports).map { (a, b) ->
      a + BigInteger.valueOf(b - a).multiply(BigInteger.valueOf(timestamp - before.timestamp))
        .divide(BigInteger.valueOf(after.timestamp - before.timestamp)).longValueExact()
    }
    return ReserveFlowReading(timestamp, values.take(3), values.drop(3))
  }

  fun credited(exportedRaw: Long, retainedPercent: Int): Long {
    require(exportedRaw >= 0 && retainedPercent in listOf(70, 80))
    return exportedRaw / 100 * retainedPercent + exportedRaw % 100 * retainedPercent / 100
  }
}
