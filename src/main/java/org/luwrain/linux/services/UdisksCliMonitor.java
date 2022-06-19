/*
   Copyright 2012-2022 Michael Pozhidaev <msp@luwrain.org>

   This file is part of LUWRAIN.

   LUWRAIN is free software; you can redistribute it and/or
   modify it under the terms of the GNU General Public
   License as published by the Free Software Foundation; either
   version 3 of the License, or (at your option) any later version.

   LUWRAIN is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   General Public License for more details.
*/

package org.luwrain.linux.services;

import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.io.*;

import org.luwrain.core.*;
import org.luwrain.linux.*;
import org.luwrain.script.core.*;

import static org.luwrain.script.Hooks.*;


public final class UdisksCliMonitor implements BashProcess.Listener
{
    static private final String
	LOG_COMPONENT = "udisks";

    static private final Pattern
	RE_ADDED = Pattern.compile("^\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d:\\sAdded\\s(.*)$"),
	RE_REMOVED = Pattern.compile("^\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d:\\sRemoved\\s(.*)$"),
    	RE_PROP_CHANGED = Pattern.compile("^\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d:\\s+([^:]+):\\s+([^:]+):\\s+Properties Changed\\s+$");

    static private final String
	OBJ_DRIVES = "/org/freedesktop/UDisks2/drives/",
		OBJ_BLOCK = "/org/freedesktop/UDisks2/block_devices/",
	IFACE_DRIVE = "org.freedesktop.UDisks2.Drive",
	IFACE_BLOCK = "org.freedesktop.UDisks2.Block",
	IFACE_FILESYSTEM = "org.freedesktop.UDisks2.Filesystem",
	PREFIX_REMOVABLE = "Removable:",
	PREFIX_SIZE = "Size:",
	PREFIX_MODEL = "Model:",
	PREFIX_VENDOR = "Vendor:",
	PREFIX_DEVICE = "Device:",
	PREFIX_DRIVE = "Drive:",
	PREFIX_FS_TYPE = "IdType:";

    private final Luwrain luwrain;
    private final BashProcess p ;
    private final Map<String, Disk> disks = new HashMap<>();
    private final Map<String, BlockDev> blockDevs = new HashMap<>();
    private Disk activeDisk = null;
    private BlockDev activeBlockDev = null;
    private String activeIface = null;

    public UdisksCliMonitor(Luwrain luwrain) throws IOException
    {
	this.luwrain = luwrain;
	p = launch();
	    }

    BashProcess launch() throws IOException
    {
	final BashProcess b = new BashProcess("udisksctl monitor", null, EnumSet.noneOf(BashProcess.Flags.class), this);
	b.run();
	return b;
    }

    public synchronized void enumBlockDevices(Consumer<Map<String, Object>> consumer)
    {
	for(Map.Entry<String, BlockDev> e: blockDevs.entrySet())
	    if (e.getValue().isReady())
	    consumer.accept(e.getValue().createAttrMap());
    }

