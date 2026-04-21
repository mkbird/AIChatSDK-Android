package com.aichatsdk.models

data class ChatError(
    val code: String,
    override val message: String,
    val turnId: String? = null,
    val fatal: Boolean = false,
) : RuntimeException("[$code] $message")
