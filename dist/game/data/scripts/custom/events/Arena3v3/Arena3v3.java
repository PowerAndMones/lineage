/*
 * Copyright (c) 2013 L2jMobius
 * Licensed under the MIT License.
 */
package custom.events.Arena3v3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.instancezone.InstanceWorld;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.annotations.RegisterEvent;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.mechanics.script.Event;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Six-player 3v3 Arena prototype. No items or global PvP points are awarded.
 * Runtime/build validation is intentionally pending.
 */
public class Arena3v3 extends Event
{
	private static final String HTML_PATH = "data/scripts/custom/events/Arena3v3/";
	private static final int MANAGER_NPC_ID = 70014;
	private static final int INSTANCE_TEMPLATE_ID = 3051;
	private static final long MATCH_TIMEOUT_MILLIS = 10 * 60 * 1000L;
	private static final long RATED_ROSTER_COOLDOWN_MILLIS = 30 * 60 * 1000L;
	private static final int RATING_K_FACTOR = 32;
	private static final String UPSERT_RETURN = "INSERT INTO character_arena3v3_return (charId, x, y, z, heading, instanceId) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE x=VALUES(x), y=VALUES(y), z=VALUES(z), heading=VALUES(heading), instanceId=VALUES(instanceId)";
	private static final String SELECT_RETURN = "SELECT x, y, z, heading, instanceId FROM character_arena3v3_return WHERE charId=?";
	private static final String DELETE_RETURN = "DELETE FROM character_arena3v3_return WHERE charId=?";
	private static final String INSERT_STATS = "INSERT IGNORE INTO character_arena3v3_stats (charId) VALUES (?)";
	private static final String SELECT_STATS = "SELECT rating FROM character_arena3v3_stats WHERE charId=? FOR UPDATE";
	private static final String UPDATE_WIN = "UPDATE character_arena3v3_stats SET matches=matches+1, wins=wins+1, rating=?, bestWinStreak=GREATEST(bestWinStreak, winStreak+1), winStreak=winStreak+1, lastMatchAt=CURRENT_TIMESTAMP WHERE charId=?";
	private static final String UPDATE_LOSS = "UPDATE character_arena3v3_stats SET matches=matches+1, losses=losses+1, rating=?, winStreak=0, lastMatchAt=CURRENT_TIMESTAMP WHERE charId=?";
	private static final String ENSURE_COOLDOWN = "INSERT IGNORE INTO character_arena3v3_team_cooldown (teamHashLow, teamHashHigh, lastRatedAt) VALUES (?, ?, '1970-01-01 00:00:00')";
	private static final String SELECT_COOLDOWN = "SELECT TIMESTAMPDIFF(SECOND, lastRatedAt, CURRENT_TIMESTAMP) AS secondsSinceRated FROM character_arena3v3_team_cooldown WHERE teamHashLow=? AND teamHashHigh=? FOR UPDATE";
	private static final String UPDATE_COOLDOWN = "UPDATE character_arena3v3_team_cooldown SET lastRatedAt=CURRENT_TIMESTAMP WHERE teamHashLow=? AND teamHashHigh=?";
	private static final Location SPAWN_A = new Location(147250, 46722, -3416, 0);
	private static final Location SPAWN_B = new Location(151700, 46722, -3416, 32768);
	private static final Location RETURN_FALLBACK = new Location(83425, 148585, -3406);
	private static final Location MANAGER_SPAWN = new Location(83425, 148585, -3406, 32938);
	private static final ConcurrentLinkedQueue<Player> WAITING = new ConcurrentLinkedQueue<>();
	private static final Set<Integer> WAITING_IDS = ConcurrentHashMap.newKeySet();
	private static final Map<Integer, ArenaMatch> MATCHES = new ConcurrentHashMap<>();
	private static volatile boolean ACTIVE;
	private static Npc MANAGER_NPC;

	private Arena3v3()
	{
		addTalkId(MANAGER_NPC_ID);
		addStartNpc(MANAGER_NPC_ID);
		addFirstTalkId(MANAGER_NPC_ID);
	}

