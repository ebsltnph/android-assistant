package com.example.assistant.core.storage

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧版前缀快照没有 version 字段，必须按 1 解码并被判定为过期——
 * 否则升级后可能继续用「最旧 50 条记忆」的旧文本，新记忆仍进不了模型上下文。
 */
class PrefixSnapshotVersionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `old snapshot without version decodes as version 1`() {
        val old = json.decodeFromString<PrefixSnapshot>(
            """{"memoryText":"旧记忆","bufferText":"","savedAt":1}"""
        )
        assertEquals(1, old.version)
        assertTrue(old.version < PrefixSnapshot.CURRENT_VERSION)
    }

    @Test
    fun `new snapshot is encoded with current version`() {
        val encoded = json.encodeToString(
            PrefixSnapshot.serializer(),
            PrefixSnapshot(version = PrefixSnapshot.CURRENT_VERSION, memoryText = "新记忆")
        )
        assertTrue(encoded.contains("\"version\":" + PrefixSnapshot.CURRENT_VERSION))
    }
}
