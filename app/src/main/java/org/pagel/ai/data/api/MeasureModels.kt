package org.pagel.ai.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Round D item 1's measured habits as `GET /api/v1/habits` sends them (dashboard src/lib/habits-api.ts, feat/8-server):
// each habit's unit ("kcal"), daily goal and goal kind, and today's total per habit. An older pagel.uz sends neither:
// every field has a default, so the phone reads it as "no measured habit yet".

@Serializable
data class HabitMeasureDto(
    val id: String,
    val title: String = "",
    val unit: String? = null,
    val goal: Double? = null,
    @SerialName("goal_kind") val goalKind: String? = null,
)

@Serializable
data class HabitMeasuresResponse(
    val day: String = "",
    val habits: List<HabitMeasureDto> = emptyList(),
    val totals: Map<String, Double> = emptyMap(),
)
