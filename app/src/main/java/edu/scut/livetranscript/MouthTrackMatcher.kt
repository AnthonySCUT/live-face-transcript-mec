package edu.scut.livetranscript

internal object MouthTrackMatcher {
    fun selectTrack(
        overlaps: List<Pair<Int, Float>>,
        minimumOverlap: Float,
        minimumMargin: Float
    ): Int? {
        val ranked = overlaps
            .filter { it.second.isFinite() && it.second >= minimumOverlap }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.second ?: return best.first
        return best.first.takeIf { best.second - runnerUp >= minimumMargin }
    }
}
