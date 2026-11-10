/* SPDX-License-Identifier: GPL-2.0-or-later */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.supla.android.R
import org.supla.android.core.ui.theme.Distance
import org.supla.android.core.ui.theme.SuplaTheme
import org.supla.android.extensions.suplaCard
import org.supla.android.features.details.detailbase.base.ItemBundle
import org.supla.android.main.ViewModelHost
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun EnergyReserveScreen(item: ItemBundle, viewModel: EnergyReserveViewModel = hiltViewModel()) {
  DisposableEffect(viewModel) {
    onDispose { viewModel.onStop() }
  }
  ViewModelHost(viewModel = viewModel, onResume = { viewModel.load(item.remoteId) }) {
    EnergyReserveContent(
      state = it,
      actions = ReserveActions(
        edit = viewModel::edit,
        refresh = viewModel::refresh,
        rebuild = viewModel::rebuildHistory,
        save = viewModel::save,
        cancel = viewModel::cancelEdit,
        delete = viewModel::delete,
        draftChanged = viewModel::updateDraft,
        searchHistory = viewModel::searchHistory,
        historyRangeChanged = viewModel::updateHistoryRange,
        retry = { viewModel.load(item.remoteId) }
      )
    )
  }
}

private data class ReserveActions(
  val edit: () -> Unit = {},
  val refresh: () -> Unit = {},
  val rebuild: () -> Unit = {},
  val save: () -> Unit = {},
  val cancel: () -> Unit = {},
  val delete: () -> Unit = {},
  val draftChanged: (ReserveDraft) -> Unit = {},
  val searchHistory: () -> Unit = {},
  val historyRangeChanged: (String, String) -> Unit = { _, _ -> },
  val retry: () -> Unit = {}
)

@Composable
private fun EnergyReserveContent(state: EnergyReserveState, actions: ReserveActions = ReserveActions()) {
  var confirmDelete by remember { mutableStateOf(false) }
  Column(
    Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(Distance.default),
    verticalArrangement = Arrangement.spacedBy(Distance.small)
  ) {
    Text(stringResource(R.string.reserve_title), style = MaterialTheme.typography.headlineSmall)
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
      }
      if (state.context == null) {
        ReserveButton(onClick = actions.retry, enabled = !state.busy) { Text(stringResource(R.string.reserve_refresh)) }
        if (it == R.string.reserve_error_storage && !state.editing) {
          ReserveButton(onClick = actions.edit, enabled = !state.busy) { Text(stringResource(R.string.reserve_edit)) }
        }
      }
    }
    if (state.editing) {
      ReserveForm(state, actions)
      if (state.context?.reserve != null || state.context == null) {
        ReserveTextButton(onClick = { confirmDelete = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.reserve_delete))
        }
      }
    } else {
      val reserve = state.context?.reserve
      if (reserve != null && state.balance != null) {
        ReserveOverview(reserve, state.balance)
        if (reserve.currentReserveEnabled) {
          ReserveCurrentOverview(reserve, state.balance)
        }
        ReserveTotalOverview(reserve, state.balance)
        if (reserve.hourlyEnabled) ReserveHistoryQuality(reserve, state.balance, !state.busy, actions.rebuild)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          ReserveButton(onClick = actions.refresh, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.reserve_refresh))
          }
          ReserveButton(onClick = actions.edit, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.reserve_edit))
          }
        }
      } else if (state.context != null && !state.busy) {
        ReserveCard {
          ReserveBattery(0f, configured = false)
          Text(stringResource(R.string.reserve_empty), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
          ReserveButton(onClick = actions.edit) { Text(stringResource(R.string.reserve_configure)) }
        }
      }
    }
    Text(
      stringResource(R.string.reserve_counter_help),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(stringResource(R.string.reserve_delete)) },
      text = { Text(stringResource(R.string.reserve_delete_question)) },
      confirmButton = {
        ReserveTextButton(onClick = {
          confirmDelete = false
          actions.delete()
        }) { Text(stringResource(R.string.reserve_delete)) }
      },
      dismissButton = { ReserveTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.reserve_cancel)) } }
    )
  }
}

