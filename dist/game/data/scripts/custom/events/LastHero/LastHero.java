/*
 * Copyright (c) 2013 L2jMobius
 * Licensed under the MIT License.
 */
package custom.events.LastHero;

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
import org.l2jmobius.gameserver.entity.instancezone.Instance;
import org.l2jmobius.gameserver.entity.instancezone.InstanceWorld;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.annotations.RegisterEvent;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureTeleported;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.mechanics.script.Event;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Last Hero free-for-all prototype. No items or open-world PvP points are awarded.
 * Core and script compilation passed in CI; database and runtime validation remain pending.
 */
public class LastHero extends Event
{
	private static final String HTML_PATH = "data/scripts/custom/events/LastHero/";
	private static final int MANAGER_NPC_ID = 70015;
	private static final int INSTANCE_TEMPLATE_ID = 3052;
	private static final int MIN_PLAYERS = 4;
	private static final int MAX_PLAYERS = 10;
	private static final long MATCH_TIMEOUT_MILLIS = 10 * 60 * 1000L;
	private static final long ROSTER_COOLDOWN_MILLIS = 30 * 60 * 1000L;
	private static final int K_FACTOR = 32;
	private static final String UPSERT_RETURN = "INSERT INTO character_lasthero_return (charId,x,y,z,heading,instanceId) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE x=VALUES(x),y=VALUES(y),z=VALUES(z),heading=VALUES(heading),instanceId=VALUES(instanceId)";
	private static final String SELECT_RETURN = "SELECT x,y,z,heading,instanceId FROM character_lasthero_return WHERE charId=?";
	private static final String DELETE_RETURN = "DELETE FROM character_lasthero_return WHERE charId=?";
	private static final String INSERT_STATS = "INSERT IGNORE INTO character_lasthero_stats (charId) VALUES (?)";
	private static final String SELECT_RATING = "SELECT rating FROM character_lasthero_stats WHERE charId=? FOR UPDATE";
	private static final String WIN_SQL = "UPDATE character_lasthero_stats SET matches=matches+1,wins=wins+1,rating=?,bestWinStreak=GREATEST(bestWinStreak,winStreak+1),winStreak=winStreak+1,lastMatchAt=CURRENT_TIMESTAMP WHERE charId=?";
	private static final String LOSS_SQL = "UPDATE character_lasthero_stats SET matches=matches+1,losses=losses+1,rating=?,winStreak=0,lastMatchAt=CURRENT_TIMESTAMP WHERE charId=?";
	private static final String ENSURE_COOLDOWN = "INSERT IGNORE INTO character_lasthero_roster_cooldown (rosterHash,lastRatedAt) VALUES (?,'1970-01-01 00:00:00')";
	private static final String SELECT_COOLDOWN = "SELECT TIMESTAMPDIFF(SECOND, lastRatedAt, CURRENT_TIMESTAMP) AS secondsSinceRated FROM character_lasthero_roster_cooldown WHERE rosterHash=? FOR UPDATE";
	private static final String UPDATE_COOLDOWN = "UPDATE character_lasthero_roster_cooldown SET lastRatedAt=CURRENT_TIMESTAMP WHERE rosterHash=?";
	private static final Location[] SPAWNS =
	{
		new Location(147250, 46722, -3416, 0),
		new Location(148250, 46722, -3416, 0),
		new Location(149250, 46722, -3416, 0),
		new Location(150250, 46722, -3416, 32768),
		new Location(151250, 46722, -3416, 32768),
		new Location(147750, 47722, -3416, 0),
		new Location(148750, 47722, -3416, 0),
		new Location(150750, 47722, -3416, 32768),
		new Location(151750, 47722, -3416, 32768),
		new Location(149750, 45722, -3416, 16384)
	};
	private static final Location RETURN_FALLBACK = new Location(83425, 148585, -3406);
	private static final Location MANAGER_SPAWN = new Location(83600, 148585, -3406, 32938);
	private static final ConcurrentLinkedQueue<Player> WAITING = new ConcurrentLinkedQueue<>();
	private static final Set<Integer> WAITING_IDS = ConcurrentHashMap.newKeySet();
	private static final Map<Integer, HeroMatch> MATCHES = new ConcurrentHashMap<>();
	private static volatile boolean ACTIVE;
	private static boolean START_SCHEDULED;
	private static volatile long START_GENERATION;
	private static Npc MANAGER_NPC;