	@Override
	public synchronized boolean eventStart(Player eventMaker)
	{
		if (ACTIVE)
		{
			return false;
		}
		clearQueue("3v3 Arena queue was reset.");
		ACTIVE = true;
		MANAGER_NPC = addSpawn(MANAGER_NPC_ID, MANAGER_SPAWN, false, 0);
		return true;
	}

	@Override
	public synchronized boolean eventStop()
	{
		ACTIVE = false;
		clearQueue("3v3 Arena has been stopped.");
		for (ArenaMatch match : Set.copyOf(MATCHES.values()))
		{
			finishMatch(match, null, false);
		}
		if (MANAGER_NPC != null)
		{
			MANAGER_NPC.deleteMe();
			MANAGER_NPC = null;
		}
		return true;
	}

	@Override
	public boolean eventBypass(Player player, String bypass)
	{
		if (bypass == null)
		{
			return false;
		}
		final String command = bypass.startsWith("Arena3v3 ") ? bypass.substring("Arena3v3 ".length()) : bypass;
		switch (command)
		{
			case "Join":
				joinQueue(player);
				return true;
			case "Leave":
				leaveQueue(player);
				return true;
			case "Status":
				if (player != null)
				{
					player.sendMessage("3v3 Arena: " + (ACTIVE ? "Open" : "Closed") + ". Waiting players: " + WAITING.size() + "/6.");
				}
				return true;
			default:
				return false;
		}
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ("Join".equals(event))
		{
			joinQueue(player);
		}
		else if ("Leave".equals(event))
		{
			leaveQueue(player);
		}
		else if ("Status".equals(event) && (player != null))
		{
			player.sendMessage("3v3 Arena: " + (ACTIVE ? "Open" : "Closed") + ". Waiting players: " + WAITING.size() + "/6.");
		}
		return null;
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setFile(player, HTML_PATH + "manager.html");
		html.replace("%queue_size%", String.valueOf(WAITING.size()));
		html.replace("%arena_status%", ACTIVE ? "Open" : "Closed");
		player.sendPacket(html);
		return null;
	}

	private synchronized void joinQueue(Player player)
	{
		if ((player == null) || !ACTIVE)
		{
			if (player != null)
			{
				player.sendMessage("3v3 Arena is currently closed.");
			}
			return;
		}
		if (!canRegister(player))
		{
			return;
		}
		if (!WAITING_IDS.add(player.getObjectId()))
		{
			player.sendMessage("You are already waiting for 3v3 Arena.");
			return;
		}
		player.setRegisteredOnEvent(true);
		WAITING.add(player);
		player.sendMessage("Joined 3v3 Arena queue. Waiting players: " + WAITING.size() + "/6.");
		matchPlayers();
	}

	private synchronized void leaveQueue(Player player)
	{
		if (player == null)
		{
			return;
		}
		if (WAITING_IDS.remove(player.getObjectId()))
		{
			WAITING.remove(player);
			player.setRegisteredOnEvent(false);
			player.sendMessage("You left the 3v3 Arena queue.");
		}
		else
		{
			player.sendMessage("You are not waiting in the 3v3 Arena queue.");
		}
	}

