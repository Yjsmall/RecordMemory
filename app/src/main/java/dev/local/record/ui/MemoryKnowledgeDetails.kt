package dev.local.record.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryPredicate

@Composable
internal fun MemoryKnowledgeDetails(memory: MemoryItem, memories: List<MemoryItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        memory.fact?.let { fact ->
            val predicate = MemoryPredicate.entries.firstOrNull { it.key == fact.predicate }?.label.orEmpty()
            Text(listOf(if (fact.subject == "self") "我" else fact.subject, predicate, fact.scope).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fact.validFrom != null || fact.validUntil != null) Text("生效：${fact.validFrom ?: "未知"} · 截止：${fact.validUntil?.let { "$it（当天起不生效）" } ?: "未指定"}", style = MaterialTheme.typography.bodySmall)
            if (fact.sources.size > 1) Text("${fact.sources.size} 个来源支持", style = MaterialTheme.typography.labelSmall)
        }
        memory.change?.targetId?.let { id ->
            val target = memories.firstOrNull { it.id == id }
            Text("旧记忆：${target?.text?.takeIf { it.isNotBlank() } ?: "已失效"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (target == null || target.version != memory.change.expectedVersion) Text("旧记忆已变化，请重新整理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        memory.change?.question?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}
