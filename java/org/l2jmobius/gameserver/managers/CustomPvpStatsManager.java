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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.CustomPvpConfig;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Persists custom PvP statistics asynchronously and limits repeated killer-victim farming.
 * The queue is drained by one worker to preserve the order of streak updates.
 * Core PvP counters remain managed by the existing game-server logic.
 */
public final class CustomPvpStatsManager
{
	private static final Logger LOGGER = Logger.getLogger(CustomPvpStatsManager.class.getName());
	private static final ConcurrentHashMap<Long, Long> LAST_PAIR_KILLS = new ConcurrentHashMap<>();
	private static final ConcurrentLinkedQueue<KillRecord> PENDING_KILLS = new ConcurrentLinkedQueue<>();
	private static final AtomicBoolean WORKER_RUNNING = new AtomicBoolean(false);
	private static final AtomicBoolean BACKLOG_WARNING_ACTIVE = new AtomicBoolean(false);
	private static final AtomicBoolean QUEUE_FULL_WARNING_ACTIVE = new AtomicBoolean(false);
	private static final String UPSERT_KILLER = "INSERT INTO character_pvp_stats (charId, charName, totalKills, totalDeaths, currentStreak, bestStreak, pvpPoints, lastKillTime) VALUES (?, ?, 1, 0, 1, 1, 1, NOW()) ON DUPLICATE KEY UPDATE charName = VALUES(charName), totalKills = totalKills + 1, currentStreak = currentStreak + 1, bestStreak = GREATEST(bestStreak, currentStreak + 1), pvpPoints = pvpPoints + 1, lastKillTime = NOW()";
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
	 * @return the current number of PvP records waiting for database persistence
	 */
	public int getPendingKillCount()
	{
		return PENDING_KILLS.size();
	}

	/**
	 * Applies the anti-farm rule and queues a validated kill for asynchronous persistence.
	 * @param killer the player who won the PvP encounter
	 * @param victim the player who died
	 */
	public void recordKill(Player killer, Player victim)
	{
		// Fake players are implemented as NPCs in Mobius and normally cannot reach this
		// Player-to-Player path. Keep an explicit guard so they can never earn custom rewards.
		if ((killer == null) || (victim == null) || killer.isFakePlayer() || victim.isFakePlayer() || (killer.getObjectId() == victim.getObjectId()))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final long cooldownMs = CustomPvpConfig.REPEAT_KILL_COOLDOWN_SECONDS * 1000L;
		final long pairKey = (((long) killer.getObjectId()) << 32) | (victim.getObjectId() & 0xffffffffL);
		final AtomicBoolean accepted = new AtomicBoolean(false);
		LAST_PAIR_KILLS.compute(pairKey, (key, previous) ->
		{
			if ((previous == null) || (cooldownMs == 0) || ((now - previous) >= cooldownMs))
			{
				accepted.set(true);
				return now;
			}
			return previous;
		});
		final boolean awardKiller = accepted.get();

		// Keep the cache bounded during long-running mass-PvP sessions.
		if (LAST_PAIR_KILLS.size() > 10000)
		{
			final long expiry = now - cooldownMs;
			LAST_PAIR_KILLS.entrySet().removeIf(entry -> entry.getValue() < expiry);
		}

		final KillRecord record = new KillRecord(killer.getObjectId(), killer.getName(), victim.getObjectId(), victim.getName(), awardKiller);
		final int maxPending = CustomPvpConfig.MAX_PENDING_RECORDS;
		if ((maxPending > 0) && (PENDING_KILLS.size() >= maxPending))
		{
			if (QUEUE_FULL_WARNING_ACTIVE.compareAndSet(false, true))
			{
				LOGGER.severe("Custom PvP persistence queue is full (" + PENDING_KILLS.size() + " records). New custom PvP records are being dropped until the queue drains. Increase MaxPendingRecords only after checking database performance.");
			}
			return;
		}

		PENDING_KILLS.add(record);
		checkQueueBacklog();
		scheduleWorker();
	}

