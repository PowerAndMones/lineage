-- Run once on an existing character_pvp_stats table when this index is absent.
-- Fresh installations get this index from character_pvp_stats.sql.
ALTER TABLE `character_pvp_stats`
  ADD INDEX `idx_pvp_stats_points` (`pvpPoints`, `totalKills`, `bestStreak`);
