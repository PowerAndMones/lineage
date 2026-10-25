/*
 * This file is part of the L2J Mobius project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.handler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.data.SchemeBufferTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.util.HtmlUtil;

/**
 * Community Board handler.
 * @author Zoey76
 */
public class CommunityBoardHandler implements IHandler<IParseBoardHandler, String>
{
	private static final Logger LOG = Logger.getLogger(CommunityBoardHandler.class.getName());
	/** The registered handlers. */
	private final Map<String, IParseBoardHandler> _datatable = new HashMap<>();
	/** The bypasses used by the players. */
	private final Map<Integer, String> _bypasses = new ConcurrentHashMap<>();
	
	protected CommunityBoardHandler()
	{
		// Prevent external initialization.
	}
	
	@Override
	public void registerHandler(IParseBoardHandler handler)
	{
		for (String cmd : handler.getCommandList())
		{
			_datatable.put(cmd.toLowerCase(), handler);
		}
	}
	
	@Override
	public synchronized void removeHandler(IParseBoardHandler handler)
	{
		for (String cmd : handler.getCommandList())
		{
			_datatable.remove(cmd.toLowerCase());
		}
	}
	
	@Override
	public IParseBoardHandler getHandler(String cmd)
	{
		for (IParseBoardHandler cb : _datatable.values())
		{
			for (String command : cb.getCommandList())
			{
				if (cmd.toLowerCase().startsWith(command.toLowerCase()))
				{
					return cb;
				}
			}
		}
		
		return null;
	}
	
	@Override
	public int size()
	{
		return _datatable.size();
	}
	
	/**
	 * Verifies if the string is a registered community board command.
	 * @param cmd the command to verify
	 * @return {@code true} if the command has been registered, {@code false} otherwise
	 */
	public boolean isCommunityBoardCommand(String cmd)
	{
		return getHandler(cmd) != null;
	}
	
	/**
	 * Parses a community board command.
	 * @param command the command
	 * @param player the player
	 */
	public void handleParseCommand(String command, Player player)
	{
		if (player == null)
		{
			return;
		}
		
		if (!GeneralConfig.ENABLE_COMMUNITY_BOARD)
		{
			player.sendPacket(SystemMessageId.THE_COMMUNITY_SERVER_IS_CURRENTLY_OFFLINE);
			return;
		}
		
		// Modern Community Board landing page and personal PvP statistics.
		if ("_bbshome".equalsIgnoreCase(command) || "_bbspvp".equalsIgnoreCase(command) || "_bbspvptop".equalsIgnoreCase(command) || "_bbsbuffer".equalsIgnoreCase(command) || command.toLowerCase().startsWith("_bbsbuffer;view;"))
		{
			if ("_bbspvptop".equalsIgnoreCase(command))
			{
				showPvPRanking(player);
			}
			else if (command.toLowerCase().startsWith("_bbsbuffer;view;"))
			{
				showBufferScheme(player, command.substring("_bbsbuffer;view;".length()));
			}
			else if ("_bbsbuffer".equalsIgnoreCase(command))
			{
				showBufferOverview(player);
			}
			else
			{
				showModernBoard(player, "_bbspvp".equalsIgnoreCase(command));
			}
			return;
		}
		
		final IParseBoardHandler cb = getHandler(command);
		if (cb == null)
		{
			LOG.warning(CommunityBoardHandler.class.getSimpleName() + ": Couldn't find parse handler for command " + command + "!");
			return;
		}
		
		cb.onCommand(command, player);
	}
	