@Composable
private fun ReserveHistoryQuality(reserve: EnergyReserve, balance: ReserveBalance, enabled: Boolean, onRebuild: () -> Unit) {
  var showDetails by remember { mutableStateOf(false) }
  val progress = reserve.hourlyProgress
  val gaps = progress?.gaps.orEmpty()
  val duration = gaps.sumOf { it.until - it.from }
  val title = when {
    balance.partial -> R.string.reserve_quality_partial
    balance.approximate -> R.string.reserve_quality_estimated
    else -> R.string.reserve_quality_title
  }
  ReserveCard {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Distance.tiny)) {
      Text(stringResource(title), style = MaterialTheme.typography.labelMedium)
      if (gaps.isNotEmpty()) {
        Text(stringResource(R.string.reserve_gap_summary, gaps.size, formatHours(duration)), style = MaterialTheme.typography.bodyMedium)
      }
      if (balance.partial) Text(stringResource(R.string.reserve_partial_help), style = MaterialTheme.typography.bodySmall)
      ReserveTextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.reserve_quality_details)) }
    }
  }
  if (showDetails) {
    AlertDialog(
      onDismissRequest = { showDetails = false },
      title = { Text(stringResource(title)) },
      text = {
        Column(
          Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(stringResource(R.string.reserve_quality_help))
          progress?.let {
            Text(stringResource(R.string.reserve_hourly_until, displayTime(it.boundary.timestamp, reserve.zoneId)))
            it.missingStartUntil?.let { first ->
              Text(
                stringResource(
                  R.string.reserve_missing_start,
                  displayTime(reserve.startsAt, reserve.zoneId),
                  displayTime(first, reserve.zoneId)
                )
              )
            }
            if (ReserveHourly.nextHour(it.boundary.timestamp) <= minOf(System.currentTimeMillis(), reserve.expiresAt)) {
              Text(stringResource(R.string.reserve_missing_end, displayTime(it.boundary.timestamp, reserve.zoneId)))
            }
            if (it.incompleteReadings.isNotEmpty()) {
              Text(stringResource(R.string.reserve_incomplete_rows, it.incompleteReadings.size))
            }
          }
          if (progress == null) Text(stringResource(R.string.reserve_no_reading))
          if (gaps.isEmpty() && progress != null) Text(stringResource(R.string.reserve_gap_none))
          gaps.forEach { gap ->
            HorizontalDivider()
            Text(
              "${displayTime(gap.from, reserve.zoneId)} → ${displayTime(gap.until, reserve.zoneId)}",
              style = MaterialTheme.typography.labelLarge
            )
            Text(stringResource(R.string.reserve_gap_duration, formatHours(gap.until - gap.from)))
            Text(stringResource(R.string.reserve_gap_energy, formatEnergy(gap.importedRaw), formatEnergy(gap.exportedRaw)))
            Text(
              stringResource(
                when (gap.method) {
                  ReserveGapMethod.PROFILE -> R.string.reserve_gap_profile
                  ReserveGapMethod.UNIFORM -> R.string.reserve_gap_uniform
                  ReserveGapMethod.MIXED -> R.string.reserve_gap_mixed
                }
              )
            )
          }
          Text(stringResource(R.string.reserve_rebuild_help), style = MaterialTheme.typography.bodySmall)
          ReserveButton(onClick = {
            showDetails = false
            onRebuild()
          }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reserve_rebuild)) }
        }
      },
      confirmButton = { ReserveTextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.reserve_quality_close)) } }
    )
  }
}

@Composable
private fun ReserveCard(content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().suplaCard().padding(Distance.small),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(Distance.tiny),
    content = content
  )
}

@Composable
private fun ReserveCardTitle(title: Int) {
  Text(
    stringResource(title),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center
  )
}

