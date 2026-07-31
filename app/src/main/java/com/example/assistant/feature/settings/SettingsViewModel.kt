package com.example.assistant.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.agent.Agent
import com.example.assistant.core.network.Capability
import com.example.assistant.core.network.ProviderProfile
import com.example.assistant.core.network.ProviderRegistry
import com.example.assistant.core.storage.PromptStore
import com.example.assistant.core.storage.SecretStore
import com.example.assistant.core.storage.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val secretStore: SecretStore,
    private val settingsStore: SettingsStore,
    private val promptStore: PromptStore,
    private val registry: ProviderRegistry,
    private val agent: Agent
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ProviderProfile>>(emptyList())
    val profiles: StateFlow<List<ProviderProfile>> = _profiles

    /** capability -> 指派档案 id（null = 使用默认档案） */
    private val _assignments = MutableStateFlow<Map<Capability, String?>>(emptyMap())
    val assignments: StateFlow<Map<Capability, String?>> = _assignments

    /** 测试连接的结果 */
    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult

    init {
        refresh()
    }

    fun refresh() {
        _profiles.value = secretStore.loadProfiles()
        viewModelScope.launch {
            _assignments.value = Capability.entries.associateWith { cap ->
                settingsStore.currentProfileIdFor(cap).ifBlank { null }
            }
        }
    }

    /** 保存档案（新增或更新）；设为默认时清除其他默认 */
    fun saveProfile(profile: ProviderProfile) {
        var list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        if (profile.isDefault) {
            list = list.map { if (it.id == profile.id) it else it.copy(isDefault = false) }.toMutableList()
        }
        if (list.isNotEmpty() && list.none { it.isDefault }) {
            list[0] = list[0].copy(isDefault = true)
        }
        secretStore.saveProfiles(list)
        registry.invalidate()
        refresh()
    }

    fun deleteProfile(id: String) {
        var list = _profiles.value.filterNot { it.id == id }.toMutableList()
        if (list.isNotEmpty() && list.none { it.isDefault }) {
            list[0] = list[0].copy(isDefault = true)
        }
        secretStore.saveProfiles(list)
        registry.invalidate()
        refresh()
    }

    fun assignCapability(capability: Capability, profileId: String?) {
        viewModelScope.launch {
            settingsStore.setProfileIdFor(capability, profileId ?: "")
            registry.invalidate()
            refresh()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = TestResult.Testing
            _testResult.value = agent.testConnection().fold(
                onSuccess = { TestResult.Success(it) },
                onFailure = { TestResult.Failure(it.message ?: "连接失败") }
            )
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    sealed interface TestResult {
        data object Testing : TestResult
        data class Success(val reply: String) : TestResult
        data class Failure(val message: String) : TestResult
    }
}
