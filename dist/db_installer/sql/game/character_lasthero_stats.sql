CREATE TABLE IF NOT EXISTS `character_lasthero_stats` (
  `charId` INT NOT NULL,
  `matches` INT UNSIGNED NOT NULL DEFAULT 0,
  `wins` INT UNSIGNED NOT NULL DEFAULT 0,
  `losses` INT UNSIGNED NOT NULL DEFAULT 0,
  `rating` INT NOT NULL DEFAULT 1000,
  `winStreak` INT UNSIGNED NOT NULL DEFAULT 0,
  `bestWinStreak` INT UNSIGNED NOT NULL DEFAULT 0,
  `lastMatchAt` TIMESTAMP NULL DEFAULT NULL,
  PRIMARY KEY (`charId`),
  KEY `idx_lasthero_rating` (`rating`),
  KEY `idx_lasthero_wins` (`wins`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `character_lasthero_roster_cooldown` (
  `rosterHash` CHAR(64) NOT NULL,
  `lastRatedAt` DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00',
  PRIMARY KEY (`rosterHash`),
  KEY `idx_lasthero_last_rated` (`lastRatedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `character_lasthero_return` (
  `charId` INT NOT NULL,
  `x` INT NOT NULL,
  `y` INT NOT NULL,
  `z` INT NOT NULL,
  `heading` INT NOT NULL DEFAULT 0,
  `instanceId` INT NOT NULL DEFAULT 0,
  `savedAt` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`charId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
