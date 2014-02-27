package au.com.mineauz.MobHunting.leaderboard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import com.google.common.collect.HashMultimap;

import au.com.mineauz.MobHunting.Messages;
import au.com.mineauz.MobHunting.MobHunting;
import au.com.mineauz.MobHunting.StatType;
import au.com.mineauz.MobHunting.storage.TimePeriod;

public class LeaderboardManager implements Listener
{
	private Set<LegacyLeaderboard> mLegacyLeaderboards = new HashSet<LegacyLeaderboard>();
	private HashMultimap<World, Leaderboard> mLeaderboards = HashMultimap.create(); 
	private HashMap<String, LegacyLeaderboard> mLegacyNameMap = new HashMap<String, LegacyLeaderboard>();
	
	private BukkitTask mUpdater = null;
	
	public void initialize()
	{
		mUpdater = Bukkit.getScheduler().runTaskTimer(MobHunting.instance, new Updater(), 1L, MobHunting.config().leaderboardUpdatePeriod);
		loadLegacy();
		
		for(World world : Bukkit.getWorlds())
			loadWorld(world);
		
		Bukkit.getPluginManager().registerEvents(this, MobHunting.instance);
	}
	
	public void shutdown()
	{
		mUpdater.cancel();
	}
	
	public void createLeaderboard(Location location, BlockFace facing, StatType type, TimePeriod period, boolean horizontal, int width, int height) throws IllegalArgumentException
	{
		Leaderboard board = new Leaderboard(location, facing, width, height, horizontal, type, period);
		if(!board.isSpaceAvailable())
			throw new IllegalArgumentException("There is not enough room for the signs.");
			
		mLeaderboards.put(location.getWorld(), board);
		board.update();
		saveWorld(location.getWorld());
	}
	
	public Leaderboard getLeaderboardAt(Location location)
	{
		for(Leaderboard board : mLeaderboards.get(location.getWorld()))
		{
			if(board.getLocation().equals(location))
				return board;
		}
		
		return null;
	}
	
	public void deleteLegacyLeaderboard(String id) throws IllegalArgumentException
	{
		if(!mLegacyNameMap.containsKey(id.toLowerCase()))
			throw new IllegalArgumentException(Messages.getString("leaderboard.notexists", "id", id)); //$NON-NLS-1$ //$NON-NLS-2$
		
		mLegacyLeaderboards.remove(mLegacyNameMap.remove(id.toLowerCase()));
		save();
	}
	
	public Set<LegacyLeaderboard> getAllLegacyBoards()
	{
		return Collections.unmodifiableSet(mLegacyLeaderboards);
	}
	
	public void save()
	{
		try
		{
			YamlConfiguration config = new YamlConfiguration();
			config.options().header("This file is automatically generated. Do NOT edit this file manually or you risk losing all leaderboards if you mistype something."); //$NON-NLS-1$
			
			ArrayList<Object> key = new ArrayList<Object>();
			
			for(LegacyLeaderboard leaderboard : mLegacyLeaderboards)
			{
				key.add(leaderboard.write());
			}
			
			config.set("boards", key); //$NON-NLS-1$
			
			config.save(new File(MobHunting.instance.getDataFolder(), "boards.yml")); //$NON-NLS-1$
		}
		catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings( "unchecked" )
	private void loadLegacy()
	{
		try
		{
			File file = new File(MobHunting.instance.getDataFolder(), "boards.yml"); //$NON-NLS-1$
			
			if(!file.exists())
				return;
			
			YamlConfiguration config = new YamlConfiguration();
			config.load(file);
			
			List<Object> boards = (List<Object>) config.getList("boards"); //$NON-NLS-1$
			
			if(boards == null)
				return;
			
			mLegacyLeaderboards.clear();
			
			for(Object board : boards)
			{
				if(!(board instanceof Map))
					continue;
				
				LegacyLeaderboard leaderboard = new LegacyLeaderboard();
				leaderboard.read((Map<String, Object>)board);
				mLegacyLeaderboards.add(leaderboard);
				mLegacyNameMap.put(leaderboard.getId(), leaderboard);
			}
		}
		catch(IOException e)
		{
			e.printStackTrace();
		}
		catch ( InvalidConfigurationException e )
		{
			e.printStackTrace();
		}
	}
	
	private void loadWorld(World world)
	{
		File file = new File(MobHunting.instance.getDataFolder(), "boards-" + world.getName() + ".yml");
		if(!file.exists())
			return;
		
		try
		{
			YamlConfiguration config = new YamlConfiguration();
			config.load(file);
			
			for(String key : config.getKeys(false))
			{
				ConfigurationSection section = config.getConfigurationSection(key);
				Leaderboard board = new Leaderboard();
				board.read(section);
				board.update();
				mLeaderboards.put(world, board);
			}
		}
		catch(InvalidConfigurationException e)
		{
			e.printStackTrace();
		}
		catch(IOException e)
		{
			e.printStackTrace();
		}
	}

	public void saveWorld(World world)
	{
		File file = new File(MobHunting.instance.getDataFolder(), "boards-" + world.getName() + ".yml");
		YamlConfiguration config = new YamlConfiguration();
		
		int i = 0;
		for(Leaderboard board : mLeaderboards.get(world))
		{
			ConfigurationSection section = config.createSection(String.valueOf(i++));
			board.save(section);
		}
		
		if(i != 0)
		{
			try
			{
				config.save(file);
			}
			catch(IOException e)
			{
				e.printStackTrace();
			}
		}
	}
	
	@EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
	private void onWorldLoad(WorldLoadEvent event)
	{
		loadWorld(event.getWorld());
	}
	
	@EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
	private void onWorldUnload(WorldUnloadEvent event)
	{
		mLeaderboards.removeAll(event.getWorld());
	}
	
	@EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
	private void onChunkLoad(ChunkLoadEvent event)
	{
		for(Leaderboard board : mLeaderboards.get(event.getWorld()))
		{
			if(board.isInChunk(event.getChunk()))
				board.refresh();
		}
	}
	
	@EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=false)
	private void onLeaderboardClick(PlayerInteractEvent event)
	{
		
	}
	
