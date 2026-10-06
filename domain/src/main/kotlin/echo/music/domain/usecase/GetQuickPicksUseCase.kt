package echo.music.domain.usecase

import echo.music.domain.models.Song
import echo.music.domain.repositories.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class GetQuickPicksUseCase(
  private val songRepository: SongRepository
) {

  operator fun invoke(limit: Int = 20): Flow<List<Song>> {
    return combine(
      songRepository.getLikedSongs(),
      songRepository.getRecentlyPlayed(limit * 2)
    ) { likedSongs, recentSongs ->
      val likedIds = likedSongs.map { it.id }.toSet()
      
      // Candidate pool: distinct union of recently played and liked songs
      val allCandidates = (recentSongs + likedSongs).distinctBy { it.id }

      allCandidates
        .sortedByDescending { song ->
          var score = 0.0
          if (likedIds.contains(song.id)) {
            score += 50.0
          }
          // Weight by total listening time in seconds (clamped to prevent extreme outliers)
          val listeningSeconds = (song.totalPlayTimeMs / 1000.0).coerceAtMost(3600.0)
          score += (listeningSeconds / 60.0) * 2.0 // +2 points per minute played up to 60 mins

          score
        }
        .take(limit)
    }
  }
}