	private synchronized void matchPlayers()
	{
		while (ACTIVE && (WAITING.size() >= 6))
		{
			final List<Player> selected = new ArrayList<>(6);
			for (int i = 0; i < 6; i++)
			{
				final Player p = WAITING.poll();
				if (p != null)
				{
					WAITING_IDS.remove(p.getObjectId());
					p.setRegisteredOnEvent(false);
					if (isMatchReady(p))
					{
						selected.add(p);
					}
					else
					{
						p.sendMessage("Removed from 3v3 queue because you are no longer eligible.");
					}
				}
			}
			if (selected.size() < 6)
			{
				for (Player p : selected)
				{
					if (isMatchReady(p) && WAITING_IDS.add(p.getObjectId()))
					{
						p.setRegisteredOnEvent(true);
						WAITING.add(p);
					}
				}
				if (WAITING.size() >= 6)
				{
					continue;
				}
				return;
			}
			final List<Player> teamA = new ArrayList<>(selected.subList(0, 3));
			final List<Player> teamB = new ArrayList<>(selected.subList(3, 6));
			final ArenaMatch match = new ArenaMatch(teamA, teamB);
			if (!match.start())
			{
				for (Player p : selected)
				{
					p.sendMessage("3v3 Arena could not start the match; you have been returned to the queue.");
					if (ACTIVE && isMatchReady(p) && WAITING_IDS.add(p.getObjectId()))
					{
						p.setRegisteredOnEvent(true);
						WAITING.add(p);
					}
				}
				// Retry after a delay so a persistent instance failure cannot spin the game thread.
				ThreadPool.schedule(() ->
				{
					synchronized (Arena3v3.this)
					{
						if (ACTIVE)
						{
							matchPlayers();
						}
					}
				}, 10000);
				return;
			}
			for (Player p : selected)
			{
				MATCHES.put(p.getObjectId(), match);
				p.setOnEvent(true);
				p.setRegisteredOnEvent(false);
			}
			match.started = true;
			for (Player p : teamA)
			{
				p.sendMessage("3v3 Arena match started. You are on Team A.");
			}
			for (Player p : teamB)
			{
				p.sendMessage("3v3 Arena match started. You are on Team B.");
			}
			ThreadPool.schedule(() -> finishMatch(match, null, true), MATCH_TIMEOUT_MILLIS);
			resolveMatchIfTeamWiped(match);
		}
	}

	@RegisterEvent(EventType.ON_CREATURE_DEATH)
	private void onCreatureDeath(OnCreatureDeath event)
	{
		final Creature target = event.getTarget();
		if ((target == null) || !target.isPlayer())
		{
			return;
		}
		final ArenaMatch match = MATCHES.get(target.asPlayer().getObjectId());
		if ((match != null) && match.started && !match.finished.get())
		{
			ThreadPool.schedule(() -> resolveMatchIfTeamWiped(match), 1000);
		}
	}

	private void resolveMatchIfTeamWiped(ArenaMatch match)
	{
		if (!match.started || match.finished.get())
		{
			return;
		}
		final boolean teamAAlive = match.teamA.stream().anyMatch(p -> (p.isOnlineInt() == 1) && !p.isDead());
		final boolean teamBAlive = match.teamB.stream().anyMatch(p -> (p.isOnlineInt() == 1) && !p.isDead());
		if (!teamAAlive && !teamBAlive)
		{
			finishMatch(match, null, false);
		}
		else if (!teamAAlive)
		{
			finishMatch(match, match.teamB, false);
		}
		else if (!teamBAlive)
		{
			finishMatch(match, match.teamA, false);
		}
	}

	@RegisterEvent(EventType.ON_PLAYER_LOGOUT)
	private void onPlayerLogout(OnPlayerLogout event)
	{
		final Player player = event.getPlayer();
		if (player == null)
		{
			return;
		}
		synchronized (this)
		{
			if (WAITING_IDS.remove(player.getObjectId()))
			{
				WAITING.remove(player);
				player.setRegisteredOnEvent(false);
			}
		}
		final ArenaMatch match = MATCHES.get(player.getObjectId());
		if (match != null)
		{
			// Deterministic initial policy: any disconnect forfeits that player's team immediately.
			finishMatch(match, match.getOpposingTeam(player), false);
		}
	}

	@RegisterEvent(EventType.ON_PLAYER_LOGIN)
	private void onPlayerLogin(OnPlayerLogin event)
	{
		final Player player = event.getPlayer();
		if (player == null)
		{
			return;
		}
		final Location saved = loadReturnLocation(player.getObjectId());
		if (saved != null)
		{
			ThreadPool.schedule(() ->
			{
				if (player.isOnlineInt() == 1)
				{
					final Location destination = ((saved.getInstanceId() > 0) && (InstanceManager.getInstance().getInstance(saved.getInstanceId()) == null)) ? RETURN_FALLBACK : saved;
					player.teleToLocation(destination, true);
					deleteReturnLocation(player.getObjectId());
				}
			}, 2000);
		}
	}