	/**
	 * Displays the modern Community Board landing page or personal PvP statistics.
	 * Uses HTML elements supported by the legacy Interlude client.
	 * @param player the active player
	 * @param showStats whether to display personal PvP statistics
	 */
	private static void showModernBoard(Player player, boolean showStats)
	{
		final StringBuilder html = new StringBuilder(4096);
		html.append("<html><body><center>");
		html.append("<table width=600 cellpadding=8 cellspacing=0 bgcolor=10141D><tr><td>");
		html.append("<font color=E8BD68 name=hs12>ADEN</font> <font color=FFFFFF name=hs12>COMMUNITY</font><br1>");
		html.append("<font color=888E9B>INTERLUDE  |  COMMUNITY HUB</font>");
		html.append("</td><td align=right><font color=E8BD68>").append(escapeBoardText(player.getName())).append("</font><br1>");
		html.append("<font color=BBBBBB>LEVEL ").append(player.getLevel()).append("</font></td></tr></table><br>");
		html.append("<table width=600 cellpadding=5 cellspacing=2><tr>");
		html.append("<td width=195 align=center bgcolor=252B38><font color=E8BD68>HOME</font><br1><a action=\"bypass _bbshome\">Open dashboard</a></td>");
		html.append("<td width=195 align=center bgcolor=252B38><font color=E8BD68>PVP PROFILE</font><br1><a action=\"bypass _bbspvp\">My statistics</a><br1><a action=\"bypass _bbspvptop\">Top PvP</a><br1><a action=\"bypass _bbsbuffer\">Buffer schemes</a></td>");
		html.append("<td width=195 align=center bgcolor=252B38><font color=E8BD68>SERVER</font><br1><font color=BBBBBB>Interlude x10</font></td>");
		html.append("</tr></table><br>");
		if (showStats)
		{
			html.append("<table width=600 cellpadding=8 cellspacing=1 bgcolor=252B38>");
			html.append("<tr><td colspan=2><font color=E8BD68 name=hs12>PERSONAL PVP STATISTICS</font></td></tr>");
			html.append("<tr><td width=300><font color=BBBBBB>Character</font></td><td><font color=FFFFFF>").append(escapeBoardText(player.getName())).append("</font></td></tr>");
			html.append("<tr><td><font color=BBBBBB>Level</font></td><td><font color=FFFFFF>").append(player.getLevel()).append("</font></td></tr>");
			html.append("<tr><td><font color=BBBBBB>PvP kills</font></td><td><font color=E8BD68>").append(player.getPvpKills()).append("</font></td></tr>");
			html.append("<tr><td><font color=BBBBBB>PK kills</font></td><td><font color=E8BD68>").append(player.getPkKills()).append("</font></td></tr>");
			html.append("</table>");
		}
		else
		{
			html.append("<table width=600 cellpadding=8 cellspacing=1 bgcolor=252B38>");
			html.append("<tr><td><font color=E8BD68 name=hs12>WELCOME BACK</font><br1>");
			html.append("<font color=FFFFFF>").append(escapeBoardText(player.getName())).append("</font>, your Community Board has a new home.</td></tr>");
			html.append("<tr><td><font color=BBBBBB>Quick access</font><br1>");
			html.append("<a action=\"bypass _bbspvp\">View my PvP / PK statistics</a><br1><a action=\"bypass _bbspvptop\">Open Top PvP ranking</a><br1><a action=\"bypass _bbsbuffer\">View saved buffer schemes</a><br1>");
			html.append("<font color=888E9B>Additional services will appear here when their server-side handlers are connected.</font></td></tr>");
			html.append("</table>");
		}
		html.append("<br><font color=777F8F>Community Board  |  Interlude</font>");
		html.append("</center></body></html>");
		separateAndSend(html.toString(), player);
	}
	
