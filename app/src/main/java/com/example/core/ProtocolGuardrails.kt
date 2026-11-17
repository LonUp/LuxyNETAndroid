package com.example.core

import com.example.data.local.ServerProfileEntity


/** Non-destructive storage cleanup. Capability checks belong to [ClientPolicy], not coercion. */
object ProtocolGuardrails {
    fun sanitizeProfileForSave(profile: ServerProfileEntity): ServerProfileEntity {
        val address = profile.address.trim()
        return profile.copy(
            address = address,
            name = profile.name.trim().ifEmpty { "$address:${profile.port}" }
        )
    }
}
