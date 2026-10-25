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
package custom.events.Arena1v1;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.DuelManager;
import org.l2jmobius.gameserver.mechanics.script.Event;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Basic 1v1 matchmaking queue that hands matched players to Mobius' native duel system.
 * This first implementation deliberately does not issue items or PvP points.
 */
public class Arena1v1 extends Event
{
	private static final String HTML_PATH = "data/scripts/custom/events/Arena1v1/";
	private static final int MANAGER_NPC_ID = 70013;
	private static final Location MANAGER_SPAWN = new Location(83425, 148585, -3406, 32938);
	private static final ConcurrentLinkedQueue<Player> WAITING_PLAYERS = new ConcurrentLinkedQueue<>();
	private static final Set<Integer> WAITING_IDS = ConcurrentHashMap.newKeySet();
	private static volatile boolean ACTIVE;
	private static Npc MANAGER_NPC;

	private Arena1v1()
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

		clearWaitingPlayers("1v1 Arena queue was reset.");
		ACTIVE = true;
		MANAGER_NPC = addSpawn(MANAGER_NPC_ID, MANAGER_SPAWN, false, 0);
		return true;
	}

	@Override
	public synchronized boolean eventStop()
	{
		ACTIVE = false;
		clearWaitingPlayers("1v1 Arena queue has been stopped.");
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

		final String command = bypass.startsWith("Arena1v1 ") ? bypass.substring("Arena1v1 ".length()) : bypass;
		switch (command)
		{
			case "Join":
				joinQueue(player);
				return true;
			case "Leave":
				leaveQueue(player);
				return true;
			case "Status":
				showStatus(player);
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
		else if ("Status".equals(event))
		{
			showStatus(player);
		}
		return null;
	}

	@Override
	public String onFirstTalk(Npc npc, Player player)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
		html.setFile(player, HTML_PATH + "manager.html");
		html.replace("%queue_size%", String.valueOf(WAITING_PLAYERS.size()));
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
				player.sendMessage("1v1 Arena is currently closed.");
			}
			return;
		}

		if (!canRegister(player) || !ACTIVE)
		{
			return;
		}

		if (!WAITING_IDS.add(player.getObjectId()))
		{
			player.sendMessage("You are already waiting for a 1v1 opponent.");
			return;
		}

		player.setRegisteredOnEvent(true);
		WAITING_PLAYERS.add(player);
		player.sendMessage("You joined the 1v1 Arena queue. Waiting players: " + WAITING_PLAYERS.size());
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
			WAITING_PLAYERS.remove(player);
			player.setRegisteredOnEvent(false);
			player.sendMessage("You left the 1v1 Arena queue.");
		}
		else
		{
			player.sendMessage("You are not waiting in the 1v1 Arena queue.");
		}
	}

	private synchronized void matchPlayers()
	{
		Player playerA;
		Player playerB;
		while (ACTIVE && (WAITING_PLAYERS.size() >= 2))
		{
			playerA = WAITING_PLAYERS.poll();
			playerB = WAITING_PLAYERS.poll();
			if (playerA == null)
			{
				continue;
			}
			WAITING_IDS.remove(playerA.getObjectId());
			playerA.setRegisteredOnEvent(false);
			if (playerB == null)
			{
				WAITING_PLAYERS.add(playerA);
				WAITING_IDS.add(playerA.getObjectId());
				playerA.setRegisteredOnEvent(true);
				return;
			}

			WAITING_IDS.remove(playerB.getObjectId());
			playerB.setRegisteredOnEvent(false);
			if (!isMatchReady(playerA) || !isMatchReady(playerB))
			{
				if (isMatchReady(playerA))
				{
					WAITING_PLAYERS.add(playerA);
					WAITING_IDS.add(playerA.getObjectId());
					playerA.setRegisteredOnEvent(true);
				}
				else
				{
					playerA.sendMessage("You were removed from the 1v1 queue because you are no longer eligible.");
				}
				if (isMatchReady(playerB))
				{
					WAITING_PLAYERS.add(playerB);
					WAITING_IDS.add(playerB.getObjectId());
					playerB.setRegisteredOnEvent(true);
				}
				else
				{
					playerB.sendMessage("You were removed from the 1v1 queue because you are no longer eligible.");
				}
				continue;
			}

			DuelManager.getInstance().addDuel(playerA, playerB, 0);
			playerA.sendMessage("1v1 opponent found: " + playerB.getName() + ". The standard duel system will manage the fight.");
			playerB.sendMessage("1v1 opponent found: " + playerA.getName() + ". The standard duel system will manage the fight.");
		}
	}

	private boolean canRegister(Player player)
	{
		if (player.isRegisteredOnEvent() || player.isOnEvent() || player.isInDuel() || player.isInOlympiadMode() || player.isInInstance() || player.isInSiege() || player.isFishing() || player.isInCombat() || (player.getPvpFlag() != 0) || (player.getKarma() > 0) || player.isCursedWeaponEquipped())
		{
			player.sendMessage("You cannot join the 1v1 queue in your current state.");
			return false;
		}
		if (player.isFlying())
		{
			player.sendMessage("You cannot join the 1v1 queue while flying.");
			return false;
		}
		if (!player.isInventoryUnder80(false) || (player.getWeightPenalty() != 0))
		{
			player.sendMessage("Reduce your inventory load before joining the 1v1 queue.");
			return false;
		}
		return true;
	}

	private boolean isMatchReady(Player player)
	{
		return (player != null) && (player.isOnlineInt() == 1) && !player.isDead() && !player.isInDuel() && (player.getPvpFlag() == 0) && !player.isInOlympiadMode() && !player.isInInstance() && !player.isInSiege() && (player.getKarma() == 0);
	}

	private void clearWaitingPlayers(String message)
	{
		for (Player player : WAITING_PLAYERS)
		{
			if (player != null)
			{
				player.setRegisteredOnEvent(false);
				if (message != null)
				{
					player.sendMessage(message);
				}
			}
		}
		WAITING_PLAYERS.clear();
		WAITING_IDS.clear();
	}

	private void showStatus(Player player)
	{
		if (player != null)
		{
			player.sendMessage("1v1 Arena status: " + (ACTIVE ? "Open" : "Closed") + ". Waiting players: " + WAITING_PLAYERS.size() + ".");
		}
	}
}