	/**
	 * Displays the current player's saved buffer schemes and available categories.
	 * @param player the active player
	 */
	private static void showBufferOverview(Player player)
	{
		final StringBuilder html = new StringBuilder(4096);
		html.append("<html><body><center>");
		html.append("<table width=600 cellpadding=8 cellspacing=0 bgcolor=10141D><tr><td>");
		html.append("<font color=E8BD68 name=hs12>ADEN</font> <font color=FFFFFF name=hs12>BUFFER</font><br1>");
		html.append("<font color=888E9B>SCHEME OVERVIEW</font></td></tr></table><br>");
		html.append("<table width=600 cellpadding=6 cellspacing=1 bgcolor=252B38>");
		html.append("<tr><td><font color=E8BD68>YOUR SAVED SCHEMES</font></td><td align=center><font color=E8BD68>SKILLS</font></td></tr>");
		final Map<String, java.util.List<Integer>> playerSchemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
		final Map<String, java.util.List<Integer>> schemes = (playerSchemes != null) ? playerSchemes : java.util.Collections.emptyMap();
		if (schemes.isEmpty())
		{
			html.append("<tr><td colspan=2><font color=BBBBBB>No saved schemes yet. Create a scheme using the server's buffer NPC.</font></td></tr>");
		}
		else
		{
			for (Map.Entry<String, java.util.List<Integer>> entry : schemes.entrySet())
			{
				final int schemeIndex = new java.util.ArrayList<>(schemes.keySet()).indexOf(entry.getKey());
				html.append("<tr><td><font color=FFFFFF>").append(escapeBoardText(entry.getKey())).append("</font><br1><a action=\"bypass _bbsbuffer;view;").append(schemeIndex).append("\">View skills</a></td>");
				html.append("<td align=center><font color=E8BD68>").append(entry.getValue().size()).append("</font></td></tr>");
			}
		}
		html.append("</table><br><table width=600 cellpadding=6 cellspacing=1 bgcolor=252B38>");
		html.append("<tr><td><font color=E8BD68>AVAILABLE BUFFER CATEGORIES</font></td></tr>");
		final java.util.List<String> categories = SchemeBufferTable.getInstance().getSkillTypes();
		if (categories.isEmpty())
		{
			html.append("<tr><td><font color=BBBBBB>No buffer categories are configured.</font></td></tr>");
		}
		else
		{
			for (String category : categories)
			{
				html.append("<tr><td><font color=FFFFFF>").append(escapeBoardText(category)).append("</font></td></tr>");
			}
		}
				html.append("</table><br><a action=\"bypass _bbshome\">Back to Community Home</a>");
		html.append("</center></body></html>");
		separateAndSend(html.toString(), player);
	}
	
	/**
	 * Displays a read-only preview of a saved buffer scheme.
	 * @param player the active player
	 * @param indexText zero-based scheme index
	 */
	private static void showBufferScheme(Player player, String indexText)
	{
		final Map<String, java.util.List<Integer>> playerSchemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
		final Map<String, java.util.List<Integer>> schemes = (playerSchemes != null) ? playerSchemes : java.util.Collections.emptyMap();
		final int index;
		try
		{
			index = Integer.parseInt(indexText);
		}
		catch (NumberFormatException e)
		{
			separateAndSend("<html><body><center>Invalid buffer scheme.<br><a action=\"bypass _bbsbuffer\">Back to Buffer</a></center></body></html>", player);
			return;
		}
		if ((index < 0) || (index >= schemes.size()))
		{
			separateAndSend("<html><body><center>Buffer scheme not found.<br><a action=\"bypass _bbsbuffer\">Back to Buffer</a></center></body></html>", player);
			return;
		}
		final Map.Entry<String, java.util.List<Integer>> scheme = new java.util.ArrayList<>(schemes.entrySet()).get(index);
		final StringBuilder html = new StringBuilder(4096);
		html.append("<html><body><center>");
		html.append("<table width=600 cellpadding=8 cellspacing=0 bgcolor=10141D><tr><td>");
		html.append("<font color=E8BD68 name=hs12>ADEN</font> <font color=FFFFFF name=hs12>BUFFER</font><br1>");
		html.append("<font color=888E9B>SAVED SCHEME DETAILS</font></td></tr></table><br>");
		html.append("<table width=600 cellpadding=6 cellspacing=1 bgcolor=252B38>");
		html.append("<tr><td colspan=2><font color=E8BD68>").append(escapeBoardText(scheme.getKey())).append("</font></td></tr>");
		html.append("<tr><td width=420><font color=E8BD68>BUFF</font></td><td align=center><font color=E8BD68>LEVEL</font></td></tr>");
		if (scheme.getValue().isEmpty())
		{
			html.append("<tr><td colspan=2><font color=BBBBBB>This scheme contains no skills.</font></td></tr>");
		}
		else
		{
			for (int skillId : scheme.getValue())
			{
				final org.l2jmobius.gameserver.entity.actor.holders.npc.BuffSkillHolder buff = SchemeBufferTable.getInstance().getAvailableBuff(skillId);
				html.append("<tr><td><font color=FFFFFF>");
				if ((buff != null) && !buff.getDescription().isEmpty())
				{
					html.append(escapeBoardText(buff.getDescription()));
				}
				else
				{
					html.append("Skill #").append(skillId);
				}
				html.append("</font></td><td align=center><font color=E8BD68>").append((buff != null) ? buff.getLevel() : 0).append("</font></td></tr>");
			}
		}
		html.append("</table><br><font color=888E9B>This is a read-only preview; buffs are not applied here.</font><br>");
		html.append("<a action=\"bypass _bbsbuffer\">Back to Buffer</a>");
		html.append("</center></body></html>");
		separateAndSend(html.toString(), player);
	}

