/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 */
package org.l2jmobius.gameserver.managers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.custom.CustomPvpConfig;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Persists custom PvP statistics and limits repeated killer-victim farming.
 * The anti-farm window applies to custom statistics and points only; core PvP
 * counters remain managed by the existing game-server logic.
 */
public final class CustomPvpStatsManager
{
	private static final Logger LOGGER = Logger.getLogger(CustomPvpStatsManager.class.getName());
	private static final ConcurrentHashMap<Long, Long> LAST_PAIR_KILLS = new ConcurrentHashMap<>();
	private static final String UPSERT_KILLER = "INSERT INTO character_pvp_stats (charId, charName, totalKills, totalDeaths, currentStreak, bestStreak, pvpPoints, lastKillTime) VALUES (?, ?, 1, 0, 1, 1, 1, NOW()) ON DUPLICATE KEY UPDATE charName = VALUES(charName), totalKills = totalKills + 1, currentStreak = currentStreak + 1, bestStreak = GREATEST(bestStreak, currentStreak), pvpPoints = pvpPoints + 1, lastKillTime = NOW()";
	private static final String UPSERT_VICTIM = "INSERT INTO character_pvp_stats (charId, charName, totalKills, totalDeaths, currentStreak, bestStreak, pvpPoints, lastKillTime) VALUES (?, ?, 0, 1, 0, 0, 0, NULL) ON DUPLICATE KEY UPDATE charName = VALUES(charName), totalDeaths = totalDeaths + 1, currentStreak = 0";
	private static final CustomPvpStatsManager INSTANCE = new CustomPvpStatsManager();

	public static CustomPvpStatsManager getInstance()
	{
		return INSTANCE;
	}

	private CustomPvpStatsManager()
	{
	}

	/**
	 * Records a validated PvP kill unless the same character pair is farming kills.
	 * @param killer the player who won the PvP encounter
	 * @param victim the player who died
	 */
	public void recordKill(Player killer, Player victim)
	{
		if ((killer == null) || (victim == null) || (killer.getObjectId() == victim.getObjectId()))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final long pairKey = (((long) killer.getObjectId()) << 32) | (victim.getObjectId() & 0xffffffffL);
		final AtomicBoolean accepted = new AtomicBoolean(false);
		LAST_PAIR_KILLS.compute(pairKey, (key, previous) ->
		{
			if ((previous == null) || ((now - previous) >= (CustomPvpConfig.REPEAT_KILL_COOLDOWN_SECONDS * 1000L)))
			{
				accepted.set(true);
				return now;
			}
			return previous;
		});
		if (!accepted.get())
		{
			return;
		}

		// Keep the cache bounded during long-running mass-PvP sessions.
		if (LAST_PAIR_KILLS.size() > 10000)
		{
			final long expiry = now - (CustomPvpConfig.REPEAT_KILL_COOLDOWN_SECONDS * 1000L);
			LAST_PAIR_KILLS.entrySet().removeIf(entry -> entry.getValue() < expiry);
		}

		try (Connection con = DatabaseFactory.getConnection())
		{
			final boolean autoCommit = con.getAutoCommit();
			con.setAutoCommit(false);
			try
			{
				try (PreparedStatement ps = con.prepareStatement(UPSERT_KILLER))
				{
					ps.setInt(1, killer.getObjectId());
					ps.setString(2, killer.getName());
					ps.executeUpdate();
				}

				try (PreparedStatement ps = con.prepareStatement(UPSERT_VICTIM))
				{
					ps.setInt(1, victim.getObjectId());
					ps.setString(2, victim.getName());
					ps.executeUpdate();
				}
				con.commit();
			}
			catch (SQLException e)
			{
				con.rollback();
				throw e;
			}
			finally
			{
				con.setAutoCommit(autoCommit);
			}
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, "Could not persist custom PvP statistics for " + killer.getName() + " -> " + victim.getName(), e);
		}
	}
}