@Composable
private fun ReserveEnergyValue(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

@Composable
private fun ReserveOverview(reserve: EnergyReserve, balance: ReserveBalance) {
  val available = balance.status in listOf(ReserveStatus.ACTIVE, ReserveStatus.EXHAUSTED, ReserveStatus.EXPIRED)
  val fraction = if (available) (balance.remainingRaw.toDouble() / reserve.initialRaw).toFloat().coerceIn(0f, 1f) else 0f
  val statusRes = when (balance.status) {
    ReserveStatus.ACTIVE -> R.string.reserve_active
    ReserveStatus.EXHAUSTED -> R.string.reserve_exhausted
    ReserveStatus.EXPIRED -> R.string.reserve_expired
    ReserveStatus.NOT_STARTED -> R.string.reserve_not_started
    ReserveStatus.NO_READING -> R.string.reserve_no_reading
    ReserveStatus.COUNTER_RESET -> R.string.reserve_reset
    ReserveStatus.INVALID_READING -> R.string.reserve_invalid
  }
  ReserveCard {
    ReserveCardTitle(R.string.reserve_previous_title)
    ReserveBattery(fraction)
    if (available) {
      Text(
        stringResource(R.string.reserve_available_percentage, formatPercentage(fraction)),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
      )
      if (balance.status != ReserveStatus.ACTIVE) Text(stringResource(statusRes), style = MaterialTheme.typography.bodyMedium)
      Text(
        stringResource(R.string.reserve_remaining),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      ReserveEnergyValue("${if (balance.approximate) "≈ " else ""}${formatEnergy(balance.remainingRaw)} kWh")
      LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
      Text(stringResource(statusRes), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.reserve_valid_until, displayDate(reserve.validUntil)), style = MaterialTheme.typography.bodyMedium)
    ReserveMetric(R.string.reserve_initial, reserve.initialRaw)
    if (available) {
      ReserveMetric(R.string.reserve_used, balance.usedRaw)
    }
  }
}

@Composable
private fun ReserveCurrentOverview(reserve: EnergyReserve, balance: ReserveBalance) {
  val available = balance.status in listOf(ReserveStatus.ACTIVE, ReserveStatus.EXHAUSTED, ReserveStatus.EXPIRED)
  val fraction = if (balance.currentCreditedRaw > 0) {
    (balance.currentRemainingRaw.toDouble() / balance.currentCreditedRaw).toFloat().coerceIn(0f, 1f)
  } else {
    0f
  }
  ReserveCard {
    ReserveCardTitle(R.string.reserve_current_title)
    ReserveBattery(fraction)
    if (available) {
      Text(
        stringResource(R.string.reserve_available_percentage, formatPercentage(fraction)),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
      )
      ReserveEnergyValue("${if (balance.approximate) "≈ " else ""}${formatEnergy(balance.currentRemainingRaw)} kWh")
      LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
      ReserveMetric(if (reserve.hourlyEnabled) R.string.reserve_export_hourly else R.string.reserve_export_raw, balance.exportedRaw)
      ReserveMetric(R.string.reserve_current_credited, balance.currentCreditedRaw, reserve.retainedPercent)
      ReserveMetric(R.string.reserve_current_used, balance.currentUsedRaw)
    } else {
      Text(stringResource(R.string.reserve_no_reading), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.reserve_fifo_help), style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun ReserveTotalOverview(reserve: EnergyReserve, balance: ReserveBalance) {
  val available = balance.status in listOf(ReserveStatus.ACTIVE, ReserveStatus.EXHAUSTED, ReserveStatus.EXPIRED)
  ReserveCard {
    ReserveCardTitle(R.string.reserve_total_available)
    if (available) {
      val currentRemaining = if (reserve.currentReserveEnabled) balance.currentRemainingRaw else 0L
      val total = ReserveEnergy.kwh(balance.remainingRaw).add(ReserveEnergy.kwh(currentRemaining))
      val formattedTotal = formatNumber(total)
      ReserveEnergyValue("${if (balance.approximate) "≈ " else ""}$formattedTotal kWh")
    } else {
      Text(stringResource(R.string.reserve_total_unavailable), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
    if (available) ReserveMetric(R.string.reserve_imported, balance.consumedRaw)
    val timestamp = if (reserve.hourlyEnabled) reserve.hourlyProgress?.boundary?.timestamp else reserve.lastReading?.timestamp
    timestamp?.let {
      Text(
        stringResource(
          if (reserve.hourlyEnabled) R.string.reserve_hourly_until else R.string.reserve_data_until,
          displayTime(it, reserve.zoneId)
        ),
        style = MaterialTheme.typography.bodySmall
      )
    }
  }
}

@Composable
private fun ReserveMetric(label: Int, raw: Long, vararg arguments: Any) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      stringResource(label, *arguments),
      Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text("${formatEnergy(raw)} kWh", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
  }
}

@Composable
private fun ReserveBattery(fraction: Float, configured: Boolean = true) {
  val color = if (fraction <= 0.2f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
  val outline = MaterialTheme.colorScheme.onSurfaceVariant
  val background = MaterialTheme.colorScheme.outlineVariant
  val description = if (configured) {
    stringResource(
      R.string.reserve_battery,
      (fraction * 100).roundToInt()
    )
  } else {
    stringResource(R.string.reserve_title)
  }
  Canvas(Modifier.size(96.dp, 48.dp).semantics { contentDescription = description }) {
    val stroke = 1.5.dp.toPx()
    val bodyWidth = size.width - 8.dp.toPx()
    val radius = CornerRadius(6.dp.toPx())
    drawRoundRect(outline, Offset(stroke, stroke), Size(bodyWidth - stroke * 2, size.height - stroke * 2), radius, style = Stroke(stroke))
    val inset = 4.dp.toPx()
    val fillWidth = bodyWidth - inset * 2
    val fillHeight = size.height - inset * 2
    drawRoundRect(background, Offset(inset, inset), Size(fillWidth, fillHeight), CornerRadius(3.dp.toPx()))
    if (fraction > 0f) drawRoundRect(color, Offset(inset, inset), Size(fillWidth * fraction, fillHeight), CornerRadius(3.dp.toPx()))
    drawRoundRect(
      outline,
      Offset(bodyWidth + 2.dp.toPx(), size.height * 0.32f),
      Size(5.dp.toPx(), size.height * 0.36f),
      CornerRadius(2.dp.toPx())
    )
  }
}

@Composable
private fun ReserveFormSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
  ReserveCard {
    ReserveCardTitle(title)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Distance.tiny), content = content)
  }
}

@Composable
private fun ReserveToggle(
  label: Int,
  checked: Boolean,
  enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  onHelp: (() -> Unit)? = null
) {
  Row(
    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Distance.tiny)
  ) {
    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    if (onHelp != null) {
      IconButton(onClick = onHelp) {
        Icon(
          painterResource(R.drawable.ic_info),
          contentDescription = stringResource(R.string.reserve_balancing_help_title),
          tint = MaterialTheme.colorScheme.primary
        )
      }
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}

@Composable
private fun ReserveForm(state: EnergyReserveState, actions: ReserveActions) {
  var showHistory by remember { mutableStateOf(false) }
  var showBalancingHelp by remember { mutableStateOf(false) }
  val draft = state.draft
  val enabled = !state.busy
  if (state.context?.reserve != null) Text(stringResource(R.string.reserve_edit_help), style = MaterialTheme.typography.bodySmall)
  ReserveFormSection(R.string.reserve_form_period) {
    OutlinedTextField(
      value = draft.initialKwh,
      onValueChange = { actions.draftChanged(draft.copy(initialKwh = it)) },
      label = { Text(stringResource(R.string.reserve_amount_label), style = MaterialTheme.typography.bodySmall) },
      textStyle = MaterialTheme.typography.bodyMedium,
      shape = reserveFieldShape(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      singleLine = true,
      enabled = enabled,
      colors = reserveFieldColors(),
      isError = !draft.hasValidAmount && (draft.initialKwh.isNotEmpty() || state.error == R.string.reserve_error_amount),
      supportingText = {
        if (!draft.hasValidAmount) Text(stringResource(R.string.reserve_error_amount))
      },
      modifier = Modifier.fillMaxWidth()
    )
    ReserveDateInput(R.string.reserve_start_label, draft.startDate, enabled) { actions.draftChanged(draft.copy(startDate = it)) }
    ReserveDateInput(R.string.reserve_end_label, draft.validUntil, enabled) { actions.draftChanged(draft.copy(validUntil = it)) }
    Text(
      stringResource(R.string.reserve_timezone),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
  ReserveFormSection(R.string.reserve_form_calculation) {
    ReserveToggle(
      label = R.string.reserve_hourly_enable,
      checked = draft.hourlyEnabled,
      enabled = enabled,
      onCheckedChange = { actions.draftChanged(draft.copy(hourlyEnabled = it)) },
      onHelp = { showBalancingHelp = true }
    )
    ReserveToggle(
      label = R.string.reserve_current_enable_label,
      checked = draft.currentReserveEnabled,
      enabled = enabled,
      onCheckedChange = { actions.draftChanged(draft.copy(currentReserveEnabled = it)) }
    )
    if (draft.currentReserveEnabled) {
      Text(stringResource(R.string.reserve_coefficient_label), style = MaterialTheme.typography.labelMedium)
      listOf(80, 70).forEach { retained ->
        Row(
          Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).selectable(
            selected = draft.retainedPercent == retained,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = { actions.draftChanged(draft.copy(retainedPercent = retained)) }
          ),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Distance.tiny)
        ) {
          RadioButton(selected = draft.retainedPercent == retained, onClick = null, enabled = enabled)
          Text(stringResource(R.string.reserve_retained, 100 - retained, retained), style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
    if (draft.usesAutomaticHistory) {
      Text(
        stringResource(if (draft.hourlyEnabled) R.string.reserve_hourly_setup_help else R.string.reserve_raw_dual_setup_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
  if (!draft.usesAutomaticHistory) {
    ReserveFormSection(R.string.reserve_form_baseline) {
      ReserveToggle(
        label = R.string.reserve_manual_label,
        checked = draft.manualBaseline,
        enabled = enabled,
        onCheckedChange = { actions.draftChanged(draft.copy(manualBaseline = it)) }
      )
      if (draft.manualBaseline) {
        OutlinedTextField(
          value = draft.baselineKwh,
          onValueChange = { actions.draftChanged(draft.copy(baselineKwh = it)) },
          label = { Text(stringResource(R.string.reserve_baseline_label), style = MaterialTheme.typography.bodySmall) },
          textStyle = MaterialTheme.typography.bodyMedium,
          shape = reserveFieldShape(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          enabled = enabled,
          colors = reserveFieldColors(),
          modifier = Modifier.fillMaxWidth()
        )
        Text(stringResource(R.string.reserve_baseline_help), style = MaterialTheme.typography.bodySmall)
      } else {
        Text(stringResource(R.string.reserve_history_help), style = MaterialTheme.typography.bodySmall)
        draft.historicalBaseline?.let { reading ->
          HorizontalDivider()
          Text(stringResource(R.string.reserve_history_selected), style = MaterialTheme.typography.labelMedium)
          Text(displayTime(reading.timestamp, "Europe/Warsaw"), style = MaterialTheme.typography.bodyMedium)
          Text("${formatEnergy(reading.importedRaw)} kWh", style = MaterialTheme.typography.labelMedium)
        }
        ReserveButton(onClick = {
          showHistory = true
          actions.searchHistory()
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.reserve_history_choose))
        }
      }
    }
  }
  ReserveButton(
    onClick = actions.save,
    enabled = state.canSave,
    modifier = Modifier.fillMaxWidth()
  ) { Text(stringResource(R.string.reserve_save)) }
  ReserveTextButton(onClick = actions.cancel, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.reserve_cancel))
  }
  if (showHistory) {
    ReserveHistoryDialog(state, actions, onDismiss = { showHistory = false }) { reading ->
      actions.draftChanged(draft.copy(historicalBaseline = reading))
      showHistory = false
    }
  }
  if (showBalancingHelp) {
    AlertDialog(
      onDismissRequest = { showBalancingHelp = false },
      title = { Text(stringResource(R.string.reserve_balancing_help_title)) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.reserve_balancing_help_on))
          Text(stringResource(R.string.reserve_balancing_help_off))
          Text(stringResource(R.string.reserve_balancing_help_pools))
        }
      },
      confirmButton = {
        ReserveTextButton(onClick = { showBalancingHelp = false }) { Text(stringResource(R.string.reserve_help_close)) }
      }
    )
  }
}

@Composable
private fun ReserveHistoryDialog(
  state: EnergyReserveState,
  actions: ReserveActions,
  onDismiss: () -> Unit,
  onSelected: (ReserveReading) -> Unit
) {
  val candidates = state.historyCandidates
  val nearest = candidates.firstOrNull()
  var selected by remember(candidates) { mutableStateOf(nearest) }
  val target = startOfDate(state.draft.startDate)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.reserve_history_choose)) },
    text = {
      Column(
        Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(stringResource(R.string.reserve_history_target, displayTime(target, "Europe/Warsaw")))
        Text(stringResource(R.string.reserve_history_bounds), style = MaterialTheme.typography.bodySmall)
        ReserveDateInput(R.string.reserve_history_from, state.historyFrom, !state.busy) {
          actions.historyRangeChanged(it, state.historyUntil)
        }
        ReserveDateInput(R.string.reserve_history_until, state.historyUntil, !state.busy) {
          actions.historyRangeChanged(state.historyFrom, it)
        }
        ReserveButton(onClick = actions.searchHistory, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.reserve_history_search))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        if (state.historySearched && candidates.isEmpty() && !state.busy) {
          Text(stringResource(R.string.reserve_history_empty))
        }
        candidates.sortedBy { it.timestamp }.forEach { reading ->
          Row(
            Modifier.fillMaxWidth().selectable(
              selected = selected == reading,
              enabled = !state.busy,
              role = Role.RadioButton,
              onClick = { selected = reading }
            ).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(selected = selected == reading, onClick = null, enabled = !state.busy)
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(displayTime(reading.timestamp, "Europe/Warsaw"), style = MaterialTheme.typography.titleSmall)
              Text("${formatEnergy(reading.importedRaw)} kWh")
              Text(
                stringResource(
                  when {
                    reading.timestamp < target -> R.string.reserve_history_before
                    reading.timestamp > target -> R.string.reserve_history_after
                    else -> R.string.reserve_history_exact
                  }
                ),
                style = MaterialTheme.typography.bodySmall
              )
              if (reading == nearest) Text(stringResource(R.string.reserve_history_nearest), color = MaterialTheme.colorScheme.primary)
            }
          }
        }
      }
    },
    confirmButton = {
      ReserveTextButton(onClick = { selected?.let(onSelected) }, enabled = !state.busy && selected != null) {
        Text(stringResource(R.string.reserve_history_use))
      }
    },
    dismissButton = { ReserveTextButton(onClick = onDismiss) { Text(stringResource(R.string.reserve_cancel)) } }
  )
}

@Composable
private fun ReserveDateInput(label: Int, date: String, enabled: Boolean, onChanged: (String) -> Unit) {
  var showPicker by remember { mutableStateOf(false) }
  val chooseDate = stringResource(R.string.reserve_choose_date, stringResource(label))
  OutlinedTextField(
    value = displayDate(date),
    onValueChange = {},
    label = { Text(stringResource(label), style = MaterialTheme.typography.bodySmall) },
    textStyle = MaterialTheme.typography.bodyMedium,
    shape = reserveFieldShape(),
    readOnly = true,
    singleLine = true,
    enabled = enabled,
    colors = reserveFieldColors(),
    trailingIcon = {
      IconButton(onClick = { showPicker = true }, enabled = enabled) {
        Icon(Icons.Default.DateRange, contentDescription = chooseDate)
      }
    },
    modifier = Modifier.fillMaxWidth().pointerInput(enabled) {
      if (enabled) {
        awaitEachGesture {
          awaitFirstDown(pass = PointerEventPass.Initial)
          if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) showPicker = true
        }
      }
    }.semantics {
      if (enabled) {
        onClick(label = chooseDate) {
          showPicker = true
          true
        }
      }
    }
  )
  if (showPicker) {
    val pickerState =
      rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
      onDismissRequest = { showPicker = false },
      confirmButton = {
        ReserveTextButton(
          onClick = {
            pickerState.selectedDateMillis?.let { onChanged(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
            showPicker = false
          },
          enabled = pickerState.selectedDateMillis != null
        ) { Text(stringResource(R.string.reserve_save)) }
      },
      dismissButton = { ReserveTextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.reserve_cancel)) } }
    ) { DatePicker(state = pickerState) }
  }
}

