package dev.local.record.domain

/** A bounded view of confirmed text facts. Unknown subjects and dates remain unknown. */
data class PersonalMemoryProfile(val preferences: List<MemoryItem>, val projects: List<MemoryItem>, val otherFacts: List<MemoryItem>) {
    val items get() = preferences + projects + otherFacts
}

fun personalMemoryProfile(memories: List<MemoryItem>): PersonalMemoryProfile {
    val facts = memories.filter { it.status == MemoryStatus.CONFIRMED && it.text.isNotBlank() }.sortedBy { it.id }
    return PersonalMemoryProfile(
        facts.filter { it.type in setOf(MemoryKind.PREFERENCE, MemoryKind.AGREEMENT) },
        facts.filter { it.type in setOf(MemoryKind.PROJECT, MemoryKind.TODO) },
        facts.filter { it.type in setOf(MemoryKind.PERSON, MemoryKind.IDEA) }
    )
}

/** Local Chinese bigram matching with a small preference profile; unrelated history stays out. */
fun selectPersonalMemoryContext(memories: List<MemoryItem>, question: String, maxCharacters: Int = 6_000, maxItems: Int = 24): List<MemoryItem> {
    require(maxCharacters >= 0 && maxItems >= 0)
    val profile = personalMemoryProfile(memories)
    val tokens = memoryTokens(question)
    val overview = listOf("了解我", "关于我", "对我有哪些了解", "我的记忆", "记得我").any(question::contains)
    val matches = profile.items.map { memory ->
        val searchable = memoryTokens(memory.text).toSet()
        val score = tokens.count { it in searchable } + if (question.contains(memory.type.label)) 2 else 0
        memory to score
    }.filter { it.second > 0 || overview }.sortedWith(compareByDescending<Pair<MemoryItem, Int>> { it.second }.thenBy { it.first.id }).map { it.first }
    val smallProfile = profile.preferences.take(8)
    var remaining = maxCharacters
    return (matches + smallProfile).distinctBy { it.id }.filter { item ->
        val size = item.text.length + 60
        (size <= remaining).also { accepted -> if (accepted) remaining -= size }
    }.take(maxItems)
}

/** Keep English words whole; only contiguous Han text produces overlapping bigrams. */
private fun memoryTokens(text: String): List<String> = Regex("[\\p{IsHan}]+|[a-z0-9_]+").findAll(text.lowercase()).flatMap { match ->
    val word = match.value
    if (word.first().code > 127) {
        if (word.length >= 2) word.windowed(2).asSequence() else sequenceOf(word)
    } else {
        sequenceOf(word)
    }
}.filter { it.length >= 2 }.distinct().toList()
