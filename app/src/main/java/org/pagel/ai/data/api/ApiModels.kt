package org.pagel.ai.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The wire shapes of pagel.uz's app API (dashboard src/lib/sync.ts, src/lib/devices.ts). Unknown fields are ignored,
// so the server can add fields before the app knows them.

@Serializable
data class PairRequest(val code: String, val name: String)

@Serializable
data class PairResponse(val id: String, val name: String, val token: String)

@Serializable
data class ErrorResponse(val error: String = "")

@Serializable
data class SyncStepDto(val text: String, val title: String? = null, val done: Boolean = false)

@Serializable
data class SyncTaskDto(
    val id: String,
    val project: String,
    val phase: String = "",
    val sort: Double = 0.0,
    val text: String,
    val title: String? = null,
    val status: String,
    val priority: Int = 2,
    val star: Boolean = false,
    val due: String? = null,
    val every: String? = null,
    @SerialName("done_when") val doneWhen: String? = null,
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val version: Int = 1,
    val steps: List<SyncStepDto> = emptyList(),
)

@Serializable
data class SyncProjectDto(val name: String, val title: String, val color: String? = null, val sort: Int = 0, val open: Int = 0)

@Serializable
data class SyncResponse(
    val cursor: Long,
    val full: Boolean,
    val projects: List<SyncProjectDto> = emptyList(),
    val tasks: List<SyncTaskDto> = emptyList(),
    val removed: List<String> = emptyList(),
)