@Composable
private fun ReserveButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable RowScope.() -> Unit
) {
  Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
      disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ),
    content = content
  )
}

@Composable
private fun ReserveTextButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable RowScope.() -> Unit
) {
  TextButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.textButtonColors(
      contentColor = MaterialTheme.colorScheme.primary,
      disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ),
    content = content
  )
}

@Composable
private fun reserveFieldShape() = RoundedCornerShape(dimensionResource(R.dimen.radius_default))

@Composable
private fun reserveFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedTextColor = MaterialTheme.colorScheme.onSurface,
  unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
  disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
  focusedContainerColor = MaterialTheme.colorScheme.surface,
  unfocusedContainerColor = MaterialTheme.colorScheme.surface,
  disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
  focusedBorderColor = MaterialTheme.colorScheme.primary,
  unfocusedBorderColor = MaterialTheme.colorScheme.primary,
  disabledBorderColor = MaterialTheme.colorScheme.outline,
  focusedLabelColor = MaterialTheme.colorScheme.primary,
  unfocusedLabelColor = MaterialTheme.colorScheme.onSurface,
  cursorColor = MaterialTheme.colorScheme.primary
)

private fun formatPercentage(fraction: Float): String = NumberFormat.getPercentInstance().apply {
  minimumFractionDigits = 1
  maximumFractionDigits = 1
  roundingMode = RoundingMode.HALF_UP
}.format(fraction.coerceIn(0f, 1f).toDouble())

