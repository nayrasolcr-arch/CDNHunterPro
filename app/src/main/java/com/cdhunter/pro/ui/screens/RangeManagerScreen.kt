package com.cdhunter.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RangeManagerVm(private val c: AppContainer) : ViewModel() {
    val ranges = c.database.ipRangeDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val importedCandidates = MutableStateFlow(0)

    val importedCount = importedCandidates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { importedCandidates.value = c.candidateRepository.countImported() }
    }

    fun importCidr(text: String) {
        viewModelScope.launch {
            val s = c.rangeRepository.importCidrs(text)
            message.value = c.context.getString(
                R.string.fetch_ok, "CIDR", s.added,
            ) + (if (s.invalid > 0) " · ${c.context.getString(R.string.invalid_entries_skipped, s.invalid)}" else "") +
                (if (s.duplicates > 0) " · ${c.context.getString(R.string.duplicate_removed, s.duplicates)}" else "")
            refresh()
        }
    }

    fun clearImported() {
        viewModelScope.launch {
            c.rangeRepository.clearImportedRanges()
            refresh()
        }
    }
}

@Composable
fun RangeManagerScreen(container: AppContainer, nav: NavController) {
    val vm: RangeManagerVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { RangeManagerVm(container) },
    )
    val ranges by vm.ranges.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val importedCount by vm.importedCount.collectAsStateWithLifecycle()
    var importText by remember { mutableStateOf("") }
    var showClear by remember { mutableStateOf(false) }

    val byProvider = ranges.groupBy { it.providerId ?: CdnRegistry.IMPORTED_ID }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { Text(stringResource(R.string.ranges_screen_title), style = MaterialTheme.typography.headlineMedium) }
        items(byProvider.entries.toList()) { (provider, list) ->
            SectionCard(title = CdnRegistry.displayName(provider)) {
                Text(stringResource(R.string.ranges_count, list.size))
                list.take(6).forEach { r ->
                    Text("• ${r.cidr}", style = MaterialTheme.typography.bodySmall)
                }
                if (list.size > 6) Text("… +${list.size - 6}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard(title = stringResource(R.string.import_hint_cidr)) {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("1.2.3.0/24\n5.6.7.0/24") },
                    minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.importCidr(importText); importText = "" },
                        enabled = importText.isNotBlank(),
                    ) { Text(stringResource(R.string.add)) }
                    OutlinedButton(onClick = { showClear = true }) {
                        Text(stringResource(R.string.clear_candidates))
                    }
                }
                Text(
                    stringResource(R.string.imported_candidates) + ": $importedCount",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        message?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text(stringResource(R.string.confirm)) },
            text = { Text(stringResource(R.string.clear_candidates)) },
            confirmButton = {
                TextButton(onClick = { vm.clearImported(); showClear = false }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) { Text(stringResource(R.string.no)) }
            },
        )
    }
}