	private void finishMatch(ArenaMatch match, List<Player> winningTeam, boolean timeout)
	{
		if (!match.finished.compareAndSet(false, true))
		{
			return;
		}
		if ((winningTeam != null) && winningTeam.stream().noneMatch(p -> (p.isOnlineInt() == 1) && !p.isDead()))
		{
			// A winner team must have at least one living, connected player.
			winningTeam = null;
		}
		if (winningTeam != null)
		{
			final List<Player> losingTeam = winningTeam == match.teamA ? match.teamB : match.teamA;
			final int rated = recordRatedResult(winningTeam, losingTeam);
			for (Player p : match.players())
			{
				p.sendMessage(rated == 1 ? "3v3 Arena: result and rating saved." : rated == 0 ? "3v3 Arena: repeat-roster cooldown active; this match did not change rating or rated statistics." : "3v3 Arena: result could not be saved; contact an administrator.");
			}
		}
		for (Player p : match.players())
		{
			MATCHES.remove(p.getObjectId(), match);
			p.setOnEvent(false);
			p.setRegisteredOnEvent(false);
			if (p.isOnlineInt() == 1)
			{
				if (p.isDead())
				{
					p.doRevive();
				}
				final Location destination = match.getReturnLocation(p);
				final Location safeDestination = (destination == null) || ((destination.getInstanceId() > 0) && (InstanceManager.getInstance().getInstance(destination.getInstanceId()) == null)) ? RETURN_FALLBACK : destination;
				p.teleToLocation(safeDestination, true);
				deleteReturnLocation(p.getObjectId());
				p.sendMessage(winningTeam == null ? (timeout ? "3v3 Arena ended without a winner (time limit)." : "3v3 Arena ended without a winner.") : (winningTeam == match.teamA ? "3v3 Arena: Team A won." : "3v3 Arena: Team B won."));
			}
		}
		if (match.world != null)
		{
			match.world.destroy();
		}
	}

