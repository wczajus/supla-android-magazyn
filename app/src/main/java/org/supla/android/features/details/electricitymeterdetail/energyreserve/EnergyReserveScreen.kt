/* SPDX-License-Identifier: GPL-2.0-or-later */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.supla.android.R
import org.supla.android.core.ui.theme.SuplaTheme
import org.supla.android.features.details.detailbase.base.ItemBundle
import org.supla.android.main.ViewModelHost
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
        save = viewModel::save,
        cancel = viewModel::cancelEdit,
        delete = viewModel::delete,
        draftChanged = viewModel::updateDraft,
        retry = { viewModel.load(item.remoteId) }
      )
    )
  }
}

private data class ReserveActions(
  val edit: () -> Unit = {},
  val refresh: () -> Unit = {},
  val save: () -> Unit = {},
  val cancel: () -> Unit = {},
  val delete: () -> Unit = {},
  val draftChanged: (ReserveDraft) -> Unit = {},
  val retry: () -> Unit = {}
)

@Composable
private fun EnergyReserveContent(state: EnergyReserveState, actions: ReserveActions = ReserveActions()) {
  var confirmDelete by remember { mutableStateOf(false) }
  Column(
    Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text(stringResource(R.string.reserve_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.reserve_description), style = MaterialTheme.typography.bodyMedium)
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
      }
      if (state.context == null) {
        OutlinedButton(onClick = actions.retry, enabled = !state.busy) { Text(stringResource(R.string.reserve_refresh)) }
      }
    }
    if (state.editing) {
      ReserveForm(state, actions)
    } else {
      val reserve = state.context?.reserve
      if (reserve != null && state.balance != null) {
        ReserveOverview(reserve, state.balance)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedButton(onClick = actions.refresh, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.reserve_refresh))
          }
          OutlinedButton(onClick = actions.edit, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.reserve_edit))
          }
        }
      } else if (state.context != null && !state.busy) {
        Card(Modifier.fillMaxWidth()) {
          Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            ReserveBattery(0f, configured = false)
            Text(stringResource(R.string.reserve_empty), style = MaterialTheme.typography.titleMedium)
            Button(onClick = actions.edit) { Text(stringResource(R.string.reserve_configure)) }
          }
        }
      }
      if (reserve != null || state.error == R.string.reserve_error_storage) {
        TextButton(onClick = { confirmDelete = true }, enabled = !state.busy) {
          Text(stringResource(R.string.reserve_delete), color = MaterialTheme.colorScheme.error)
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
        TextButton(onClick = {
          confirmDelete = false
          actions.delete()
        }) { Text(stringResource(R.string.reserve_delete)) }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.reserve_cancel)) } }
    )
  }
}

