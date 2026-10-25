CREATE TABLE IF NOT EXISTS `character_arena1v1_stats` (
  `charId` INT NOT NULL,
  `matches` INT UNSIGNED NOT NULL DEFAULT 0,
  `wins` INT UNSIGNED NOT NULL DEFAULT 0,
  `losses` INT UNSIGNED NOT NULL DEFAULT 0,
  `rating` INT NOT NULL DEFAULT 1000,
  `winStreak` INT UNSIGNED NOT NULL DEFAULT 0,
  `bestWinStreak` INT UNSIGNED NOT NULL DEFAULT 0,
  `lastMatchAt` TIMESTAMP NULL DEFAULT NULL,
  PRIMARY KEY (`charId`),
  KEY `idx_arena1v1_rating` (`rating`),
  KEY `idx_arena1v1_wins` (`wins`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `character_arena1v1_pair_cooldown` (
  `charIdLow` INT NOT NULL,
  `charIdHigh` INT NOT NULL,
  `lastRatedAt` DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00',
  PRIMARY KEY (`charIdLow`, `charIdHigh`),
  KEY `idx_arena1v1_pair_last_rated` (`lastRatedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
