package com.nearby.domain.model

import java.security.PublicKey

data class User(
    val id: String,
    val displayName: String,
    val publicKey: PublicKey,
    val createdAt: Long
)