	@EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
	private void onBlockBreak(BlockBreakEvent event)
	{
		Block block = event.getBlock();
		
		if(block.getType() != Material.WALL_SIGN)
		{
			if (block.getRelative(BlockFace.NORTH).getType() != Material.WALL_SIGN &&
				block.getRelative(BlockFace.SOUTH).getType() != Material.WALL_SIGN && 
				block.getRelative(BlockFace.EAST).getType() != Material.WALL_SIGN && 
				block.getRelative(BlockFace.WEST).getType() != Material.WALL_SIGN)
				return;
		}
		
		for(Leaderboard board : mLeaderboards.get(block.getWorld()))
		{
			if(board.isInBounds(block.getLocation()))
			{
				// Allow the block to be broken
				if(event.getPlayer().hasPermission("mobhunting.leaderboard") && block.getLocation().equals(board.getLocation()))
					return;
				
				if(block.getType() == Material.WALL_SIGN)
					event.setCancelled(true);
				else if(block.getRelative(board.getFacing()).getType() == Material.WALL_SIGN)
					event.setCancelled(true);
			}
		}
	}
	
	@EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
	private void onBlockPiston(BlockPistonExtendEvent event)
	{
		for(Block block : event.getBlocks())
		{
			if(block.getType() != Material.WALL_SIGN)
			{
				if (block.getRelative(BlockFace.NORTH).getType() != Material.WALL_SIGN &&
					block.getRelative(BlockFace.SOUTH).getType() != Material.WALL_SIGN && 
					block.getRelative(BlockFace.EAST).getType() != Material.WALL_SIGN && 
					block.getRelative(BlockFace.WEST).getType() != Material.WALL_SIGN)
					continue;
			}
			
			for(Leaderboard board : mLeaderboards.get(block.getWorld()))
			{
				if(board.isInBounds(block.getLocation()))
				{
					if(block.getType() == Material.WALL_SIGN)
						event.setCancelled(true);
					else if(block.getRelative(board.getFacing()).getType() == Material.WALL_SIGN)
						event.setCancelled(true);
				}
			}
		}
	}
	
	@EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
	private void onBlockPiston(BlockPistonRetractEvent event)
	{
		if(event.isSticky())
		{
			Block block = event.getRetractLocation().getBlock();
			if(block.getType() != Material.WALL_SIGN)
			{
				if (block.getRelative(BlockFace.NORTH).getType() != Material.WALL_SIGN &&
					block.getRelative(BlockFace.SOUTH).getType() != Material.WALL_SIGN && 
					block.getRelative(BlockFace.EAST).getType() != Material.WALL_SIGN && 
					block.getRelative(BlockFace.WEST).getType() != Material.WALL_SIGN)
					return;
			}
			
			for(Leaderboard board : mLeaderboards.get(block.getWorld()))
			{
				if(board.isInBounds(block.getLocation()))
				{
					if(block.getType() == Material.WALL_SIGN)
						event.setCancelled(true);
					else if(block.getRelative(board.getFacing()).getType() == Material.WALL_SIGN)
						event.setCancelled(true);
				}
			}
		}
	}
	
