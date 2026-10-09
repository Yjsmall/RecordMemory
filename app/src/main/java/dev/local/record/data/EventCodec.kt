package dev.local.record.data

import kotlinx.serialization.json.Json

internal val eventJson = Json { classDiscriminator = "type" }
