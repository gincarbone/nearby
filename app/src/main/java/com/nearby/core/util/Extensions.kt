package com.nearby.core.util

import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

fun generateUUID(): String = UUID.randomUUID().toString()

fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

fun Long.toFormattedTime(): String {
    val date = Date(this)
    val now = System.currentTimeMillis()
    val diff = now - this

    return when {
        diff < 60_000 -> "now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        diff < 604800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(date)
    }
}

fun Long.toMessageTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this))
}

fun Long.toFullDateTime(): String {
    return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(this))
}
