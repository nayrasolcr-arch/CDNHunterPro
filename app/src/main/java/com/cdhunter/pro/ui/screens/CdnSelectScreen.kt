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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.db.ProviderStateEntity
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.components.Fmt
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CdnSelectVm(private val c: AppContainer) : ViewModel() {
    val selected = MutableStateFlow<List<String>>(emptyList())
    val states = c.database.providerStateDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val updating = MutableStateFlow(false)
    val summaries = MutableStateFlow<List<String>>(emptyList())

    init {
        viewModelScope.launch { selected.value = c.settings.selectedProviders() }
    }

    fun toggle(id: String) {
        selected.value = if (id in selected.value) selected.value - id else selected.value + id
    }

    fun save() {
        viewModelScope.launch { c.settings.setSelectedProviders(selected.value) }
    }

    fun updateSources() {
        viewModelScope.launch {
            updating.value = true
            save()
            val results = c.rangeRepository.updateProviders(selected.value.filter { it != "imported" })
            summaries.value = results.map { r ->
                if (r.ok) c.context.getString(R.string.fetch_ok, CdnRegistry.displayName(r.providerId), r.added)
                else c.context.getString(R.string.fetch_failed, r.message ?: "?")
            }
            updating.value = false
        }
    }
}

@Composable
fun CdnSelectScreen(container: AppContainer, nav: NavController) {
    val vm: CdnSelectVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { CdnSelectVm(container) },
    )
    val selected by vm.selected.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { Text(stringResource(R.string.select_cdns), style = MaterialTheme.typography.headlineMedium) }
        items(CdnRegistry.ALL) { def ->
            val state: ProviderStateEntity? = states.firstOrNull { it.providerId == def.id }
            SectionCard(title = def.displayName) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = def.id in selected,
                        onCheckedChange = { vm.toggle(def.id) },
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(def.id, style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(
                                R.string.last_updated,
                                state?.lastUpdatedMs?.let { Fmt.time(it) } ?: stringResource(R.string.never),
                            ) + " · ${state?.rangesCount ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state?.lastUpdateStatus?.let { status ->
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.startsWith("FAILED")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(def.notes, style = MaterialTheme.typography.bodySmall)
                Text(
                    def.endpoints.joinToString("\n") { "• ${it.label}: ${it.url}" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save(); nav.popBackStack() }) { Text(stringResource(R.string.save)) }
                Button(onClick = { vm.updateSources() }, enabled = !updating) {
                    Text(if (updating) stringResource(R.string.loading) else stringResource(R.string.update_now))
                }
            }
        }
        items(summaries) { s -> Text(s, style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
