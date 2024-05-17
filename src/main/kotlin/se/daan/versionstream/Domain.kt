package se.daan.versionstream

import kotlinx.serialization.Serializable

@Serializable
data class ActivityDefinition(
    val id: Int,
    val activity_code: String,
    val description_dutch: String,
    val description_english: String,
    val description_french: String,
    val description_german: String,
    val filters: Boolean,
    val maxNumberOfParts: String,
    val kilometrage: Boolean,
    val activityGroupId: Int,
    val active: Boolean,
    val activityDefinitionUnit: String?,
    val selectable: Boolean
)

@Serializable
data class ActivityGroup(
    val id: Int,
    val groupCode: String,
    val description_dutch: String,
    val description_english: String,
    val description_french: String,
    val description_german: String,
    val active: Boolean,
)
