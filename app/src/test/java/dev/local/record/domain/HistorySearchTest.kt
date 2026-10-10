package dev.local.record.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySearchTest {
    @Test fun exactQuerySnippetContainsPhraseEvenWithEarlierPartialTokens() {
        val source = HistoryHit("source", "t", "USER_MESSAGE", "我喜欢茶" + "天气".repeat(300) + "我喜欢咖啡", 1, "UTC")
        assertTrue(selectHistoryHits(listOf(source), HistoryQuery("我喜欢咖啡")).single().text.contains("我喜欢咖啡"))
    }

    @Test fun exactCoffeeQueryRanksBeforeNewerPartialTeaMatch() {
        val sources = listOf(
            HistoryHit("coffee", "t1", "USER_MESSAGE", "我喜欢咖啡", 1, "UTC"),
            HistoryHit("tea", "t2", "USER_MESSAGE", "我喜欢茶", 2, "UTC")
        )
        assertEquals(listOf("coffee", "tea"), selectHistoryHits(sources, HistoryQuery("我喜欢咖啡")).map { it.contentId })
    }

    @Test fun chineseBigramsAndLatinWordsMatchDeterministically() {
        val sources = listOf(
            HistoryHit("one", "t", "USER_MESSAGE", "中文项目与Kotlin笔记", 1, "UTC"),
            HistoryHit("two", "t2", "USER_MESSAGE", "天气记录", 2, "UTC")
        )
        assertEquals(listOf("one"), selectHistoryHits(sources, HistoryQuery("项目进度")).map { it.contentId })
        assertEquals(listOf("one"), selectHistoryHits(sources, HistoryQuery("KOTLIN")).map { it.contentId })
        assertTrue(selectHistoryHits(sources, HistoryQuery("!!!")).isEmpty())
    }

    @Test fun dateBoundariesUseSourceZoneAndEmptyKeywordsAllowBrowsing() {
        val at = Instant.parse("2026-10-09T16:00:00Z").toEpochMilli()
        val sources = listOf(
            HistoryHit("shanghai", "r1", "USER_TRANSCRIPT", "边界", at, "Asia/Shanghai"),
            HistoryHit("utc", "r2", "USER_TRANSCRIPT", "同一时刻", at, "UTC")
        )
        assertEquals(listOf("shanghai"), selectHistoryHits(sources, HistoryQuery(fromDate = "2026-10-10", throughDate = "2026-10-10")).map { it.contentId })
        assertEquals(2, selectHistoryHits(sources, HistoryQuery()).size)
    }

    @Test fun invalidOrReversedDatesAreRejectedInsteadOfBroadeningSearch() {
        listOf(
            HistoryQuery(fromDate = "2026-02-30"),
            HistoryQuery(fromDate = "2026/10/10"),
            HistoryQuery(fromDate = "2026-10-11", throughDate = "2026-10-10")
        ).forEach { query -> assertTrue(runCatching { selectHistoryHits(emptyList(), query) }.isFailure) }
    }
}
