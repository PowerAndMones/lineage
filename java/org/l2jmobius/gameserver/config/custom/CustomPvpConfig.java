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
package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * Loads configuration for custom PvP statistics and anti-farm rules.
 */
public class CustomPvpConfig
{
	private static final String CUSTOM_PVP_CONFIG_FILE = "./config/Custom/CustomPvp.ini";

	public static int REPEAT_KILL_COOLDOWN_SECONDS;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(CUSTOM_PVP_CONFIG_FILE);
		REPEAT_KILL_COOLDOWN_SECONDS = Math.max(0, config.getInt("RepeatKillCooldownSeconds", 30));
	}
}
