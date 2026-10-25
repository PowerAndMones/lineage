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
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package handlers.bypass.communityboard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.config.custom.PremiumSystemConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.PcCafePointsManager;
import org.l2jmobius.gameserver.managers.PremiumManager;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.SellList;
import org.l2jmobius.gameserver.network.serverpackets.ShowBoard;

/**
 * Home board.
 * @author Zoey76, Mobius
 */
public class HomeBoard implements IParseBoardHandler
{
	private static final DateTimeFormatter PREMIUM_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
	
	// SQL Queries
	private static final String COUNT_FAVORITES = "SELECT COUNT(*) AS favorites FROM `bbs_favorites` WHERE `playerId`=?";
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	
	private static final String[] COMMANDS =
	{
		"_bbshome",
		"_bbstop",
		"_bbspvp",
		"_bbspvptop",
	};
	
	private static final String[] CUSTOM_COMMANDS =
	{
		PremiumSystemConfig.PREMIUM_SYSTEM_ENABLED && CommunityBoardConfig.COMMUNITY_PREMIUM_SYSTEM_ENABLED ? "_bbspremium" : null,
		CommunityBoardConfig.COMMUNITYBOARD_ENABLE_MULTISELLS ? "_bbsexcmultisell" : null,
		CommunityBoardConfig.COMMUNITYBOARD_ENABLE_MULTISELLS ? "_bbsmultisell" : null,
		CommunityBoardConfig.COMMUNITYBOARD_ENABLE_MULTISELLS ? "_bbssell" : null,
		CommunityBoardConfig.COMMUNITYBOARD_ENABLE_TELEPORTS ? "_bbsteleport" : null,
		CommunityBoardConfig.COMMUNITYBOARD_ENABLE_BUFFS ? "_bbsbuff" : null,
		CommunityBoardConfig.COMMUNITYBOARD_ENABLE_HEAL ? "_bbsheal" : null,
		CommunityBoardConfig.COMMUNITYBOARD_ENABLE_DELEVEL ? "_bbsdelevel" : null
	};
	
	private static final BiPredicate<String, Player> COMBAT_CHECK = (command, player) ->
	{
		boolean commandCheck = false;
		for (String c : CUSTOM_COMMANDS)
		{
			if ((c != null) && command.startsWith(c))
			{
				commandCheck = true;
				break;
			}
		}
		
		return commandCheck && (player.isCastingNow() || player.isCastingSimultaneouslyNow() || player.isInCombat() || player.isInDuel() || player.isInOlympiadMode() || player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || (player.getPvpFlag() > 0) || player.isAlikeDead() || player.isOnEvent() || player.isInStoreMode());
	};
	
	private static final Predicate<Player> KARMA_CHECK = player -> CommunityBoardConfig.COMMUNITYBOARD_KARMA_DISABLED && (player.getKarma() > 0);
	
	@Override
	public String[] getCommandList()
	{
		final List<String> commands = new ArrayList<>();
		commands.addAll(Arrays.asList(COMMANDS));
		commands.addAll(Arrays.asList(CUSTOM_COMMANDS));
		return commands.stream().filter(Objects::nonNull).toArray(String[]::new);
	}
	
	@Override
	public boolean onCommand(String command, Player player)
	{
		// Old custom conditions check move to here.
		if (CommunityBoardConfig.COMMUNITYBOARD_COMBAT_DISABLED && COMBAT_CHECK.test(command, player))
		{
			player.sendMessage("You can't use the Community Board right now.");
			return false;
		}
		
		if (KARMA_CHECK.test(player))
		{
			player.sendMessage("Players with Karma cannot use the Community Board.");
			return false;
		}
		
		if (CommunityBoardConfig.COMMUNITYBOARD_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE))
		{
			player.sendMessage("Community Board cannot be used out of peace zone.");
			return false;
		}
		
		String returnHtml = null;
		String navigation = null;
		
		if (CommunityBoardConfig.CUSTOM_CB_ENABLED)
		{
			navigation = HtmCache.getInstance().getHtm(player, NAVIGATION_PATH);
		}
		