    		@Override public synchronized void onOutputLine(String line)
		{
		    //		    		    Log.debug(LOG_COMPONENT, line);
		    try {
		    Matcher m = RE_ADDED.matcher(line);
		    if (m.find())
		    {
			final String obj = m.group(1).trim();
			activeDisk = null;
			activeBlockDev = null;
			activeIface = null;

			if (obj.startsWith(OBJ_DRIVES))
			{
			    activeDisk = new Disk(obj);
			    disks.put(obj, activeDisk);
			    Log.debug(LOG_COMPONENT, "added new disk: " + obj);
			    return;
			}

						if (obj.startsWith(OBJ_BLOCK))
			{
			    activeBlockDev = new BlockDev(obj);
			    blockDevs.put(obj, activeBlockDev);
			    Log.debug(LOG_COMPONENT, "added new block device: " + obj);
			    return;
			}

			return;
		    }

m = RE_REMOVED.matcher(line);
		    if (m.find())
		    {
			final String obj = m.group(1).trim();
			activeDisk = null;
			activeBlockDev = null;
			activeIface = null;
			if (disks.containsKey(obj))
			{
			    final Disk disk = disks.get(obj);
			    disks.remove(obj);
			    chainOfResponsibility(luwrain, Hooks.DISK_REMOVED, new Object[]{ new MapScriptObject(disk.createHookMap())});
			    return;
			}

						if (blockDevs.containsKey(obj))
			{
			    final BlockDev blockDev = blockDevs.get(obj);
			    blockDevs.remove(obj);
			    chainOfResponsibility(luwrain, Hooks.BLOCK_DEV_REMOVED, new Object[]{ new MapScriptObject(blockDev.createAttrMap())});
			    return;
			}

						return;
		    }

		    final String l = line.trim();
		    if (l.startsWith("org.freedesktop") && l.endsWith(":"))
		    {
			activeIface = l.substring(0, l.length() - 1);
			return;
		    }

		    if (activeDisk != null)
			activeDisk.onLine(l);
		    if (activeBlockDev != null)
			activeBlockDev.onLine(l);
		    }
		    catch(Throwable e)
		    {
			Log.error(LOG_COMPONENT, "unable to process a line of udisksctl output: " + e.getClass().getName() + ": " + e.getMessage());
		    }
		}

	@Override public void onErrorLine(String line)
	{
	    Log.error(LOG_COMPONENT, "monitor error: " + line);
	}

	@Override public void onFinishing(int exitCode)
	{
	    if (exitCode == 0)
		Log.debug(LOG_COMPONENT, "the monitor finished without errors"); else
		Log.error(LOG_COMPONENT, "the monitor finished with the exit code " + String.valueOf(exitCode));
	    activeDisk = null;
	    activeBlockDev = null;
	    disks.clear();
	    blockDevs.clear();
	}

    private final class Disk
    {
	final String obj;
	String
	    vendor = null,
	    model = null;
	Disk(String obj) { this.obj = obj; }
	void onLine(String line)
	{
	    if (line.startsWith(PREFIX_MODEL))
		model = line.substring(PREFIX_MODEL.length()).trim();
	    if (line.startsWith(PREFIX_VENDOR))
		vendor = line.substring(PREFIX_VENDOR.length()).trim();
	    if (model != null && vendor != null)
		chainOfResponsibility(luwrain, Hooks.DISK_ADDED, new Object[]{new MapScriptObject(createHookMap())});
	}
	Map<String, Object> createHookMap()
	{
	    final Map<String, Object> d = new HashMap<>();
	    d.put("obj", obj);
	    d.put("model", model);
	    d.put("vendor", vendor);
	    return d;
	}
    }

    private final class BlockDev
    {
	final String obj;
	String
	    device = null,
	    drive = null,
	    fsType = null;
	BlockDev(String obj) { this.obj = obj; }
	void onLine(String line)
	{
	    if (line.startsWith(PREFIX_DEVICE))
		device = line.substring(PREFIX_DEVICE.length()).trim();
	    if (line.startsWith(PREFIX_DRIVE))
		drive = line.substring(PREFIX_DRIVE.length()).trim();
	    if (line.startsWith(PREFIX_FS_TYPE))
		fsType = line.substring(PREFIX_FS_TYPE.length()).trim();
	    if (isReady())
		chainOfResponsibility(luwrain, Hooks.BLOCK_DEV_ADDED, new Object[]{new MapScriptObject(createAttrMap())});
	}
	boolean isReady()
	{
	    return device != null && drive != null && fsType != null;
	}
	Map<String, Object> createAttrMap()
	{
	    final Map<String, Object> d = new HashMap<>();
	    d.put("obj", obj);
	    d.put("device", device);
	    d.put("drive", drive);
	    d.put("fsType", fsType);
	    return d;
	}
    }
}
