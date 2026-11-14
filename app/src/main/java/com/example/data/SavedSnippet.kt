package com.example.data

import java.util.UUID

data class SavedSnippet(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