	private LastHero()
	{
		addTalkId(MANAGER_NPC_ID);
		addStartNpc(MANAGER_NPC_ID);
		addFirstTalkId(MANAGER_NPC_ID);
		// These matchmaking queues are always available after the script loads.
		eventStart(null);
	}

	@Override
	public synchronized boolean eventStart(Player eventMaker)
	{
		if (ACTIVE) return false;
		clearQueue("Last Hero queue was reset.");
		START_GENERATION++;
		START_SCHEDULED = false;
		ACTIVE = true;
		MANAGER_NPC = addSpawn(MANAGER_NPC_ID, MANAGER_SPAWN, false, 0);
		return true;
	}

	@Override
	public synchronized boolean eventStop()
	{
		ACTIVE = false;
		START_GENERATION++;
		START_SCHEDULED = false;
		clearQueue("Last Hero has been stopped.");
		for (HeroMatch match : Set.copyOf(MATCHES.values())) finishMatch(match, null, false);
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
		if (bypass == null) return false;
		final String command = bypass.startsWith("LastHero ") ? bypass.substring("LastHero ".length()) : bypass;
		switch (command)
		{
			case "Join": joinQueue(player); return true;
			case "Leave": leaveQueue(player); return true;
			case "Status":
				if (player != null) player.sendMessage("Last Hero: " + (ACTIVE ? "Open" : "Closed") + ". Queue: " + WAITING.size() + "/" + MAX_PLAYERS + ".");
				return true;
			default: return false;
		}
	}

	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ("Join".equals(event)) joinQueue(player);
		else if ("Leave".equals(event)) leaveQueue(player);
		else if ("Status".equals(event) && (player != null)) player.sendMessage("Last Hero: " + (ACTIVE ? "Open" : "Closed") + ". Queue: " + WAITING.size() + "/" + MAX_PLAYERS + ".");
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

	private synchronized void joinQueue(Player p)
	{
		if ((p == null) || !ACTIVE)
		{
			if (p != null) p.sendMessage("Last Hero is currently closed.");
			return;
		}
		if (!eligible(p, true)) return;
		if (WAITING.size() >= MAX_PLAYERS)
		{
			p.sendMessage("Last Hero queue is full.");
			return;
		}
		if (!WAITING_IDS.add(p.getObjectId()))
		{
			p.sendMessage("You are already waiting for Last Hero.");
			return;
		}
		WAITING.add(p);
		p.setRegisteredOnEvent(true);
		p.sendMessage("Joined Last Hero queue. Players waiting: " + WAITING.size() + ".");
		scheduleStartIfReady();
	}

	private synchronized void leaveQueue(Player p)
	{
		if (p == null) return;
		if (WAITING_IDS.remove(p.getObjectId()))
		{
			WAITING.remove(p);
			p.setRegisteredOnEvent(false);
			p.sendMessage("You left the Last Hero queue.");
		}
		else p.sendMessage("You are not waiting for Last Hero.");
	}

	private synchronized void scheduleStartIfReady()
	{
		if (!ACTIVE || START_SCHEDULED || (WAITING.size() < MIN_PLAYERS)) return;
		START_SCHEDULED = true;
		final long generation = START_GENERATION;
		for (Player p : WAITING) p.sendMessage("Last Hero starts in 15 seconds. Join now to enter this round.");
		ThreadPool.schedule(() ->
		{
			synchronized (LastHero.this)
			{
				if (generation != START_GENERATION)
				{
					return;
				}
				START_SCHEDULED = false;
				startMatch();
			}
		}, 15000);
	}

