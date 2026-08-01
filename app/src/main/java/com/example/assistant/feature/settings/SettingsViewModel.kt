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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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

    /** 每日小结自动总结时间（分钟数，默认 21:00=1260，可精确到分钟） */
    val summaryMinute: StateFlow<Int> = settingsStore.dailySummaryMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 21 * 60)

    fun setSummaryMinute(minute: Int) {
        viewModelScope.launch { settingsStore.setDailySummaryMinute(minute) }
    }

    // ---- 搜索（Tavily）配置：空 key = keyless 免费模式 ----
    val searchApiKey = MutableStateFlow(secretStore.searchApiKey())

    fun saveSearchApiKey(key: String) {
        secretStore.saveSearchApiKey(key)
        searchApiKey.value = key
    }

    // ---- 清晨简报 ----
    val briefingMinute: StateFlow<Int> = settingsStore.briefingMinuteOfDay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7 * 60 + 30)

    fun setBriefingMinute(minutes: Int) {
        viewModelScope.launch { settingsStore.setBriefingMinuteOfDay(minutes) }
    }

    // ---- 免打扰时段（分钟数；起止相同 = 未启用） ----
    val quietStartMinute: StateFlow<Int> = settingsStore.quietStartMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 23 * 60)

    val quietEndMinute: StateFlow<Int> = settingsStore.quietEndMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7 * 60)

    fun setQuietWindow(startMinute: Int, endMinute: Int) {
        viewModelScope.launch { settingsStore.setQuietWindow(startMinute, endMinute) }
    }

    // ---- 高级设置：思考开关与深度 ----
    val thinkingMode: StateFlow<String> = settingsStore.thinkingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    val reasoningEffort: StateFlow<String> = settingsStore.reasoningEffort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    fun setThinkingMode(v: String) {
        viewModelScope.launch { settingsStore.setThinkingMode(v) }
    }

    fun setReasoningEffort(v: String) {
        viewModelScope.launch { settingsStore.setReasoningEffort(v) }
    }

    // ---- P6：悬浮球 ----
    val floatingBallEnabled: StateFlow<Boolean> = settingsStore.floatingBallEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setFloatingBallEnabled(v: Boolean) {
        viewModelScope.launch { settingsStore.setFloatingBallEnabled(v) }
    }

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
