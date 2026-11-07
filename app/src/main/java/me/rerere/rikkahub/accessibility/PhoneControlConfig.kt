package me.rerere.rikkahub.accessibility

import kotlinx.serialization.Serializable

@Serializable
data class PhoneControlConfig(val enabled: Boolean = false, val approvalRequired: Boolean = true)
