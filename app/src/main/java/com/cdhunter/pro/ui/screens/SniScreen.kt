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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.db.SniResultEntity
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.components.EmptyState
import com.cdhunter.pro.ui.components.Fmt
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SniVm(private val c: AppContainer) : ViewModel() {
    val results = c.database.sniResultDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val providers = MutableStateFlow<List<String>>(emptyList())
    val selectedProvider = MutableStateFlow("cloudflare")
    val sniList = MutableStateFlow<List<String>>(emptyList())
    val newSni = MutableStateFlow("")
    val testing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            providers.value = c.settings.selectedProviders()
            loadSnis()
        }
    }

    fun selectProvider(id: String) {
        selectedProvider.value = id
        viewModelScope.launch { loadSnis() }
    }

    private suspend fun loadSnis() {
        val user = c.settings.sniFor(selectedProvider.value)
        val defaults = CdnRegistry.byId(selectedProvider.value)?.defaultTestSnis ?: emptyList()
        sniList.value = (user + defaults).distinct()
    }

    fun addSni() {
        val sni = newSni.value.trim().lowercase()
        if (!com.cdhunter.pro.core.util.HostnameValidator.isValid(sni)) {
            message.value = c.context.getString(R.string.sni_invalid)
            return
        }
        viewModelScope.launch {
            val current = c.settings.sniFor(selectedProvider.value)
            c.settings.setSniFor(selectedProvider.value, (current + sni).distinct())
            newSni.value = ""
            message.value = null
            loadSnis()
        }
    }

    fun removeSni(sni: String) {
        viewModelScope.launch {
            c.settings.setSniFor(selectedProvider.value, c.settings.sniFor(selectedProvider.value) - sni)
            loadSnis()
        }
    }

    fun testCombos() {
        viewModelScope.launch {
            testing.value = true
            try {
                // Use reachable IPs from the latest scan for this provider.
                val latest = c.database.scanDao().recentOnce().firstOrNull()
                val latestResults = latest?.let { c.resultRepository.forScan(it.id) } ?: emptyList()
                val providerIps = latestResults
                    .filter { it.providerId == selectedProvider.value && it.tcpOk }
                    .map { it.ip }
                    .take(12)
                val combos = c.sniEngine.testCombinations(
                    ips = providerIps,
                    providerId = selectedProvider.value,
                    snis = sniList.value,
                    timeoutMs = c.settings.timeoutMs(),
                )
                message.value = c.context.getString(R.string.export_done, combos.size) +
                    (combos.firstOrNull { it.ok }?.let { " · ${it.sni}" } ?: "")
            } finally {
                testing.value = false
            }
        }
    }
}

@Composable
fun SniScreen(container: AppContainer, nav: NavController) {
    val vm: SniVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { SniVm(container) },
    )
    val results by vm.results.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val selected by vm.selectedProvider.collectAsStateWithLifecycle()
    val sniList by vm.sniList.collectAsStateWithLifecycle()
    val newSni by vm.newSni.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { Text(stringResource(R.string.sni_title), style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                stringResource(R.string.sni_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                providers.forEach { pid ->
                    FilterChip(
                        selected = selected == pid,
                        onClick = { vm.selectProvider(pid) },
                        label = { Text(CdnRegistry.displayName(pid)) },
                    )
                }
            }
        }
        item {
            SectionCard(title = stringResource(R.string.sni_list, CdnRegistry.displayName(selected))) {
                sniList.forEach { sni ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(sni, style = MaterialTheme.typography.bodyMedium)
                        if (container.settings.sniFor(selected).contains(sni)) {
                            OutlinedButton(onClick = { vm.removeSni(sni) }) {
                                Text(stringResource(R.string.remove))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = newSni,
                    onValueChange = { vm.newSni.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.sni_hint)) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.addSni() }) { Text(stringResource(R.string.add)) }
                    Button(onClick = { vm.testCombos() }, enabled = !testing) {
                        Text(if (testing) stringResource(R.string.loading) else stringResource(R.string.test_sni))
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { Text(stringResource(R.string.sni_results), style = MaterialTheme.typography.titleMedium) }
        if (results.isEmpty()) {
            item { EmptyState(stringResource(R.string.sni_empty)) }
        } else {
            items(results.take(50)) { s ->
                SniRow(s)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SniRow(s: SniResultEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${s.ip} · ${s.sni}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (s.ok) stringResource(R.string.sni_ok) else stringResource(R.string.sni_fail),
                    color = if (s.ok) androidx.compose.ui.graphics.Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                (s.tlsVersion ?: "–") + " · " + Fmt.ms(s.handshakeMs) + " · " + Fmt.time(s.testedAtMs),
                style = MaterialTheme.typography.bodySmall,
            )
            if (java.lang.System.currentTimeMillis() > s.expiresAtMs) {
                Text(stringResource(R.string.sni_expired), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