		if (command.equals("_bbshome") || command.equals("_bbstop"))
		{
			final String customPath = CommunityBoardConfig.CUSTOM_CB_ENABLED ? "Custom/" : "";
			CommunityBoardHandler.getInstance().addBypass(player, "Home", command);
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/" + customPath + "home.html");
			if (!CommunityBoardConfig.CUSTOM_CB_ENABLED)
			{
				returnHtml = returnHtml.replace("%fav_count%", Integer.toString(getFavoriteCount(player)));
				returnHtml = returnHtml.replace("%region_count%", Integer.toString(getRegionCount(player)));
				returnHtml = returnHtml.replace("%clan_count%", Integer.toString(ClanTable.getInstance().getClanCount()));
			}
		}
		else if (command.startsWith("_bbstop;"))
		{
			final String customPath = CommunityBoardConfig.CUSTOM_CB_ENABLED ? "Custom/" : "";
			final String path = command.replace("_bbstop;", "");
			if (!path.isEmpty() && path.endsWith(".html"))
			{
				returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/" + customPath + path);
			}
		}
		else if (command.equals("_bbspvp") || command.equals("_bbspvptop"))
		{
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/pvp.html");
			if (returnHtml != null)
			{
				returnHtml = returnHtml.replace("%my_stats%", getPvpStats(player));
				returnHtml = returnHtml.replace("%top_list%", getPvpTop());
				returnHtml = returnHtml.replace("%arena_stats%", getArenaStats(player));
				returnHtml = returnHtml.replace("%arena_top%", getArenaTop());
			}
		}
		else if (command.startsWith("_bbsmultisell"))
		{
			final String fullBypass = command.replace("_bbsmultisell;", "");
			final String[] buypassOptions = fullBypass.split(",");
			final int multisellId = Integer.parseInt(buypassOptions[0]);
			final String page = buypassOptions[1];
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
			ThreadPool.schedule(() -> MultisellData.getInstance().separateAndSend(multisellId, player, null, false), 100);
		}
		else if (command.startsWith("_bbsexcmultisell"))
		{
			final String fullBypass = command.replace("_bbsexcmultisell;", "");
			final String[] buypassOptions = fullBypass.split(",");
			final int multisellId = Integer.parseInt(buypassOptions[0]);
			final String page = buypassOptions[1];
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
			ThreadPool.schedule(() -> MultisellData.getInstance().separateAndSend(multisellId, player, null, true), 100);
		}
		else if (command.startsWith("_bbssell"))
		{
			final String page = command.replace("_bbssell;", "");
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
			ThreadPool.schedule(() -> player.sendPacket(new SellList(player)), 100);
		}
		else if (command.startsWith("_bbsteleport"))
		{
			final String teleBuypass = command.replace("_bbsteleport;", "");
			if (player.getInventory().getInventoryItemCount(CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, -1) < CommunityBoardConfig.COMMUNITYBOARD_TELEPORT_PRICE)
			{
				player.sendMessage("Not enough currency!");
			}
			else if (CommunityBoardConfig.COMMUNITY_AVAILABLE_TELEPORTS.get(teleBuypass) != null)
			{
				player.disableAllSkills();
				player.sendPacket(new ShowBoard());
				player.destroyItemByItemId(ItemProcessType.FEE, CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, CommunityBoardConfig.COMMUNITYBOARD_TELEPORT_PRICE, player, true);
				player.setIn7sDungeon(false);
				player.setInstanceId(0);
				player.teleToLocation(CommunityBoardConfig.COMMUNITY_AVAILABLE_TELEPORTS.get(teleBuypass), 0);
				ThreadPool.schedule(player::enableAllSkills, 3000);
			}
		}
		else if (command.startsWith("_bbsbuff"))
		{
			final String fullBypass = command.replace("_bbsbuff;", "");
			final String[] buypassOptions = fullBypass.split(";");
			final int buffCount = buypassOptions.length - 1;
			final String page = buypassOptions[buffCount];
			if (player.getInventory().getInventoryItemCount(CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, -1) < (CommunityBoardConfig.COMMUNITYBOARD_BUFF_PRICE * buffCount))
			{
				player.sendMessage("Not enough currency!");
			}
			else
			{
				player.destroyItemByItemId(ItemProcessType.FEE, CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, CommunityBoardConfig.COMMUNITYBOARD_BUFF_PRICE * buffCount, player, true);
				final Summon pet = player.getSummon();
				final List<Creature> targets = new ArrayList<>(4);
				targets.add(player);
				if (pet != null)
				{
					targets.add(pet);
				}
				
				for (int i = 0; i < buffCount; i++)
				{
					final Skill skill = SkillData.getInstance().getSkill(Integer.parseInt(buypassOptions[i].split(",")[0]), Integer.parseInt(buypassOptions[i].split(",")[1]));
					if (!CommunityBoardConfig.COMMUNITY_AVAILABLE_BUFFS.contains(skill.getId()))
					{
						continue;
					}
					
					for (Creature target : targets)
					{
						skill.applyEffects(player, target);
						if (CommunityBoardConfig.COMMUNITYBOARD_CAST_ANIMATIONS)
						{
							player.sendPacket(new MagicSkillUse(player, target, skill.getId(), skill.getLevel(), skill.getHitTime(), skill.getReuseDelay()));
							
							// not recommend broadcast
							// player.broadcastPacket(new MagicSkillUse(player, target, skill.getId(), skill.getLevel(), skill.getHitTime(), skill.getReuseDelay()));
						}
					}
				}
			}
			
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
		}
		else if (command.startsWith("_bbsheal"))
		{
			final String page = command.replace("_bbsheal;", "");
			if (player.getInventory().getInventoryItemCount(CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, -1) < (CommunityBoardConfig.COMMUNITYBOARD_HEAL_PRICE))
			{
				player.sendMessage("Not enough currency!");
			}
			else
			{
				player.destroyItemByItemId(ItemProcessType.FEE, CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, CommunityBoardConfig.COMMUNITYBOARD_HEAL_PRICE, player, true);
				player.setCurrentHp(player.getMaxHp());
				player.setCurrentMp(player.getMaxMp());
				player.setCurrentCp(player.getMaxCp());
				if (player.hasSummon())
				{
					player.getSummon().setCurrentHp(player.getSummon().getMaxHp());
					player.getSummon().setCurrentMp(player.getSummon().getMaxMp());
					player.getSummon().setCurrentCp(player.getSummon().getMaxCp());
				}
				
				player.updateUserInfo();
				player.sendMessage("You used heal!");
			}
			
			returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/" + page + ".html");
		}
		else if (command.equals("_bbsdelevel"))
		{
			if (player.getInventory().getInventoryItemCount(CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, -1) < CommunityBoardConfig.COMMUNITYBOARD_DELEVEL_PRICE)
			{
				player.sendMessage("Not enough currency!");
			}
			else if (player.getLevel() == 1)
			{
				player.sendMessage("You are at minimum level!");
			}
			else
			{
				player.destroyItemByItemId(ItemProcessType.FEE, CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, CommunityBoardConfig.COMMUNITYBOARD_DELEVEL_PRICE, player, true);
				final int newLevel = player.getLevel() - 1;
				player.setExp(ExperienceData.getInstance().getExpForLevel(newLevel));
				player.getStat().setLevel((byte) newLevel);
				player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
				player.setCurrentCp(player.getMaxCp());
				player.broadcastUserInfo();
				player.checkPlayerSkills(); // Adjust skills according to new level.
				returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/delevel/complete.html");
			}
		}
		else if (command.startsWith("_bbspremium"))
		{
			final String fullBypass = command.replace("_bbspremium;", "");
			final String[] buypassOptions = fullBypass.split(",");
			final int premiumDays = Integer.parseInt(buypassOptions[0]);
			if ((premiumDays < 1) || (premiumDays > 30) || (player.getInventory().getInventoryItemCount(CommunityBoardConfig.COMMUNITY_PREMIUM_COIN_ID, -1) < (CommunityBoardConfig.COMMUNITY_PREMIUM_PRICE_PER_DAY * premiumDays)))
			{
				player.sendMessage("Not enough currency!");
			}
			else
			{
				final String premiumKey = PremiumSystemConfig.ACCOUNT_WIDE_PREMIUM ? player.getAccountName() : player.getName();
				player.destroyItemByItemId(ItemProcessType.FEE, CommunityBoardConfig.COMMUNITY_PREMIUM_COIN_ID, CommunityBoardConfig.COMMUNITY_PREMIUM_PRICE_PER_DAY * premiumDays, player, true);
				PremiumManager.getInstance().addPremiumTime(premiumKey, premiumDays, TimeUnit.DAYS);
				player.sendMessage("You will now have premium status until " + PREMIUM_FORMAT.format(Instant.ofEpochMilli(PremiumManager.getInstance().getPremiumExpiration(premiumKey)).atZone(java.time.ZoneId.systemDefault())) + ".");
				if (PremiumSystemConfig.PC_CAFE_RETAIL_LIKE)
				{
					PcCafePointsManager.getInstance().run(player);
				}
				
				returnHtml = HtmCache.getInstance().getHtm(player, "data/html/CommunityBoard/Custom/premium/thankyou.html");
			}
		}
		