	private synchronized void startMatch()
	{
		if (!ACTIVE || WAITING.size() < MIN_PLAYERS) return;
		final List<Player> players = new ArrayList<>(MAX_PLAYERS);
		while ((players.size() < MAX_PLAYERS) && !WAITING.isEmpty())
		{
			final Player p = WAITING.poll();
			if (p == null) continue;
			WAITING_IDS.remove(p.getObjectId());
			p.setRegisteredOnEvent(false);
			if (eligible(p, false)) players.add(p);
			else p.sendMessage("Removed from Last Hero queue because you are no longer eligible.");
		}
		if (players.size() < MIN_PLAYERS)
		{
			for (Player p : players)
			{
				if (eligible(p, false) && WAITING_IDS.add(p.getObjectId()))
				{
					WAITING.add(p);
					p.setRegisteredOnEvent(true);
				}
			}
			scheduleStartIfReady();
			return;
		}
		final HeroMatch match = new HeroMatch(players);
		if (!match.start())
		{
			for (Player p : players)
			{
				p.sendMessage("Last Hero could not start; you were returned to the queue.");
				if (ACTIVE && eligible(p, false) && WAITING_IDS.add(p.getObjectId()))
				{
					WAITING.add(p);
					p.setRegisteredOnEvent(true);
				}
			}
			scheduleStartIfReady();
			return;
		}
		for (Player p : players)
		{
			MATCHES.put(p.getObjectId(), match);
			p.setOnEvent(true);
			p.sendMessage("Last Hero started! Be the last player alive.");
		}
		match.started = true;
		ThreadPool.schedule(() -> finishMatch(match, null, true), MATCH_TIMEOUT_MILLIS);
		resolveWinner(match);
		scheduleStartIfReady();
	}

	@RegisterEvent(EventType.ON_CREATURE_DEATH)
	private void onCreatureDeath(OnCreatureDeath event)
	{
		final Creature target = event.getTarget();
		if ((target == null) || !target.isPlayer()) return;
		final HeroMatch match = MATCHES.get(target.asPlayer().getObjectId());
		if ((match != null) && match.started && !match.finished.get())
		{
			ThreadPool.schedule(() -> resolveWinner(match), 1000);
		}
	}

	@RegisterEvent(EventType.ON_CREATURE_TELEPORTED)
	private void onCreatureTeleported(OnCreatureTeleported event)
	{
		final Creature creature = event.getCreature();
		if ((creature == null) || !creature.isPlayer()) return;
		final Player player = creature.asPlayer();
		final HeroMatch match = MATCHES.get(player.getObjectId());
		if ((match != null) && match.started && !match.finished.get() && (match.world != null) && (player.getInstanceId() != match.world.getInstanceId()))
		{
			match.eliminated.add(player.getObjectId());
			ThreadPool.schedule(() -> resolveWinner(match), 100);
		}
	}

	private void resolveWinner(HeroMatch match)
	{
		if (!match.started || match.finished.get()) return;
		final List<Player> alive = match.players.stream().filter(p -> !match.eliminated.contains(p.getObjectId()) && (p.isOnlineInt() == 1) && !p.isDead() && (match.world != null) && (p.getInstanceId() == match.world.getInstanceId())).toList();
		if (alive.size() == 1) finishMatch(match, alive.get(0), false);
		else if (alive.isEmpty()) finishMatch(match, null, false);
	}

	@RegisterEvent(EventType.ON_PLAYER_LOGOUT)
	private void onPlayerLogout(OnPlayerLogout event)
	{
		final Player p = event.getPlayer();
		if (p == null) return;
		synchronized (this)
		{
			if (WAITING_IDS.remove(p.getObjectId()))
			{
				WAITING.remove(p);
				p.setRegisteredOnEvent(false);
			}
		}
		final HeroMatch match = MATCHES.get(p.getObjectId());
		if (match != null)
		{
			// A logout event can fire before isOnlineInt() flips to offline; mark the player eliminated explicitly.
			match.eliminated.add(p.getObjectId());
			ThreadPool.schedule(() -> resolveWinner(match), 100);
		}
	}

