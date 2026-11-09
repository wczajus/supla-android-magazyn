/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import kotlinx.serialization.Serializable
import java.math.BigInteger

/** Cumulative raw counters, never the synthetic Float values used by charts. */
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
data class ReserveHourlyProgress(
  val initial: ReserveFlowReading,
  val boundary: ReserveFlowReading,
  val importedRaw: Long = 0,
  val exportedRaw: Long = 0,
  val interpolatedBoundaries: Int = 0
)

class ReserveHourlyGap : RuntimeException()
class ReserveHourlyReset : RuntimeException()

/** Poland's UTC offsets are whole hours. Epoch hour keys keep both autumn 02:00 hours separate. */
object ReserveHourly {
  const val HOUR_MS = 3_600_000L
  const val MAX_SAMPLE_GAP_MS = HOUR_MS

  fun advance(
    existing: ReserveHourlyProgress?,
    readings: List<ReserveFlowReading>,
    startsAt: Long,
    cutoff: Long
  ): ReserveHourlyProgress {
    require(startsAt % HOUR_MS == 0L)
    val ordered = readings.sortedBy { it.timestamp }
    require(ordered.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
    var progress = existing ?: run {
      val before = ordered.lastOrNull { it.timestamp <= startsAt } ?: throw ReserveHourlyGap()
      val after = ordered.firstOrNull { it.timestamp >= startsAt } ?: throw ReserveHourlyGap()
      val initial = interpolate(before, after, startsAt)
      ReserveHourlyProgress(initial, initial, interpolatedBoundaries = if (before.timestamp == startsAt) 0 else 1)
    }
    require(progress.initial.timestamp == startsAt && progress.boundary.timestamp <= cutoff)
    var previous = ordered.lastOrNull { it.timestamp <= progress.boundary.timestamp } ?: progress.boundary
    for (reading in ordered.filter { it.timestamp > progress.boundary.timestamp }) {
      if (reading.decreasedFrom(previous)) throw ReserveHourlyReset()
      var nextBoundary = progress.boundary.timestamp + HOUR_MS
      while (nextBoundary <= reading.timestamp && nextBoundary <= cutoff) {
        val next = interpolate(previous, reading, nextBoundary)
        if (next.decreasedFrom(progress.boundary)) throw ReserveHourlyReset()
        val imported = next.importedRaw - progress.boundary.importedRaw
        val exported = next.exportedRaw - progress.boundary.exportedRaw
        progress = progress.copy(
          boundary = next,
          importedRaw = Math.addExact(progress.importedRaw, (imported - exported).coerceAtLeast(0)),
          exportedRaw = Math.addExact(progress.exportedRaw, (exported - imported).coerceAtLeast(0)),
          interpolatedBoundaries = progress.interpolatedBoundaries + if (nextBoundary == reading.timestamp) 0 else 1
        )
        nextBoundary += HOUR_MS
      }
      previous = reading
      if (progress.boundary.timestamp + HOUR_MS > cutoff) break
    }
    return progress
  }

  private fun interpolate(before: ReserveFlowReading, after: ReserveFlowReading, timestamp: Long): ReserveFlowReading {
    require(timestamp in before.timestamp..after.timestamp)
    if (after.decreasedFrom(before)) throw ReserveHourlyReset()
    if (timestamp == before.timestamp) return before
    if (timestamp == after.timestamp) {
      if (after.timestamp - before.timestamp > MAX_SAMPLE_GAP_MS) throw ReserveHourlyGap()
      return after
    }
    val duration = after.timestamp - before.timestamp
    if (duration > MAX_SAMPLE_GAP_MS) throw ReserveHourlyGap()
    fun values(a: List<Long>, b: List<Long>) = a.zip(b).map { (old, current) ->
      val delta = BigInteger.valueOf(current - old).multiply(BigInteger.valueOf(timestamp - before.timestamp))
        .divide(BigInteger.valueOf(duration)).longValueExact()
      Math.addExact(old, delta)
    }
    return ReserveFlowReading(timestamp, values(before.imports, after.imports), values(before.exports, after.exports))
  }

  /** Apply the discount once to the sum, without per-hour rounding or multiplication overflow. */
  fun credited(exportedRaw: Long, retainedPercent: Int): Long {
    require(exportedRaw >= 0 && retainedPercent in listOf(70, 80))
    return exportedRaw / 100 * retainedPercent + exportedRaw % 100 * retainedPercent / 100
  }
}