@Composable
private fun ReserveOverview(reserve: EnergyReserve, balance: ReserveBalance) {
  val available =
    balance.status == ReserveStatus.ACTIVE || balance.status == ReserveStatus.EXHAUSTED || balance.status == ReserveStatus.EXPIRED
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
  Card(
    Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    Column(
      Modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      ReserveBattery(fraction)
      Text(stringResource(statusRes), style = MaterialTheme.typography.titleMedium)
      if (available) {
        Text(
          stringResource(R.string.reserve_remaining),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("${formatEnergy(balance.remainingRaw)} kWh", style = MaterialTheme.typography.headlineLarge)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
      }
      Text(stringResource(R.string.reserve_valid_until, displayDate(reserve.validUntil)), style = MaterialTheme.typography.bodyMedium)
    }
  }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ReserveMetric(R.string.reserve_initial, reserve.initialRaw)
      if (available) {
        ReserveMetric(R.string.reserve_used, balance.usedRaw)
        ReserveMetric(R.string.reserve_imported, balance.consumedRaw)
        if (balance.excessRaw > 0) ReserveMetric(R.string.reserve_excess, balance.excessRaw)
        if (balance.status == ReserveStatus.EXPIRED) ReserveMetric(R.string.reserve_expired_amount, balance.expiredRaw)
      }
      HorizontalDivider()
      Text(
        stringResource(if (reserve.manualBaseline) R.string.reserve_manual_source else R.string.reserve_history_source),
        style = MaterialTheme.typography.labelLarge
      )
      Text(
        stringResource(
          R.string.reserve_baseline_details,
          formatEnergy(reserve.baseline.importedRaw),
          displayTime(reserve.baseline.timestamp, reserve.zoneId)
        ),
        style = MaterialTheme.typography.bodySmall
      )
      reserve.lastReading?.let {
        Text(
          stringResource(R.string.reserve_last_reading, displayTime(it.timestamp, reserve.zoneId)),
          style = MaterialTheme.typography.bodySmall
        )
      }
    }
  }
  if (reserve.baseline.timestamp != reserve.startsAt) {
    Text(
      stringResource(R.string.reserve_approximate),
      color = MaterialTheme.colorScheme.tertiary,
      style = MaterialTheme.typography.bodySmall
    )
  }
  val lastTimestamp = reserve.lastReading?.timestamp
  if (balance.status == ReserveStatus.EXPIRED && lastTimestamp != null && lastTimestamp < reserve.expiresAt) {
    Text(
      stringResource(R.string.reserve_closing_approximate),
      color = MaterialTheme.colorScheme.tertiary,
      style = MaterialTheme.typography.bodySmall
    )
  } else if (lastTimestamp != null && balance.status == ReserveStatus.ACTIVE && System.currentTimeMillis() - lastTimestamp > 900_000) {
    Text(stringResource(R.string.reserve_stale), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun ReserveMetric(label: Int, raw: Long) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text("${formatEnergy(raw)} kWh", style = MaterialTheme.typography.titleSmall)
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
  Canvas(Modifier.size(168.dp, 84.dp).semantics { contentDescription = description }) {
    val stroke = 2.dp.toPx()
    val bodyWidth = size.width - 12.dp.toPx()
    val radius = CornerRadius(12.dp.toPx())
    drawRoundRect(outline, Offset(stroke, stroke), Size(bodyWidth - stroke * 2, size.height - stroke * 2), radius, style = Stroke(stroke))
    val inset = 8.dp.toPx()
    val fillWidth = bodyWidth - inset * 2
    val fillHeight = size.height - inset * 2
    drawRoundRect(background, Offset(inset, inset), Size(fillWidth, fillHeight), CornerRadius(6.dp.toPx()))
    if (fraction > 0f) drawRoundRect(color, Offset(inset, inset), Size(fillWidth * fraction, fillHeight), CornerRadius(6.dp.toPx()))
    drawRoundRect(
      outline,
      Offset(bodyWidth + 3.dp.toPx(), size.height * 0.32f),
      Size(8.dp.toPx(), size.height * 0.36f),
      CornerRadius(3.dp.toPx())
    )
  }
}

@Composable
private fun ReserveForm(state: EnergyReserveState, actions: ReserveActions) {
  val draft = state.draft
  val enabled = !state.busy
  if (state.context?.reserve != null) Text(stringResource(R.string.reserve_edit_help), style = MaterialTheme.typography.bodySmall)
  OutlinedTextField(
    value = draft.initialKwh,
    onValueChange = { actions.draftChanged(draft.copy(initialKwh = it)) },
    label = { Text(stringResource(R.string.reserve_amount_label)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    singleLine = true,
    enabled = enabled,
    modifier = Modifier.fillMaxWidth()
  )
  ReserveDateInput(R.string.reserve_start_label, draft.startDate, enabled) { actions.draftChanged(draft.copy(startDate = it)) }
  ReserveDateInput(R.string.reserve_end_label, draft.validUntil, enabled) { actions.draftChanged(draft.copy(validUntil = it)) }
  Text(stringResource(R.string.reserve_timezone), style = MaterialTheme.typography.bodySmall)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Switch(checked = draft.manualBaseline, onCheckedChange = { actions.draftChanged(draft.copy(manualBaseline = it)) }, enabled = enabled)
    Text(
      stringResource(R.string.reserve_manual_label),
      Modifier.padding(start = 12.dp).weight(1f),
      style = MaterialTheme.typography.bodyMedium
    )
  }
  if (draft.manualBaseline) {
    OutlinedTextField(
      value = draft.baselineKwh,
      onValueChange = { actions.draftChanged(draft.copy(baselineKwh = it)) },
      label = { Text(stringResource(R.string.reserve_baseline_label)) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      singleLine = true,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth()
    )
    Text(stringResource(R.string.reserve_baseline_help), style = MaterialTheme.typography.bodySmall)
  } else {
    Text(stringResource(R.string.reserve_history_help), style = MaterialTheme.typography.bodySmall)
  }
  Button(onClick = actions.save, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reserve_save)) }
  TextButton(onClick = actions.cancel, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.reserve_cancel))
  }
}

@Composable
private fun ReserveDateInput(label: Int, date: String, enabled: Boolean, onChanged: (String) -> Unit) {
  var showPicker by remember { mutableStateOf(false) }
  OutlinedButton(onClick = { showPicker = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
      Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
      Text(displayDate(date), style = MaterialTheme.typography.bodyLarge)
    }
  }
  if (showPicker) {
    val pickerState =
      rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
      onDismissRequest = { showPicker = false },
      confirmButton = {
        TextButton(onClick = {
          pickerState.selectedDateMillis?.let { onChanged(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
          showPicker = false
        }) { Text(stringResource(R.string.reserve_save)) }
      },
      dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.reserve_cancel)) } }
    ) { DatePicker(state = pickerState) }
  }
}

private fun formatEnergy(raw: Long): String = NumberFormat.getNumberInstance().apply {
  maximumFractionDigits = 3
}.format(ReserveEnergy.kwh(raw))

private fun displayDate(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
private fun displayTime(timestamp: Long, zone: String): String =
  DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(timestamp))

@Preview(name = "Magazyn — jasny", showBackground = true)
@Preview(name = "Magazyn — ciemny", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReservePreview() {
  val baseline = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli()
  val value =
    EnergyReserve(
      1, 42, 200 * 100_000L, "2026-05-01", "2027-04-30", "Europe/Warsaw",
      ReserveReading(
        baseline,
        1000 * 100_000L
      ),
      true, ReserveReading(baseline + 86_400_000, 1050 * 100_000L)
    )
  SuplaTheme {
    EnergyReserveContent(
      EnergyReserveState(
        context = ReserveContext(1, 42, "MEW-01", value),
        balance = ReserveCalculator.calculate(
          value,
          baseline + 86_400_000
        )
      )
    )
  }
}
