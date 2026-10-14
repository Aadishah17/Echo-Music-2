package echo.music.iad1tya.ui.component.musicflow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import echo.music.iad1tya.musicflow.MusicFlowLyricFrame

@Composable
fun MusicFlowLyricsView(
  frame: MusicFlowLyricFrame,
  reduceMotion: Boolean,
  modifier: Modifier = Modifier,
) {
  val enterOffset = with(LocalDensity.current) { 24.dp.roundToPx() }
  val exitOffset = with(LocalDensity.current) { 12.dp.roundToPx() }
  val duration = if (reduceMotion) 0 else 280

  AnimatedContent(
    targetState = frame,
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.TopStart,
    contentKey = { it.index },
    transitionSpec = {
      val direction = if (targetState.index >= initialState.index) 1 else -1
      (fadeIn(tween(duration)) +
          slideInVertically(tween(duration, easing = FastOutSlowInEasing)) {
            if (reduceMotion) 0 else enterOffset * direction
          })
        .togetherWith(
          fadeOut(tween(if (reduceMotion) 0 else 180)) +
            slideOutVertically(tween(duration, easing = FastOutSlowInEasing)) {
              if (reduceMotion) 0 else -exitOffset * direction
            }
        )
        .using(SizeTransform(sizeAnimationSpec = { _, _ -> tween(duration) }))
    },
    label = "MusicFlowLyrics",
  ) { displayedFrame ->
    if (displayedFrame.currentLine.isNotBlank() || displayedFrame.nextLine.isNotBlank()) {
      Column(Modifier.fillMaxWidth()) {
        Text(
          text = displayedFrame.currentLine,
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.ExtraBold,
          color = Color.White,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
        if (displayedFrame.nextLine.isNotBlank()) {
          Text(
            text = displayedFrame.nextLine,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.45f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    }
  }
}