	@RegisterEvent(EventType.ON_PLAYER_LOGIN)
	private void onPlayerLogin(OnPlayerLogin event)
	{
		final Player p = event.getPlayer();
		if (p == null) return;
		final Location saved = loadReturnLocation(p.getObjectId());
		if (saved != null)
		{
			ThreadPool.schedule(() ->
			{
				if (p.isOnlineInt() == 1)
				{
					final Location dest = ((saved.getInstanceId() > 0) && (InstanceManager.getInstance().getInstance(saved.getInstanceId()) == null)) ? RETURN_FALLBACK : saved;
					p.teleToLocation(dest, true);
					if (isAtLocation(p, dest))
					{
						deleteReturnLocation(p.getObjectId());
					}
					else
					{
						LOGGER.warning("Last Hero could not restore player " + p.getObjectId() + " to the saved return location; recovery record retained.");
					}
				}
			}, 2000);
		}
	}

	private static boolean isAtLocation(Player player, Location destination)
	{
		return (player != null) && (destination != null) && (player.getInstanceId() == destination.getInstanceId()) && (Math.abs(player.getX() - destination.getX()) <= 500) && (Math.abs(player.getY() - destination.getY()) <= 500) && (Math.abs(player.getZ() - destination.getZ()) <= 500);
	}

	private void finishMatch(HeroMatch match, Player winner, boolean timeout)
	{
		if (!match.finished.compareAndSet(false, true)) return;
		if ((winner != null) && ((winner.isDead()) || (winner.isOnlineInt() != 1) || (match.world == null) || (winner.getInstanceId() != match.world.getInstanceId()))) winner = null;
		if (winner != null)
		{
			final int rated = recordResult(match, winner);
			for (Player p : match.players) p.sendMessage(rated == 1 ? "Last Hero: result and rating saved." : rated == 0 ? "Last Hero: repeat-roster cooldown active; this match did not change rating or rated statistics." : "Last Hero: result could not be saved; contact an administrator.");
		}
		for (Player p : match.players)
		{
			MATCHES.remove(p.getObjectId(), match);
			p.setOnEvent(false);
			p.setRegisteredOnEvent(false);
			if (p.isOnlineInt() == 1)
			{
				if (p.isDead()) p.doRevive();
				final Location dest = match.returnLocations.get(p.getObjectId());
				final Location safeDestination = (dest == null) || ((dest.getInstanceId() > 0) && (InstanceManager.getInstance().getInstance(dest.getInstanceId()) == null)) ? RETURN_FALLBACK : dest;
				p.teleToLocation(safeDestination, true);
				if (isAtLocation(p, safeDestination))
				{
					deleteReturnLocation(p.getObjectId());
				}
				else
				{
					LOGGER.warning("Last Hero return teleport failed for " + p.getObjectId() + "; recovery record retained.");
				}
				p.sendMessage(winner == null ? (timeout ? "Last Hero ended without a winner (time limit)." : "Last Hero ended without a winner.") : (p == winner ? "You won Last Hero!" : "Last Hero winner: " + winner.getName() + "."));
			}
		}
		if (match.world != null) match.world.destroy();
	}