	private void checkQueueBacklog()
	{
		final int pending = PENDING_KILLS.size();
		final int threshold = CustomPvpConfig.QUEUE_WARNING_THRESHOLD;
		if ((threshold > 0) && (pending >= threshold))
		{
			if (BACKLOG_WARNING_ACTIVE.compareAndSet(false, true))
			{
				LOGGER.warning("Custom PvP persistence queue backlog reached " + pending + " records (warning threshold: " + threshold + "). Check database connectivity and server load.");
			}
		}
		else if ((threshold <= 0) || (pending < threshold))
		{
			if (BACKLOG_WARNING_ACTIVE.compareAndSet(true, false))
			{
				LOGGER.info("Custom PvP persistence queue recovered below warning threshold. Pending records: " + pending);
			}
		}

		final int maxPending = CustomPvpConfig.MAX_PENDING_RECORDS;
		if ((maxPending <= 0) || (pending < maxPending))
		{
			if (QUEUE_FULL_WARNING_ACTIVE.compareAndSet(true, false))
			{
				LOGGER.info("Custom PvP persistence queue is no longer full. Pending records: " + pending);
			}
		}
	}

	private void scheduleWorker()
	{
		if (WORKER_RUNNING.compareAndSet(false, true))
		{
			ThreadPool.execute(this::processPendingKills);
		}
	}

	private void processPendingKills()
	{
		try
		{
			KillRecord record;
			while ((record = PENDING_KILLS.poll()) != null)
			{
				persistKillWithRetry(record);
				checkQueueBacklog();
			}
		}
		finally
		{
			WORKER_RUNNING.set(false);
			// Avoid a race where a new record arrives as the worker is finishing.
			if (!PENDING_KILLS.isEmpty())
			{
				scheduleWorker();
			}
		}
	}

	private void persistKillWithRetry(KillRecord record)
	{
		final int maxAttempts = Math.max(1, CustomPvpConfig.DB_RETRY_ATTEMPTS);
		for (int attempt = 1; attempt <= maxAttempts; attempt++)
		{
			if (persistKill(record))
			{
				return;
			}

			if (attempt < maxAttempts)
			{
				final long delayMs = Math.min(5000L, 500L * (1L << Math.min(attempt - 1, 4)));
				try
				{
					Thread.sleep(delayMs);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					LOGGER.log(Level.WARNING, "Interrupted while retrying custom PvP persistence for " + record.killerName + " -> " + record.victimName, e);
					break;
				}
			}
		}

		LOGGER.severe("Failed to persist custom PvP record after " + maxAttempts + " attempt(s): " + record.killerName + " -> " + record.victimName + ". This record is being dropped; pending queue size: " + PENDING_KILLS.size());
	}

	private boolean persistKill(KillRecord record)
	{
		try (Connection con = DatabaseFactory.getConnection())
		{
			final boolean autoCommit = con.getAutoCommit();
			con.setAutoCommit(false);
			try
			{
				if (record.awardKiller)
				{
					try (PreparedStatement ps = con.prepareStatement(UPSERT_KILLER))
					{
						ps.setInt(1, record.killerId);
						ps.setString(2, record.killerName);
						ps.executeUpdate();
					}
				}

				try (PreparedStatement ps = con.prepareStatement(UPSERT_VICTIM))
				{
					ps.setInt(1, record.victimId);
					ps.setString(2, record.victimName);
					ps.executeUpdate();
				}
				con.commit();
				return true;
			}
			catch (SQLException e)
			{
				try
				{
					con.rollback();
				}
				catch (SQLException rollbackException)
				{
					e.addSuppressed(rollbackException);
				}
				throw e;
			}
			finally
			{
				con.setAutoCommit(autoCommit);
			}
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, "Custom PvP SQL persistence attempt failed for " + record.killerName + " -> " + record.victimName, e);
			return false;
		}
	}

	private static final class KillRecord
	{
		private final int killerId;
		private final String killerName;
		private final int victimId;
		private final String victimName;
		private final boolean awardKiller;

		private KillRecord(int killerId, String killerName, int victimId, String victimName, boolean awardKiller)
		{
			this.killerId = killerId;
			this.killerName = killerName;
			this.victimId = victimId;
			this.victimName = victimName;
			this.awardKiller = awardKiller;
		}
	}
}
