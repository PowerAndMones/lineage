CREATE TABLE IF NOT EXISTS `character_pvp_stats` (
  `charId` INT NOT NULL,
  `charName` VARCHAR(35) NOT NULL,
  `totalKills` INT UNSIGNED NOT NULL DEFAULT 0,
  `totalDeaths` INT UNSIGNED NOT NULL DEFAULT 0,
  `currentStreak` INT UNSIGNED NOT NULL DEFAULT 0,
  `bestStreak` INT UNSIGNED NOT NULL DEFAULT 0,
  `pvpPoints` INT UNSIGNED NOT NULL DEFAULT 0,
  `lastKillTime` DATETIME NULL DEFAULT NULL,
  PRIMARY KEY (`charId`),
  KEY `idx_pvp_stats_rank` (`totalKills`),
  KEY `idx_pvp_stats_streak` (`bestStreak`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