	private static int recordResult(HeroMatch match, Player winner)
	{
		try
		{
			final String rosterHash = rosterHash(match.players);
			try (Connection con = DatabaseFactory.getConnection())
			{
				con.setAutoCommit(false);
				try
				{
					try (PreparedStatement ps = con.prepareStatement(INSERT_STATS))
					{
						for (Player p : match.players) { ps.setInt(1, p.getObjectId()); ps.executeUpdate(); }
					}
					try (PreparedStatement ps = con.prepareStatement(ENSURE_COOLDOWN))
					{
						ps.setString(1, rosterHash);
						ps.executeUpdate();
					}
					final long secondsSinceRated;
					try (PreparedStatement ps = con.prepareStatement(SELECT_COOLDOWN))
					{
						ps.setString(1, rosterHash);
						try (ResultSet rs = ps.executeQuery())
						{
							if (!rs.next()) throw new IllegalStateException("Missing Last Hero cooldown row.");
							secondsSinceRated = rs.getLong("secondsSinceRated");
						}
					}
					if (secondsSinceRated < (ROSTER_COOLDOWN_MILLIS / 1000L))
					{
						con.rollback();
						return 0;
					}
					final Map<Integer, Integer> ratings = new ConcurrentHashMap<>();
					try (PreparedStatement ps = con.prepareStatement(SELECT_RATING))
					{
						for (Player p : match.players)
						{
							ps.setInt(1, p.getObjectId());
							try (ResultSet rs = ps.executeQuery())
							{
								if (!rs.next()) throw new IllegalStateException("Missing Last Hero rating row.");
								ratings.put(p.getObjectId(), rs.getInt("rating"));
							}
						}
					}
					final double averageOpponent = match.players.stream().filter(p -> p != winner).mapToInt(p -> ratings.get(p.getObjectId())).average().orElse(1000);
					final double expectedWinner = 1.0 / (1.0 + Math.pow(10.0, (averageOpponent - ratings.get(winner.getObjectId())) / 400.0));
					// Distribute one integer winner delta across all opponents so rounding cannot create or destroy rating points.
					final int winnerDelta = (int) Math.round(K_FACTOR * (1.0 - expectedWinner));
					final List<Player> opponents = new ArrayList<>(match.players.stream().filter(p -> p != winner).toList());
					java.util.Collections.shuffle(opponents);
					final int baseLoss = winnerDelta / opponents.size();
					final int remainder = winnerDelta % opponents.size();
					final Map<Integer, Integer> losses = new ConcurrentHashMap<>();
					for (int i = 0; i < opponents.size(); i++)
					{
						losses.put(opponents.get(i).getObjectId(), baseLoss + ((i < remainder) ? 1 : 0));
					}
					for (Player p : match.players)
					{
						if (p == winner)
						{
							try (PreparedStatement ps = con.prepareStatement(WIN_SQL))
							{
								ps.setInt(1, ratings.get(p.getObjectId()) + winnerDelta);
								ps.setInt(2, p.getObjectId());
								ps.executeUpdate();
							}
						}
						else
						{
							try (PreparedStatement ps = con.prepareStatement(LOSS_SQL))
							{
								ps.setInt(1, ratings.get(p.getObjectId()) - losses.get(p.getObjectId()));
								ps.setInt(2, p.getObjectId());
								ps.executeUpdate();
							}
						}
					}
					try (PreparedStatement ps = con.prepareStatement(UPDATE_COOLDOWN))
					{
						ps.setString(1, rosterHash);
						ps.executeUpdate();
					}
					con.commit();
					return 1;
				}
				catch (Exception e) { con.rollback(); throw e; }
				finally { con.setAutoCommit(true); }
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("Last Hero rating transaction failed: " + e.getMessage());
			return -1;
		}
	}

	private static String rosterHash(List<Player> players) throws Exception
	{
		final String ids = players.stream().map(Player::getObjectId).sorted(Comparator.naturalOrder()).map(String::valueOf).reduce((a, b) -> a + ":" + b).orElse("");
		final byte[] digest = MessageDigest.getInstance("SHA-256").digest(ids.getBytes(StandardCharsets.UTF_8));
		final StringBuilder out = new StringBuilder(64);
		for (byte b : digest) out.append(String.format("%02x", b & 0xff));
		return out.toString();
	}

	private static boolean saveReturn(int charId, Location loc)
	{
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(UPSERT_RETURN))
		{
			ps.setInt(1, charId); ps.setInt(2, loc.getX()); ps.setInt(3, loc.getY()); ps.setInt(4, loc.getZ()); ps.setInt(5, loc.getHeading()); ps.setInt(6, loc.getInstanceId());
			ps.executeUpdate(); return true;
		}
		catch (Exception e) { LOGGER.warning("Last Hero could not save return location for " + charId + ": " + e.getMessage()); return false; }
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
		catch (Exception e) { LOGGER.warning("Last Hero could not load return location for " + charId + ": " + e.getMessage()); }
		return null;
	}

