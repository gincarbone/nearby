package com.nearby.core.util

object Constants {
    const val SERVICE_ID = "com.nearby.app"
    const val NEARBY_ADVERTISING_NAME_PREFIX = "NearBy|"

    const val PAYLOAD_TYPE_HANDSHAKE = 1
    const val PAYLOAD_TYPE_MESSAGE = 2
    const val PAYLOAD_TYPE_DELIVERY_RECEIPT = 3
    const val PAYLOAD_TYPE_READ_RECEIPT = 4

    const val MAX_RECONNECT_ATTEMPTS = 3
    const val DISCOVERY_TIMEOUT_MS = 30_000L
    const val CONNECTION_TIMEOUT_MS = 10_000L

    const val DATASTORE_NAME = "nearby_preferences"
    const val KEY_USER_ID = "user_id"
    const val KEY_DISPLAY_NAME = "display_name"
    const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
}
