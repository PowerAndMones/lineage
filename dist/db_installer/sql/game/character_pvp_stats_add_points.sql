-- Run once on an existing database where character_pvp_stats already exists.
-- Fresh installations get pvpPoints from character_pvp_stats.sql and do not need this migration.
ALTER TABLE `character_pvp_stats`
  ADD COLUMN `pvpPoints` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `bestStreak`;