	private static void deleteReturnLocation(int charId)
	{
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(DELETE_RETURN))
		{
			ps.setInt(1, charId);
			ps.executeUpdate();
		}
		catch (Exception e) { LOGGER.warning("Last Hero could not delete return location for " + charId + ": " + e.getMessage()); }
	}

	private boolean eligible(Player p, boolean notify)
	{
		if ((p.isOnlineInt() != 1) || p.isRegisteredOnEvent() || p.isOnEvent() || p.isInDuel() || p.isInOlympiadMode() || p.isInInstance() || p.isInSiege() || p.isFishing() || p.isInCombat() || (p.getPvpFlag() != 0) || (p.getKarma() > 0) || p.isCursedWeaponEquipped() || p.isFlying() || !p.isInventoryUnder80(false) || (p.getWeightPenalty() != 0))
		{
			if (notify) p.sendMessage("You cannot join Last Hero in your current state.");
			return false;
		}
		return true;
	}

	private void clearQueue(String message)
	{
		for (Player p : WAITING)
		{
			if (p != null) { p.setRegisteredOnEvent(false); if (message != null) p.sendMessage(message); }
		}
		WAITING.clear();
		WAITING_IDS.clear();
	}

	private static final class HeroMatch
	{
		private final List<Player> players;
		private final Map<Integer, Location> returnLocations = new ConcurrentHashMap<>();
		private final Set<Integer> eliminated = ConcurrentHashMap.newKeySet();
		private final AtomicBoolean finished = new AtomicBoolean();
		private volatile boolean started;
		private InstanceWorld world;

		private HeroMatch(List<Player> players)
		{
			this.players = List.copyOf(players);
			for (Player p : players) returnLocations.put(p.getObjectId(), new Location(p));
		}

		private boolean start()
		{
			try
			{
				for (Player p : players)
				{
					if (!saveReturn(p.getObjectId(), returnLocations.get(p))) throw new IllegalStateException("Could not persist all return locations.");
				}
				final Instance instance = InstanceManager.getInstance().createDynamicInstance(INSTANCE_TEMPLATE_ID);
				if ((instance == null) || (instance.getId() <= 0) || (instance.getTemplateId() != INSTANCE_TEMPLATE_ID) || (instance.getExitLoc() == null))
				{
					if (instance != null) InstanceManager.getInstance().destroyInstance(instance.getId());
					throw new IllegalStateException("Could not load a valid Last Hero instance template.");
				}
				world = new InstanceWorld();
				world.setInstance(instance);
				InstanceManager.getInstance().addWorld(world);
				for (Player p : players) world.addAllowed(p);
				for (int i = 0; i < players.size(); i++) players.get(i).teleToLocation(SPAWNS[i], world.getInstanceId(), 50);
				for (Player p : players)
				{
					if ((p.isOnlineInt() != 1) || (p.getInstanceId() != world.getInstanceId())) throw new IllegalStateException("Participant did not enter the Last Hero instance.");
				}
				return true;
			}
			catch (Exception e)
			{
				LOGGER.warning("Last Hero startup failed: " + e.getMessage());
				for (Player p : players)
				{
					if (p.isOnlineInt() == 1)
					{
						final Location loc = returnLocations.get(p.getObjectId());
						final Location destination = loc != null ? loc : RETURN_FALLBACK;
						p.teleToLocation(destination, true);
						if (isAtLocation(p, destination)) deleteReturnLocation(p.getObjectId());
					}
				}
				if (world != null) world.destroy();
				return false;
			}
		}
	}
	/**
	 * Stop this mode before unload, without removing a replacement script from ScriptManager.
	 * @param removeFromList whether the base script should be removed from the manager
	 */
	@Override
	public void unload(boolean removeFromList)
	{
		eventStop();
		super.unload(removeFromList && (ScriptManager.getInstance().getScript(getName()) == this));
	}

	/**
	 * Loads the event script and registers its NPC interactions.
	 * @param args unused script arguments
	 */
	public static void main(String[] args)
	{
		new LastHero();
	}

}