	/**
	 * Displays the global Top 10 PvP ranking.
	 * @param player the active player
	 */
	private static void showPvPRanking(Player player)
	{
		final StringBuilder html = new StringBuilder(4096);
		html.append("<html><body><center>");
		html.append("<table width=600 cellpadding=8 cellspacing=0 bgcolor=10141D><tr><td>");
		html.append("<font color=E8BD68 name=hs12>ADEN</font> <font color=FFFFFF name=hs12>COMMUNITY</font><br1>");
		html.append("<font color=888E9B>TOP PVP PLAYERS</font></td></tr></table><br>");
		html.append("<table width=600 cellpadding=5 cellspacing=1 bgcolor=252B38>");
		html.append("<tr><td width=45 align=center><font color=E8BD68>#</font></td><td><font color=E8BD68>PLAYER</font></td><td width=80 align=center><font color=E8BD68>LEVEL</font></td><td width=90 align=center><font color=E8BD68>PVP</font></td><td width=90 align=center><font color=E8BD68>PK</font></td></tr>");
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT char_name, level, pvpkills, pkkills FROM characters WHERE accesslevel = 0 ORDER BY pvpkills DESC, pkkills ASC, char_name ASC LIMIT 10");
			ResultSet rs = ps.executeQuery())
		{
			int rank = 1;
			boolean hasPlayers = false;
			boolean playerInTopTen = false;
			while (rs.next())
			{
				hasPlayers = true;
				final String characterName = rs.getString("char_name");
				final boolean isCurrentPlayer = (characterName != null) && characterName.equalsIgnoreCase(player.getName());
				playerInTopTen |= isCurrentPlayer;
				html.append("<tr");
				if (isCurrentPlayer)
				{
					html.append(" bgcolor=3A3020");
				}
				html.append("><td align=center><font color=BBBBBB>").append(rank++).append("</font></td>");
				html.append("<td><font color=").append(isCurrentPlayer ? "E8BD68" : "FFFFFF").append(">").append(escapeBoardText(characterName)).append("</font></td>");
				html.append("<td align=center><font color=BBBBBB>").append(rs.getInt("level")).append("</font></td>");
				html.append("<td align=center><font color=E8BD68>").append(rs.getInt("pvpkills")).append("</font></td>");
				html.append("<td align=center><font color=BBBBBB>").append(rs.getInt("pkkills")).append("</font></td></tr>");
			}
			if (!hasPlayers)
			{
				html.append("<tr><td colspan=5><font color=BBBBBB>No players are currently available for the public ranking.</font></td></tr>");
			}
			else if (!playerInTopTen)
			{
				html.append("<tr><td colspan=5><font color=888E9B>Your character is not in the Top 10 yet. Keep fighting to climb the ranking!</font></td></tr>");
			}
		}
		catch (SQLException e)
		{
			LOG.warning("CommunityBoardHandler: Could not load PvP ranking: " + e.getMessage());
			html.append("<tr><td colspan=5><font color=FF7777>Ranking is temporarily unavailable.</font></td></tr>");
		}
		html.append("</table><br>");
		html.append("<a action=\"bypass _bbshome\">Back to Community Home</a>");
		html.append("</center></body></html>");
		separateAndSend(html.toString(), player);
	}
	
	/**
	 * Escapes player-controlled text before inserting it into Community Board HTML.
	 * @param value text to escape
	 * @return escaped text
	 */
	private static String escapeBoardText(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
	
	/**
	 * Writes a command into the client.
	 * @param player the player
	 * @param url the command URL
	 * @param arg1 the first argument
	 * @param arg2 the second argument
	 * @param arg3 the third argument
	 * @param arg4 the fourth argument
	 * @param arg5 the fifth argument
	 */
	public void handleWriteCommand(Player player, String url, String arg1, String arg2, String arg3, String arg4, String arg5)
	{
		if (player == null)
		{
			return;
		}
		
		if (!GeneralConfig.ENABLE_COMMUNITY_BOARD)
		{
			player.sendPacket(SystemMessageId.THE_COMMUNITY_SERVER_IS_CURRENTLY_OFFLINE);
			return;
		}
		
		String cmd = "";
		switch (url)
		{
			case "Topic":
			{
				cmd = "_bbstop";
				break;
			}
			case "Post":
			{
				cmd = "_bbspos"; // TODO: Implement.
				break;
			}
			case "Region":
			{
				cmd = "_bbsloc";
				break;
			}
			case "Notice":
			{
				cmd = "_bbsclan";
				break;
			}
			case "Mail":
			{
				cmd = "_bbsmail";
				break;
			}
			case "Search":
			{
				cmd = "_bbsclan";
				break;
			}
			case "CMail":
			{
				cmd = "_bbsclan";
				break;
			}
			default:
			{
				separateAndSend("<html><body><br><br><center>The command: " + url + " is not implemented yet.</center><br><br></body></html>", player);
				return;
			}
		}
		
		final IParseBoardHandler cb = getHandler(cmd);
		if (cb == null)
		{
			LOG.warning(CommunityBoardHandler.class.getSimpleName() + ": Couldn't find write handler for command " + cmd + "!");
			return;
		}
		
		if (!(cb instanceof IWriteBoardHandler))
		{
			LOG.warning(CommunityBoardHandler.class.getSimpleName() + ": " + cb.getClass().getSimpleName() + " doesn't implement write!");
			return;
		}
		
		((IWriteBoardHandler) cb).writeCommunityBoardCommand(player, arg1, arg2, arg3, arg4, arg5);
	}
	
	/**
	 * Sets the last bypass used by the player.
	 * @param player the player
	 * @param title the title
	 * @param bypass the bypass
	 */
	public void addBypass(Player player, String title, String bypass)
	{
		_bypasses.put(player.getObjectId(), title + "&" + bypass);
	}
	
	/**
	 * Removes the last bypass used by the player.
	 * @param player the player
	 * @return the last bypass used
	 */
	public String removeBypass(Player player)
	{
		return _bypasses.remove(player.getObjectId());
	}
	
	/**
	 * Separates and send an HTML into multiple packets, to display into the community board.<br>
	 * The limit is 16383 characters.
	 * @param html the HTML to send
	 * @param player the player
	 */
	public static void separateAndSend(String html, Player player)
	{
		HtmlUtil.sendCBHtml(player, html);
	}
	
	public static CommunityBoardHandler getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final CommunityBoardHandler INSTANCE = new CommunityBoardHandler();
	}
}
