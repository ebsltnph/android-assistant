package com.example.assistant.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.core.storage.SummaryStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** 首页 ViewModel：最新清晨简报（随时可看） */
class HomeViewModel(private val summaryStore: SummaryStore) : ViewModel() {

    val latestBriefing: StateFlow<String?> = summaryStore.latestBriefing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val latestBriefingDate: StateFlow<String?> = summaryStore.latestBriefingDate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
