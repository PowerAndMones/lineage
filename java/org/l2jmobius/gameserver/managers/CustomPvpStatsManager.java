/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Persists custom PvP statistics for valid player-versus-player kills.
 * This manager is called only after the core AntiFeedManager check succeeds.
 */
public final class CustomPvpStatsManager
{
	private static final Logger LOGGER = Logger.getLogger(CustomPvpStatsManager.class.getName());
	private static final String UPSERT_KILLER = "INSERT INTO character_pvp_stats (charId, charName, totalKills, totalDeaths, currentStreak, bestStreak, lastKillTime) VALUES (?, ?, 1, 0, 1, 1, NOW()) ON DUPLICATE KEY UPDATE charName = VALUES(charName), totalKills = totalKills + 1, bestStreak = GREATEST(bestStreak, currentStreak + 1), currentStreak = currentStreak + 1, lastKillTime = NOW()";
	private static final String UPSERT_VICTIM = "INSERT INTO character_pvp_stats (charId, charName, totalKills, totalDeaths, currentStreak, bestStreak, lastKillTime) VALUES (?, ?, 0, 1, 0, 0, NULL) ON DUPLICATE KEY UPDATE charName = VALUES(charName), totalDeaths = totalDeaths + 1, currentStreak = 0";
	private static final CustomPvpStatsManager INSTANCE = new CustomPvpStatsManager();

	public static CustomPvpStatsManager getInstance()
	{
		return INSTANCE;
	}

	private CustomPvpStatsManager()
	{
	}

	/**
	 * Records one validated PvP kill and the corresponding death.
	 * @param killer the player who won the PvP encounter
	 * @param victim the player who died
	 */
	public void recordKill(Player killer, Player victim)
	{
		if ((killer == null) || (victim == null) || (killer.getObjectId() == victim.getObjectId()))
		{
			return;
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
