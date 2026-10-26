-- Safe for fresh installs and existing databases: add the ranking index only when absent.
SET @pvp_points_index_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'character_pvp_stats'
    AND INDEX_NAME = 'idx_pvp_stats_points'
);
SET @pvp_points_index_sql = IF(
  @pvp_points_index_exists = 0,
  'ALTER TABLE `character_pvp_stats` ADD INDEX `idx_pvp_stats_points` (`pvpPoints`, `totalKills`, `bestStreak`)',
  'SELECT ''idx_pvp_stats_points already exists'' AS migration_status'
);
PREPARE pvp_points_index_stmt FROM @pvp_points_index_sql;
EXECUTE pvp_points_index_stmt;
DEALLOCATE PREPARE pvp_points_index_stmt;
