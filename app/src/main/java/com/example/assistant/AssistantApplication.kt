package com.example.assistant

import android.app.Application
import com.example.assistant.di.AppContainer
import kotlinx.coroutines.runBlocking

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 首次启动：确保种子数据（生活/工作日记本）。
        // runBlocking 只在进程启动时执行一次（Room 查询在后台线程执行，主线程短暂等待，可接受）。
        runBlocking {
            container.diaryRepository.ensureSeedBooks()
        }
    }
}
