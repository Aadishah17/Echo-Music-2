package echo.music.dsp.core.models

import kotlinx.serialization.Serializable

@Serializable
data class SavedEQProfile(
  val id: String,
  val name: String,
  val eq: ParametricEQ,
  val isBuiltIn: Boolean = false,
  val createdAt: Long = System.currentTimeMillis()
)