	private static int recordRatedResult(List<Player> winningTeam, List<Player> losingTeam)
	{
		try
		{
			final String hashA = rosterHash(winningTeam);
			final String hashB = rosterHash(losingTeam);
			final String low = hashA.compareTo(hashB) <= 0 ? hashA : hashB;
			final String high = hashA.compareTo(hashB) <= 0 ? hashB : hashA;
			try (Connection con = DatabaseFactory.getConnection())
			{
				con.setAutoCommit(false);
				try
				{
					try (PreparedStatement ps = con.prepareStatement(INSERT_STATS))
					{
						for (Player p : concat(winningTeam, losingTeam))
						{
							ps.setInt(1, p.getObjectId());
							ps.executeUpdate();
						}
					}
					try (PreparedStatement ps = con.prepareStatement(ENSURE_COOLDOWN))
					{
						ps.setString(1, low);
						ps.setString(2, high);
						ps.executeUpdate();
					}
					final long secondsSinceRated;
					try (PreparedStatement ps = con.prepareStatement(SELECT_COOLDOWN))
					{
						ps.setString(1, low);
						ps.setString(2, high);
						try (ResultSet rs = ps.executeQuery())
						{
							if (!rs.next()) throw new IllegalStateException("Missing 3v3 roster cooldown row.");
							secondsSinceRated = rs.getLong("secondsSinceRated");
						}
					}
					if (secondsSinceRated < (RATED_ROSTER_COOLDOWN_MILLIS / 1000L))
					{
						con.rollback();
						return 0;
					}
					final Map<Integer, Integer> ratings = new ConcurrentHashMap<>();
					try (PreparedStatement ps = con.prepareStatement(SELECT_STATS))
					{
						for (Player p : concat(winningTeam, losingTeam))
						{
							ps.setInt(1, p.getObjectId());
							try (ResultSet rs = ps.executeQuery())
							{
								if (!rs.next()) throw new IllegalStateException("Missing 3v3 rating row.");
								ratings.put(p.getObjectId(), rs.getInt("rating"));
							}
						}
					}
					final double avgWin = winningTeam.stream().mapToInt(p -> ratings.get(p.getObjectId())).average().orElse(1000);
					final double avgLoss = losingTeam.stream().mapToInt(p -> ratings.get(p.getObjectId())).average().orElse(1000);
					final double expectedWin = 1.0 / (1.0 + Math.pow(10.0, (avgLoss - avgWin) / 400.0));
					for (Player p : winningTeam)
					{
						final int next = ratings.get(p.getObjectId()) + (int) Math.round(RATING_K_FACTOR * (1.0 - expectedWin));
						try (PreparedStatement ps = con.prepareStatement(UPDATE_WIN))
						{
							ps.setInt(1, next);
							ps.setInt(2, p.getObjectId());
							ps.executeUpdate();
						}
					}
					for (Player p : losingTeam)
					{
						final int next = ratings.get(p.getObjectId()) + (int) Math.round(RATING_K_FACTOR * (0.0 - (1.0 - expectedWin)));
						try (PreparedStatement ps = con.prepareStatement(UPDATE_LOSS))
						{
							ps.setInt(1, next);
							ps.setInt(2, p.getObjectId());
							ps.executeUpdate();
						}
					}
					try (PreparedStatement ps = con.prepareStatement(UPDATE_COOLDOWN))
					{
						ps.setString(1, low);
						ps.setString(2, high);
						ps.executeUpdate();
					}
					con.commit();
					return 1;
				}
				catch (Exception e)
				{
					con.rollback();
					throw e;
				}
				finally
				{
					con.setAutoCommit(true);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("3v3 Arena rating transaction failed: " + e.getMessage());
			return -1;
		}
	}

	private static List<Player> concat(List<Player> a, List<Player> b)
	{
		final List<Player> all = new ArrayList<>(6);
		all.addAll(a);
		all.addAll(b);
		return all;
	}

	private static String rosterHash(List<Player> team) throws Exception
	{
		final String ids = team.stream().map(Player::getObjectId).sorted(Comparator.naturalOrder()).map(String::valueOf).reduce((a, b) -> a + ":" + b).orElse("");
		final byte[] digest = MessageDigest.getInstance("SHA-256").digest(ids.getBytes(StandardCharsets.UTF_8));
		final StringBuilder out = new StringBuilder(64);
		for (byte value : digest)
		{
			out.append(String.format("%02x", value & 0xff));
		}
		return out.toString();
	}

	private static boolean saveReturnLocation(int charId, Location loc)
	{
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(UPSERT_RETURN))
		{
			ps.setInt(1, charId);
			ps.setInt(2, loc.getX());
			ps.setInt(3, loc.getY());
			ps.setInt(4, loc.getZ());
			ps.setInt(5, loc.getHeading());
			ps.setInt(6, loc.getInstanceId());
			ps.executeUpdate();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.warning("3v3 Arena could not save return location for " + charId + ": " + e.getMessage());
			return false;
		}
	}

	private static Location loadReturnLocation(int charId)
	{
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(SELECT_RETURN))
		{
			ps.setInt(1, charId);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next()) return new Location(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getInt("heading"), rs.getInt("instanceId"));
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("3v3 Arena could not load return location for " + charId + ": " + e.getMessage());
		}
		return null;
	}

	private static void deleteReturnLocation(int charId)
	{
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(DELETE_RETURN))
		{
			ps.setInt(1, charId);
			ps.executeUpdate();
		}
		catch (Exception e)
		{
			LOGGER.warning("3v3 Arena could not delete return location for " + charId + ": " + e.getMessage());
		}
	}

	private boolean canRegister(Player p)
	{
		if (p.isRegisteredOnEvent() || p.isOnEvent() || p.isInDuel() || p.isInOlympiadMode() || p.isInInstance() || p.isInSiege() || p.isFishing() || p.isInCombat() || (p.getPvpFlag() != 0) || (p.getKarma() > 0) || p.isCursedWeaponEquipped() || p.isFlying() || !p.isInventoryUnder80(false) || (p.getWeightPenalty() != 0))
		{
			p.sendMessage("You cannot join the 3v3 queue in your current state.");
			return false;
		}
		return true;
	}

	private boolean isMatchReady(Player p)
	{
		return (p != null) && (p.isOnlineInt() == 1) && !p.isDead() && !p.isOnEvent() && !p.isInDuel() && (p.getPvpFlag() == 0) && !p.isInOlympiadMode() && !p.isInInstance() && !p.isInSiege() && (p.getKarma() == 0);
	}

	private void clearQueue(String message)
	{
		for (Player p : WAITING)
		{
			if (p != null)
			{
				p.setRegisteredOnEvent(false);
				if (message != null) p.sendMessage(message);
			}
		}
		WAITING.clear();
		WAITING_IDS.clear();
	}

	private static final class ArenaMatch
	{
		private final List<Player> teamA;
		private final List<Player> teamB;
		private final Map<Integer, Location> returnLocations = new ConcurrentHashMap<>();
		private final AtomicBoolean finished = new AtomicBoolean();
		private volatile boolean started;
		private InstanceWorld world;

		private ArenaMatch(List<Player> teamA, List<Player> teamB)
		{
			this.teamA = List.copyOf(teamA);
			this.teamB = List.copyOf(teamB);
			for (Player p : players()) returnLocations.put(p.getObjectId(), new Location(p));
		}

		private List<Player> players()
		{
			return concat(teamA, teamB);
		}

		private List<Player> getTeam(Player p)
		{
			return teamA.contains(p) ? teamA : teamB;
		}

		private List<Player> getOpposingTeam(Player p)
		{
			return teamA.contains(p) ? teamB : teamA;
		}

		private Location getReturnLocation(Player p)
		{
			return returnLocations.get(p.getObjectId());
		}

		private boolean start()
		{
			try
			{
				for (Player p : players())
				{
					if (!saveReturnLocation(p.getObjectId(), returnLocations.get(p))) throw new IllegalStateException("Could not persist all return locations.");
				}
				world = new InstanceWorld();
				world.setInstance(InstanceManager.getInstance().createDynamicInstance(INSTANCE_TEMPLATE_ID));
				if (world.getInstanceId() <= 0)
				{
					throw new IllegalStateException("Invalid 3v3 Arena instance ID.");
				}
				InstanceManager.getInstance().addWorld(world);
				for (Player p : players()) world.addAllowed(p);
				for (int i = 0; i < 3; i++) teamA.get(i).teleToLocation(SPAWN_A, world.getInstanceId(), 50);
				for (int i = 0; i < 3; i++) teamB.get(i).teleToLocation(SPAWN_B, world.getInstanceId(), 50);
				for (Player p : players())
				{
					if ((p.isOnlineInt() != 1) || (p.getInstanceId() != world.getInstanceId())) throw new IllegalStateException("A participant did not enter the arena instance.");
				}
				return true;
			}
			catch (Exception e)
			{
				LOGGER.warning("3v3 Arena match startup failed: " + e.getMessage());
				for (Player p : players())
				{
					if (p.isOnlineInt() == 1)
					{
						final Location loc = returnLocations.get(p.getObjectId());
						p.teleToLocation(loc != null ? loc : RETURN_FALLBACK, true);
					}
					else
					{
						// Leave recovery record for the next login.
					}
					if (p.isOnlineInt() == 1) deleteReturnLocation(p.getObjectId());
				}
				if (world != null) world.destroy();
				return false;
			}
		}
	}
	/**
	 * Loads the event script and registers its NPC interactions.
	 * @param args unused script arguments
	 */
	public static void main(String[] args)
	{
		new Arena3v3();
	}

}