		if (returnHtml != null)
		{
			if (CommunityBoardConfig.CUSTOM_CB_ENABLED)
			{
				returnHtml = returnHtml.replace("%navigation%", navigation);
			}
			
			CommunityBoardHandler.separateAndSend(returnHtml, player);
		}
		
		return false;
	}
	
	/**
	 * Returns the current player's custom PvP statistics as Community Board HTML.
	 * @param player the player
	 * @return HTML fragment
	 */
	private static String getPvpStats(Player player)
	{
		final String query = "SELECT totalKills, totalDeaths, currentStreak, bestStreak, pvpPoints FROM character_pvp_stats WHERE charId = ?";
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(query))
		{
			ps.setInt(1, player.getObjectId());
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					final int points = rs.getInt("pvpPoints");
					final int kills = rs.getInt("totalKills");
					final int deaths = rs.getInt("totalDeaths");
					final int currentStreak = rs.getInt("currentStreak");
					final int bestStreak = rs.getInt("bestStreak");
					return "<table width=450><tr><td width=110><font color=LEVEL>PvP points:</font></td><td>" + points + "</td><td width=110><font color=LEVEL>PvP kills:</font></td><td>" + kills + "</td></tr><tr><td><font color=LEVEL>Deaths:</font></td><td>" + deaths + "</td><td><font color=LEVEL>Current streak:</font></td><td>" + currentStreak + "</td><td><font color=LEVEL>Best streak:</font></td><td>" + bestStreak + "</td></tr></table><br><font color=LEVEL>PvP ACHIEVEMENTS</font><br>" + getPvpAchievements(kills, bestStreak, points);
				}
			}
		}
		catch (java.sql.SQLException e)
		{
			player.sendMessage("PvP statistics table is missing or unavailable. Install character_pvp_stats.sql first.");
			return "<font color=FF6666>PvP statistics are temporarily unavailable.</font>";
		}
		return "<font color=AAAAAA>No PvP statistics yet. Win your first PvP fight to start tracking!</font>";
	}

	/**
	 * Builds non-announced PvP achievements directly from saved statistics.
	 * @param kills total custom PvP kills
	 * @param bestStreak best kill streak
	 * @param points earned PvP points
	 * @return achievement HTML
	 */
	private static String getPvpAchievements(int kills, int bestStreak, int points)
	{
		final StringBuilder html = new StringBuilder("<table width=450>");
		html.append("<tr><td width=220><font color=LEVEL>Achievement</font></td><td><font color=LEVEL>Progress</font></td></tr>");
		appendAchievement(html, "First Blood", kills, 1);
		appendAchievement(html, "PvP Veteran", kills, 100);
		appendAchievement(html, "Arena Legend", kills, 500);
		appendAchievement(html, "War Machine", kills, 1000);
		appendAchievement(html, "On a Roll", bestStreak, 5);
		appendAchievement(html, "Unstoppable", bestStreak, 10);
		appendAchievement(html, "Legendary Streak", bestStreak, 20);
		appendAchievement(html, "Point Collector", points, 100);
		appendAchievement(html, "Point Hunter", points, 500);
		appendAchievement(html, "Point Master", points, 1000);
		return html.append("</table>").toString();
	}

	private static void appendAchievement(StringBuilder html, String name, int value, int required)
	{
		final boolean unlocked = value >= required;
		html.append("<tr><td><font color=").append(unlocked ? "LEVEL" : "AAAAAA").append(">").append(unlocked ? "[Unlocked] " : "[Locked] ").append(name).append("</font></td><td>").append(Math.min(value, required)).append(" / ").append(required).append("</td></tr>");
	}

	/**
	 * Returns the top ten PvP players as Community Board HTML.
	 * @return HTML fragment
	 */
	private static String getPvpTop()
	{
		final String query = "SELECT charName, totalKills, totalDeaths, bestStreak, pvpPoints FROM character_pvp_stats ORDER BY pvpPoints DESC, totalKills DESC, bestStreak DESC, charName ASC LIMIT 10";
		final StringBuilder html = new StringBuilder("<table width=450><tr><td width=35><font color=LEVEL>#</font></td><td width=150><font color=LEVEL>Player</font></td><td width=75><font color=LEVEL>Points</font></td><td width=65><font color=LEVEL>Kills</font></td><td width=65><font color=LEVEL>Deaths</font></td><td><font color=LEVEL>Best streak</font></td></tr>");
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(query);
			ResultSet rs = ps.executeQuery())
		{
			int rank = 1;
			while (rs.next())
			{
				html.append("<tr><td>").append(rank++).append("</td><td>").append(rs.getString("charName")).append("</td><td>").append(rs.getInt("pvpPoints")).append("</td><td>").append(rs.getInt("totalKills")).append("</td><td>").append(rs.getInt("totalDeaths")).append("</td><td>").append(rs.getInt("bestStreak")).append("</td></tr>");
			}
		}
		catch (java.sql.SQLException e)
		{
			return "<font color=FF6666>Ranking unavailable. Install character_pvp_stats.sql first.</font></table>";
		}
		if (html.toString().endsWith("</table>"))
		{
			// Header-only table means no PvP records exist yet.
			if (html.toString().contains("<tr><td width=35><font color=LEVEL>#</font></td>"))
			{
				// Keep the table valid; the first row is the header.
			}
		}
		return html.append("</table>").toString();
	}

	/**
	 * Returns the player's dedicated 1v1 Arena record.
	 * @param player the player
	 * @return HTML fragment
	 */
	private static String getArenaStats(Player player)
	{
		final String query = "SELECT matches, wins, losses, rating, winStreak, bestWinStreak FROM character_arena1v1_stats WHERE charId = ?";
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(query))
		{
			ps.setInt(1, player.getObjectId());
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					return "<table width=450><tr><td width=120><font color=LEVEL>Rating:</font></td><td>" + rs.getInt("rating") + "</td><td width=120><font color=LEVEL>Matches:</font></td><td>" + rs.getInt("matches") + "</td></tr><tr><td><font color=LEVEL>Wins:</font></td><td>" + rs.getInt("wins") + "</td><td><font color=LEVEL>Losses:</font></td><td>" + rs.getInt("losses") + "</td></tr><tr><td><font color=LEVEL>Win streak:</font></td><td>" + rs.getInt("winStreak") + "</td><td><font color=LEVEL>Best streak:</font></td><td>" + rs.getInt("bestWinStreak") + "</td></tr></table>";
				}
			}
		}
		catch (java.sql.SQLException e)
		{
			return "<font color=FF6666>1v1 rating unavailable. Install character_arena1v1_stats.sql first.</font>";
		}
		return "<font color=AAAAAA>No rated 1v1 matches yet. Join the Arena queue to begin.</font>";
	}

	/**
	 * Returns the top ten rated 1v1 players.
	 * @return HTML fragment
	 */
	private static String getArenaTop()
	{
		final String query = "SELECT c.char_name, s.rating, s.wins, s.losses, s.matches FROM character_arena1v1_stats s INNER JOIN characters c ON c.charId = s.charId ORDER BY s.rating DESC, s.wins DESC, s.matches DESC, c.char_name ASC LIMIT 10";
		final StringBuilder html = new StringBuilder("<table width=450><tr><td width=30><font color=LEVEL>#</font></td><td width=150><font color=LEVEL>Player</font></td><td width=65><font color=LEVEL>Rating</font></td><td width=55><font color=LEVEL>W</font></td><td width=55><font color=LEVEL>L</font></td><td><font color=LEVEL>Matches</font></td></tr>");
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(query);
			ResultSet rs = ps.executeQuery())
		{
			int rank = 1;
			while (rs.next())
			{
				html.append("<tr><td>").append(rank++).append("</td><td>").append(rs.getString("char_name")).append("</td><td>").append(rs.getInt("rating")).append("</td><td>").append(rs.getInt("wins")).append("</td><td>").append(rs.getInt("losses")).append("</td><td>").append(rs.getInt("matches")).append("</td></tr>");
			}
			if (rank == 1)
			{
				html.append("<tr><td colspan=6><font color=AAAAAA>No rated 1v1 matches yet.</font></td></tr>");
			}
		}
		catch (java.sql.SQLException e)
		{
			return "<font color=FF6666>1v1 leaderboard unavailable. Install character_arena1v1_stats.sql first.</font></table>";
		}
		return html.append("</table>").toString();
	}

	/**
	 * Gets the Favorite links for the given player.
	 * @param player the player
	 * @return the favorite links count
	 */
	private static int getFavoriteCount(Player player)
	{
		int count = 0;
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(COUNT_FAVORITES))
		{
			ps.setInt(1, player.getObjectId());
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					count = rs.getInt("favorites");
				}
			}
		}
		catch (Exception e)
		{
			LOG.warning(FavoriteBoard.class.getSimpleName() + ": Coudn't load favorites count for " + player);
		}
		
		return count;
	}
	
	/**
	 * Gets the registered regions count for the given player.
	 * @param player the player
	 * @return the registered regions count
	 */
	private static int getRegionCount(Player player)
	{
		return 0; // TODO: Implement.
	}
}