	@EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
	private void onBlockBurn(BlockBurnEvent event)
	{
		Block block = event.getBlock();
		if(block.getType() != Material.WALL_SIGN)
		{
			if (block.getRelative(BlockFace.NORTH).getType() != Material.WALL_SIGN &&
				block.getRelative(BlockFace.SOUTH).getType() != Material.WALL_SIGN && 
				block.getRelative(BlockFace.EAST).getType() != Material.WALL_SIGN && 
				block.getRelative(BlockFace.WEST).getType() != Material.WALL_SIGN)
				return;
		}
		
		for(Leaderboard board : mLeaderboards.get(block.getWorld()))
		{
			if(board.isInBounds(block.getLocation()))
			{
				if(block.getType() == Material.WALL_SIGN)
					event.setCancelled(true);
				else if(block.getRelative(board.getFacing()).getType() == Material.WALL_SIGN)
					event.setCancelled(true);
			}
		}
	}
	
	@EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
	private void onBlockExplode(EntityExplodeEvent event)
	{
		for(Block block : event.blockList())
		{
			if(block.getType() != Material.WALL_SIGN)
			{
				if (block.getRelative(BlockFace.NORTH).getType() != Material.WALL_SIGN &&
					block.getRelative(BlockFace.SOUTH).getType() != Material.WALL_SIGN && 
					block.getRelative(BlockFace.EAST).getType() != Material.WALL_SIGN && 
					block.getRelative(BlockFace.WEST).getType() != Material.WALL_SIGN)
					continue;
			}
			
			for(Leaderboard board : mLeaderboards.get(block.getWorld()))
			{
				if(board.isInBounds(block.getLocation()))
				{
					if(block.getType() == Material.WALL_SIGN)
						event.setCancelled(true);
					else if(block.getRelative(board.getFacing()).getType() == Material.WALL_SIGN)
						event.setCancelled(true);
				}
			}
		}
	}
	
	@EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
	private void onBlockPickup(EntityChangeBlockEvent event)
	{
		if(!event.getTo().isSolid())
		{
			Block block = event.getBlock();
			if(block.getType() != Material.WALL_SIGN)
			{
				if (block.getRelative(BlockFace.NORTH).getType() != Material.WALL_SIGN &&
					block.getRelative(BlockFace.SOUTH).getType() != Material.WALL_SIGN && 
					block.getRelative(BlockFace.EAST).getType() != Material.WALL_SIGN && 
					block.getRelative(BlockFace.WEST).getType() != Material.WALL_SIGN)
					return;
			}
			
			for(Leaderboard board : mLeaderboards.get(block.getWorld()))
			{
				if(board.isInBounds(block.getLocation()))
				{
					if(block.getType() == Material.WALL_SIGN)
						event.setCancelled(true);
					else if(block.getRelative(board.getFacing()).getType() == Material.WALL_SIGN)
						event.setCancelled(true);
				}
			}
		}
	}
	
	@EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
	private void onBlockBreakFinal(BlockBreakEvent event)
	{
		Block block = event.getBlock();
		if(block.getType() != Material.WALL_SIGN || !event.getPlayer().hasPermission("mobhunting.leaderboard"))
			return;
		
		for(Leaderboard board : mLeaderboards.get(block.getWorld()))
		{
			if(block.getLocation().equals(board.getLocation()))
			{
				board.removeSigns();
				mLeaderboards.remove(block.getWorld(), board);
				saveWorld(board.getWorld());
				System.out.println("Leaderboard removed " + block.getLocation().toString());
				return;
			}
		}
	}
	
	
	
	private class Updater implements Runnable
	{
		@Override
		public void run()
		{
			for(LegacyLeaderboard board : mLegacyLeaderboards)
				board.updateBoard();
			
			for(Leaderboard board : mLeaderboards.values())
				board.update();
		}
	}

	public LegacyLeaderboard getLeaderboard( String id )
	{
		return mLegacyNameMap.get(id.toLowerCase());
	}
}
