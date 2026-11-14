package me.rerere.rikkahub.ui.pages.extensions.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.automation.ScheduleKind
import me.rerere.rikkahub.data.automation.TaskSchedule
import me.rerere.rikkahub.ui.components.automation.AutomationSettingRow
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ConnectedMultiChoiceButtons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private enum class ScheduleDate { ONCE, START, END }

@Composable
internal fun AutomationScheduleEditor(schedule: TaskSchedule, interval: String?, onIntervalChange: (String) -> Unit, onChange: (TaskSchedule) -> Unit) {
    var datePicker by rememberSaveable { mutableStateOf<ScheduleDate?>(null) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var editingTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDateBounds by rememberSaveable { mutableStateOf(false) }
    val once = schedule.kind == ScheduleKind.ONCE
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(schedule.onceTimes.first()).atZone(zone).toLocalDate()
    CardGroup(title = { Text("时间安排") }) {
        formItem {
            ConnectedChoiceButtons(options = ScheduleKind.entries, selected = schedule.kind, onSelect = { kind ->
                onChange(if (kind == ScheduleKind.ONCE && schedule.onceTimes.none { it > 0 }) {
                    schedule.copy(kind = kind, at = System.currentTimeMillis() + 3_600_000, atTimes = emptyList())
                } else schedule.copy(kind = kind))
            }) { kind, selected ->
                ConnectedChoiceLabel(when (kind) {
                    ScheduleKind.ONCE -> "指定日期"
                    ScheduleKind.DAILY -> "每天"
                    ScheduleKind.WEEKLY -> "每周"
                    ScheduleKind.INTERVAL -> "间隔"
                }, selected)
            }
            if (schedule.kind == ScheduleKind.INTERVAL) {
                OutlinedTextField(
                    value = interval ?: schedule.intervalMinutes.toString(), onValueChange = onIntervalChange,
                    label = { Text("间隔（分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = (interval ?: schedule.intervalMinutes.toString()).toIntOrNull()?.let { it >= 15 } != true,
                    singleLine = true, supportingText = { Text("至少 15 分钟") }, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                if (once) AutomationSettingRow("执行日期", date.toString(), { datePicker = ScheduleDate.ONCE })
                if (schedule.kind == ScheduleKind.WEEKLY) ConnectedMultiChoiceButtons(
                    options = (1..7).toList(), selected = schedule.weekdays,
                    onToggle = { day, checked -> onChange(schedule.copy(weekdays = if (checked) schedule.weekdays + day else schedule.weekdays - day)) },
                    minHeight = 40.dp,
                ) { day, selected -> ConnectedChoiceLabel("一二三四五六日"[day - 1].toString(), selected) }
                val times = if (once) schedule.onceTimes else schedule.recurringTimes.map(Int::toLong)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    times.forEach { value ->
                        val local = if (once) Instant.ofEpochMilli(value).atZone(zone) else null
                        val minutes = local?.let { it.hour * 60 + it.minute } ?: value.toInt()
                        val time = "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
                        val label = if (local != null && local.toLocalDate() != date) "${local.toLocalDate()} $time" else time
                        InputChip(selected = false, onClick = { editingTime = value; showTime = true }, label = { Text(label) },
                            trailingIcon = if (times.size > 1) { {
                                IconButton(onClick = {
                                    val remaining = times.filterNot { it == value }
                                    onChange(if (once) schedule.withOnceTimes(remaining) else schedule.withRecurringTimes(remaining.map(Long::toInt)))
                                }, modifier = Modifier.size(24.dp)) { Icon(HugeIcons.Cancel01, "删除执行时间 $label", modifier = Modifier.size(16.dp)) }
                            } } else null)
                    }
                    TextButton(onClick = { editingTime = null; showTime = true }) {
                        Icon(HugeIcons.Add01, null, modifier = Modifier.size(18.dp))
                        Text("添加时间", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Text(if (once) "所选日期的每个时间各执行一次" else "${if (schedule.kind == ScheduleKind.WEEKLY) "选中星期" else "每天"}的每个时间都会执行",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (schedule.kind in setOf(ScheduleKind.DAILY, ScheduleKind.WEEKLY)) {
                if (showDateBounds || schedule.startDate != null || schedule.endDate != null) Column {
                    AutomationSettingRow("开始日期", schedule.startDate ?: "不限", { datePicker = ScheduleDate.START },
                        onClear = if (schedule.startDate != null) { { onChange(schedule.copy(startDate = null)) } } else null)
                    AutomationSettingRow("结束日期", schedule.endDate ?: "不限", { datePicker = ScheduleDate.END },
                        onClear = if (schedule.endDate != null) { { onChange(schedule.copy(endDate = null)) } } else null)
                } else TextButton(onClick = { showDateBounds = true }) { Text("设置日期范围") }
            }
        }
    }
    datePicker?.let { field ->
        val initial = when (field) {
            ScheduleDate.ONCE -> date
            ScheduleDate.START -> schedule.startDate?.let(LocalDate::parse)
            ScheduleDate.END -> schedule.endDate?.let(LocalDate::parse)
        } ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { datePicker = null }, confirmButton = { TextButton(onClick = {
            state.selectedDateMillis?.let { selected ->
                val selectedDate = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                onChange(when (field) {
                    ScheduleDate.START -> schedule.copy(startDate = selectedDate.toString())
                    ScheduleDate.END -> schedule.copy(endDate = selectedDate.toString())
                    ScheduleDate.ONCE -> schedule.withOnceTimes(schedule.onceTimes.map {
                        selectedDate.atTime(Instant.ofEpochMilli(it).atZone(zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                    })
                })
            }
            datePicker = null
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { datePicker = null }) { Text("取消") } }) { DatePicker(state) }
    }
    if (showTime) {
        val local = Instant.ofEpochMilli(if (once) editingTime ?: schedule.onceTimes.first() else schedule.at).atZone(zone)
        val last = if (once) Instant.ofEpochMilli(schedule.onceTimes.last()).atZone(zone).let { it.hour * 60 + it.minute } else schedule.recurringTimes.last()
        val minutes = editingTime?.let { if (once) local.hour * 60 + local.minute else it.toInt() } ?: (last + 60) % 1440
        val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
        val selectedMinutes = state.hour * 60 + state.minute
        val selectedDateTime = local.toLocalDate().atTime(state.hour, state.minute).atZone(zone)
        val duplicate = if (once) schedule.onceTimes.any { it != editingTime &&
            Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().withSecond(0).withNano(0) == selectedDateTime.toLocalDateTime() }
            else schedule.recurringTimes.any { it.toLong() != editingTime && it == selectedMinutes }
        AlertDialog(onDismissRequest = { showTime = false }, confirmButton = { TextButton(enabled = !duplicate, onClick = {
            onChange(if (once) schedule.withOnceTimes(schedule.onceTimes.filterNot { it == editingTime } + selectedDateTime.toInstant().toEpochMilli())
                else schedule.withRecurringTimes(schedule.recurringTimes.filterNot { it.toLong() == editingTime } + selectedMinutes))
            showTime = false
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePicker(state)
                if (duplicate) Text("该时间已存在", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        })
    }
}