private fun formatHours(milliseconds: Long): String = formatNumber(milliseconds.toDouble() / ReserveHourly.HOUR_MS)

private fun formatEnergy(raw: Long): String = formatNumber(ReserveEnergy.kwh(raw))

private fun formatNumber(value: Number): String = NumberFormat.getNumberInstance().apply {
  minimumFractionDigits = 1
  maximumFractionDigits = 1
  roundingMode = RoundingMode.HALF_UP
}.format(value)

private fun startOfDate(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()

private fun displayDate(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
private fun displayTime(timestamp: Long, zone: String): String =
  DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(timestamp))

@Preview(name = "Magazyn — jasny", showBackground = true, widthDp = 360, heightDp = 1700)
@Preview(
  name = "Magazyn — ciemny",
  showBackground = true,
  widthDp = 360,
  heightDp = 1700,
  uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Preview(name = "Magazyn — wąski ekran", showBackground = true, widthDp = 320, heightDp = 1900, fontScale = 1.3f)
@PreviewFontScale
@Composable
private fun ReservePreview() {
  val value = reservePreviewValue()
  SuplaTheme {
    EnergyReserveContent(
      EnergyReserveState(
        context = ReserveContext(1, 42, "MEW-01", value),
        balance = ReserveCalculator.calculate(
          value,
          value.hourlyProgress!!.boundary.timestamp
        )
      )
    )
  }
}

private fun reservePreviewValue(): EnergyReserve {
  val baseline = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
  val initial = ReserveFlowReading(baseline, listOf(1000 * 100_000L, 0, 0), listOf(0, 0, 0))
  val latest = ReserveFlowReading(baseline + 2 * ReserveHourly.HOUR_MS, listOf(1050 * 100_000L, 0, 0), listOf(100 * 100_000L, 0, 0))
  return EnergyReserve(
    1, 42, 200 * 100_000L, "2026-05-01", "2027-04-30", "Europe/Warsaw",
    ReserveReading(baseline, initial.importedRaw), false,
    lastReading = ReserveReading(latest.timestamp, latest.importedRaw), hourlyEnabled = true,
    hourlyProgress = ReserveHourlyProgress(initial, latest, 50 * 100_000L, 100 * 100_000L, interpolatedBoundaries = 2)
  )
}

@Preview(name = "Edycja — jasny", showBackground = true, widthDp = 360, heightDp = 1700)
@Preview(
  name = "Edycja — ciemny",
  showBackground = true,
  widthDp = 360,
  heightDp = 1700,
  uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@PreviewFontScale
@Composable
private fun ReserveEditPreview() {
  ReserveEditPreview(manual = false)
}

@Preview(name = "Edycja — ręczna, wąski ekran", showBackground = true, widthDp = 320, heightDp = 1900, fontScale = 1.3f)
@Composable
private fun ReserveManualEditPreview() {
  ReserveEditPreview(manual = true)
}

@Composable
private fun ReserveEditPreview(manual: Boolean) {
  val value = reservePreviewValue()
  SuplaTheme {
    EnergyReserveContent(
      EnergyReserveState(
        context = ReserveContext(1, 42, "MEW-01", value),
        editing = true,
        draft = ReserveDraft(
          initialKwh = "200",
          startDate = value.startDate,
          validUntil = value.validUntil,
          manualBaseline = manual,
          baselineKwh = "1000",
          hourlyEnabled = !manual,
          currentReserveEnabled = !manual
        )
      )
    )
  }
}
