-- Safe for fresh installs and existing databases: add pvpPoints only when absent.
SET @pvp_points_column_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'character_pvp_stats'
    AND COLUMN_NAME = 'pvpPoints'
);
SET @pvp_points_column_sql = IF(
  @pvp_points_column_exists = 0,
  'ALTER TABLE `character_pvp_stats` ADD COLUMN `pvpPoints` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `bestStreak`',
  'SELECT ''character_pvp_stats.pvpPoints already exists'' AS migration_status'
);
PREPARE pvp_points_column_stmt FROM @pvp_points_column_sql;
EXECUTE pvp_points_column_stmt;
DEALLOCATE PREPARE pvp_points_column_stmt;
