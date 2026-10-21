package fr.hevenly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

public class BedFightManager implements Listener, CommandExecutor {

    private final HevenlyPlugin plugin;
    private final List<UUID> queue = new ArrayList<UUID>();
    private final Map<UUID, String> teams = new HashMap<UUID, String>();
    private final Set<BlockKey> placedBlocks = new LinkedHashSet<BlockKey>();
    private final Map<String, Boolean> bedAlive = new HashMap<String, Boolean>();
    private final Map<String, Location> respawnLocations = new HashMap<String, Location>();
    private boolean matchRunning = false;
    private boolean finishing = false;

    public BedFightManager(HevenlyPlugin plugin) {
        this.plugin = plugin;
        bedAlive.put("red", true);
        bedAlive.put("blue", true);
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("bedfight.enabled", false);
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        String[] commands = {"bfsetspawn", "bfsetbed", "bfstart", "bfleave"};
        for (String name : commands) {
            if (plugin.getCommand(name) != null) {
                plugin.getCommand(name).setExecutor(this);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("bfsetspawn") || name.equals("bfsetbed")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }
            Player player = (Player) sender;
            if (!isAdmin(player)) {
                player.sendMessage(ChatColor.RED + "Tu n'as pas la permission !");
                return true;
            }
            if (args.length != 1 || (!args[0].equalsIgnoreCase("red") && !args[0].equalsIgnoreCase("blue"))) {
                player.sendMessage(ChatColor.RED + "Utilisation : /" + name + " <red|blue>");
                return true;
            }

            String team = args[0].toLowerCase(Locale.ROOT);
            if (name.equals("bfsetspawn")) {
                saveLocation("bedfight.spawns." + team, player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Spawn de l'equipe " + teamColor(team) + team.toUpperCase(Locale.ROOT)
                        + ChatColor.GREEN + " defini.");
            } else {
                Block target = player.getTargetBlock((HashSet<Byte>) null, 6);
                if (target == null || target.getType() != Material.BED_BLOCK) {
                    player.sendMessage(ChatColor.RED + "Regarde un lit de l'equipe " + team.toUpperCase(Locale.ROOT)
                            + ChatColor.RED + " a moins de 6 blocs.");
                    return true;
                }
                saveBed(team, target);
                player.sendMessage(ChatColor.GREEN + "Lit de l'equipe " + teamColor(team)
                        + team.toUpperCase(Locale.ROOT) + ChatColor.GREEN + " enregistre.");
            }
            return true;
        }

        if (name.equals("bfstart")) {
            if (!sender.hasPermission("hevenly.bedfight.admin")) {
                sender.sendMessage(ChatColor.RED + "Tu n'as pas la permission !");
                return true;
            }
            if (matchRunning) {
                sender.sendMessage(ChatColor.RED + "Une partie est deja en cours.");
                return true;
            }
            if (queue.size() < 2) {
                sender.sendMessage(ChatColor.RED + "Il faut au moins deux joueurs dans la file.");
                return true;
            }
            if (!isArenaConfigured()) {
                sender.sendMessage(ChatColor.RED + "Configure les deux spawns et les deux lits avant de lancer une partie.");
                return true;
            }
            startMatch();
            sender.sendMessage(ChatColor.GREEN + "Partie BedFight demarree.");
            return true;
        }

        if (name.equals("bfleave")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }
            Player player = (Player) sender;
            if (matchRunning && teams.containsKey(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Tu ne peux pas quitter une partie en cours avec /bfleave.");
                player.sendMessage(ChatColor.GRAY + "Deconnecte-toi pour abandonner la partie.");
                return true;
            }
            if (queue.remove(player.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + "Tu as quitte la file BedFight.");
                Location lobby = getLobbySpawn();
                if (lobby != null) player.teleport(lobby);
            } else {
                player.sendMessage(ChatColor.RED + "Tu n'es pas dans la file d'attente.");
            }
            return true;
        }

        return false;
    }

    private boolean isAdmin(Player player) {
        return player.hasPermission("hevenly.bedfight.admin") || player.isOp();
    }

    private ChatColor teamColor(String team) {
        return "red".equals(team) ? ChatColor.RED : ChatColor.BLUE;
    }

    private void saveLocation(String path, Location loc) {
        plugin.getConfig().set(path + ".world", loc.getWorld().getName());
        plugin.getConfig().set(path + ".x", loc.getX());
        plugin.getConfig().set(path + ".y", loc.getY());
        plugin.getConfig().set(path + ".z", loc.getZ());
        plugin.getConfig().set(path + ".yaw", loc.getYaw());
        plugin.getConfig().set(path + ".pitch", loc.getPitch());
        plugin.saveConfig();
    }

    private Location readLocation(String path) {
        String worldName = plugin.getConfig().getString(path + ".world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world,
                plugin.getConfig().getDouble(path + ".x"),
                plugin.getConfig().getDouble(path + ".y"),
                plugin.getConfig().getDouble(path + ".z"),
                (float) plugin.getConfig().getDouble(path + ".yaw"),
                (float) plugin.getConfig().getDouble(path + ".pitch"));
    }

    private Location getLobbySpawn() {
        String worldName = plugin.getConfig().getString("spawn.world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world,
                plugin.getConfig().getDouble("spawn.x"),
                plugin.getConfig().getDouble("spawn.y"),
                plugin.getConfig().getDouble("spawn.z"),
                (float) plugin.getConfig().getDouble("spawn.yaw"),
                (float) plugin.getConfig().getDouble("spawn.pitch"));
    }

    private void saveBed(String team, Block target) {
        List<Block> parts = getBedParts(target);
        plugin.getConfig().set("bedfight.beds." + team + ".blocks", null);
        int i = 0;
        for (Block block : parts) {
            String path = "bedfight.beds." + team + ".blocks." + i++;
            plugin.getConfig().set(path + ".world", block.getWorld().getName());
            plugin.getConfig().set(path + ".x", block.getX());
            plugin.getConfig().set(path + ".y", block.getY());
            plugin.getConfig().set(path + ".z", block.getZ());
            plugin.getConfig().set(path + ".data", (int) block.getData());
        }
        plugin.saveConfig();
    }

    private List<Block> getBedParts(Block target) {
        List<Block> parts = new ArrayList<Block>();
        parts.add(target);
        byte data = target.getData();
        // Dans Minecraft 1.8, bit 0x8 indique la tete du lit.
        boolean isHead = (data & 0x8) != 0;
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] dir : directions) {
            Block other = target.getRelative(dir[0], 0, dir[1]);
            if (other.getType() == Material.BED_BLOCK) {
                boolean otherHead = (other.getData() & 0x8) != 0;
                if (otherHead != isHead) {
                    parts.add(other);
                    break;
                }
            }
        }
        return parts;
    }

    private List<Location> getConfiguredBedLocations(String team) {
        List<Location> locations = new ArrayList<Location>();
        String path = "bedfight.beds." + team + ".blocks";
        if (!plugin.getConfig().isConfigurationSection(path)) return locations;
        for (String key : plugin.getConfig().getConfigurationSection(path).getKeys(false)) {
            String blockPath = path + "." + key;
            String worldName = plugin.getConfig().getString(blockPath + ".world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world != null) {
                locations.add(new Location(world,
                        plugin.getConfig().getInt(blockPath + ".x"),
                        plugin.getConfig().getInt(blockPath + ".y"),
                        plugin.getConfig().getInt(blockPath + ".z")));
            }
        }
        return locations;
    }

    private void restoreBeds() {
        for (String team : Arrays.asList("red", "blue")) {
            String path = "bedfight.beds." + team + ".blocks";
            if (!plugin.getConfig().isConfigurationSection(path)) continue;
            for (String key : plugin.getConfig().getConfigurationSection(path).getKeys(false)) {
                String blockPath = path + "." + key;
                String worldName = plugin.getConfig().getString(blockPath + ".world");
                World world = worldName == null ? null : Bukkit.getWorld(worldName);
                if (world == null) continue;
                int x = plugin.getConfig().getInt(blockPath + ".x");
                int y = plugin.getConfig().getInt(blockPath + ".y");
                int z = plugin.getConfig().getInt(blockPath + ".z");
                byte data = (byte) plugin.getConfig().getInt(blockPath + ".data");
                Block block = world.getBlockAt(x, y, z);
                block.setType(Material.BED_BLOCK);
                block.setData(data, false);
            }
        }
    }

    private boolean isConfiguredBedBlock(Block block, String team) {
        for (Location loc : getConfiguredBedLocations(team)) {
            if (loc.getWorld().equals(block.getWorld())
                    && loc.getBlockX() == block.getX()
                    && loc.getBlockY() == block.getY()
                    && loc.getBlockZ() == block.getZ()) return true;
        }
        return false;
    }

    private String bedTeamForBlock(Block block) {
        if (isConfiguredBedBlock(block, "red")) return "red";
        if (isConfiguredBedBlock(block, "blue")) return "blue";
        return null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        if (matchRunning && teams.size() >= 2) {
            if (!queue.contains(player.getUniqueId())) queue.add(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "Une partie est en cours. Tu es dans la file d'attente.");
            Location lobby = getLobbySpawn();
            if (lobby != null) player.teleport(lobby);
        } else {
            addToQueue(player);
        }
    }

    private void addToQueue(Player player) {
        if (teams.containsKey(player.getUniqueId()) || queue.contains(player.getUniqueId())) return;
        queue.add(player.getUniqueId());
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        Location lobby = getLobbySpawn();
        if (lobby != null) player.teleport(lobby);
        player.sendMessage(ChatColor.GOLD + "Hevenly BedFight " + ChatColor.GRAY + "» "
                + ChatColor.YELLOW + "Recherche d'un adversaire...");
        tryStartMatch();
    }

    private void tryStartMatch() {
        if (!isEnabled() || matchRunning || finishing || queue.size() < 2) return;
        if (!isArenaConfigured()) {
            for (UUID id : queue) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(ChatColor.RED + "La map BedFight n'est pas configuree. Contacte un administrateur.");
            }
            return;
        }
        startMatch();
    }

    private boolean isArenaConfigured() {
        return readLocation("bedfight.spawns.red") != null
                && readLocation("bedfight.spawns.blue") != null
                && !getConfiguredBedLocations("red").isEmpty()
                && !getConfiguredBedLocations("blue").isEmpty();
    }

    private void startMatch() {
        if (queue.size() < 2 || matchRunning) return;
        restoreBeds();
        placedBlocks.clear();
        bedAlive.put("red", true);
        bedAlive.put("blue", true);
        UUID redId = queue.remove(0);
        UUID blueId = queue.remove(0);
        Player red = Bukkit.getPlayer(redId);
        Player blue = Bukkit.getPlayer(blueId);
        if (red == null || blue == null) {
            if (red != null) queue.add(0, redId);
            if (blue != null) queue.add(0, blueId);
            tryStartMatch();
            return;
        }

        matchRunning = true;
        finishing = false;
        teams.put(redId, "red");
        teams.put(blueId, "blue");
        preparePlayer(red, "red");
        preparePlayer(blue, "blue");
        broadcastMatch(ChatColor.GOLD + "La partie commence ! " + ChatColor.RED + red.getName()
                + ChatColor.GRAY + " contre " + ChatColor.BLUE + blue.getName() + ChatColor.GOLD + ".");
    }

    private void preparePlayer(Player player, String team) {
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        PlayerInventory inv = player.getInventory();
        inv.addItem(new ItemStack(Material.WOOD_SWORD, 1));
        inv.addItem(new ItemStack(Material.WOOD_PICKAXE, 1));
        inv.addItem(new ItemStack(Material.WOOD_AXE, 1));
        inv.addItem(new ItemStack(Material.SHEARS, 1));
        inv.addItem(new ItemStack(Material.WOOL, 64, (short) 0, (byte) ("red".equals(team) ? 14 : 11)));
        inv.addItem(new ItemStack(Material.WOOD, 32));
        inv.setHelmet(new ItemStack(Material.LEATHER_HELMET));
        inv.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
        inv.setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
        inv.setBoots(new ItemStack(Material.LEATHER_BOOTS));
        Location spawn = readLocation("bedfight.spawns." + team);
        if (spawn != null) player.teleport(spawn);
        player.sendMessage(ChatColor.GOLD + "Ton equipe : " + teamColor(team) + team.toUpperCase(Locale.ROOT));
        player.sendMessage(ChatColor.GRAY + "Protege ton lit et detruis celui de ton adversaire !");
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isAdmin(player)) return;

        if (!matchRunning || !teams.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        Block block = event.getBlock();
        String bedTeam = bedTeamForBlock(block);
        if (block.getType() == Material.BED_BLOCK) {
            event.setCancelled(true);
            if (bedTeam == null || !Boolean.TRUE.equals(bedAlive.get(bedTeam))) return;
            String ownTeam = teams.get(player.getUniqueId());
            if (bedTeam.equals(ownTeam)) {
                player.sendMessage(ChatColor.RED + "Tu ne peux pas casser ton propre lit !");
                return;
            }
            bedAlive.put(bedTeam, false);
            for (Location loc : getConfiguredBedLocations(bedTeam)) {
                loc.getBlock().setType(Material.AIR);
            }
            broadcastMatch(ChatColor.GOLD + "Le lit de l'equipe " + teamColor(bedTeam)
                    + bedTeam.toUpperCase(Locale.ROOT) + ChatColor.GOLD + " a ete detruit par "
                    + player.getName() + " !");
            return;
        }

        Material type = block.getType();
        if (type != Material.WOOL && type != Material.WOOD && type != Material.LOG && type != Material.LOG_2) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isAdmin(player)) return;
        if (!matchRunning || !teams.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Material type = event.getBlockPlaced().getType();
        if (type != Material.WOOL && type != Material.WOOD && type != Material.LOG && type != Material.LOG_2) {
            event.setCancelled(true);
            return;
        }
        placedBlocks.add(new BlockKey(event.getBlockPlaced().getWorld().getName(),
                event.getBlockPlaced().getX(), event.getBlockPlaced().getY(), event.getBlockPlaced().getZ()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!isEnabled()) return;
        Player player = event.getEntity();
        String team = teams.get(player.getUniqueId());
        if (team == null || !matchRunning) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setDeathMessage(null);

        if (Boolean.TRUE.equals(bedAlive.get(team))) {
            Location spawn = readLocation("bedfight.spawns." + team);
            if (spawn != null) respawnLocations.put(team, spawn);
            player.sendMessage(ChatColor.GREEN + "Ton lit est encore intact : tu peux revenir dans la partie !");
        } else {
            player.sendMessage(ChatColor.RED + "Ton lit est detruit : tu es elimine !");
            String enemyTeam = "red".equals(team) ? "blue" : "red";
            Player enemy = getPlayerByTeam(enemyTeam);
            if (enemy != null) {
                finishMatch(enemy.getName() + ChatColor.GREEN + " remporte la partie !");
            } else {
                finishMatch(ChatColor.YELLOW + player.getName() + " est elimine.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!isEnabled()) return;
        String team = teams.get(event.getPlayer().getUniqueId());
        if (team == null || !matchRunning) {
            Location lobby = getLobbySpawn();
            if (lobby != null) event.setRespawnLocation(lobby);
            return;
        }
        if (Boolean.TRUE.equals(bedAlive.get(team))) {
            Location spawn = readLocation("bedfight.spawns." + team);
            if (spawn != null) event.setRespawnLocation(spawn);
        } else {
            Location lobby = getLobbySpawn();
            if (lobby != null) event.setRespawnLocation(lobby);
        }
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                String currentTeam = teams.get(player.getUniqueId());
                if (currentTeam != null && matchRunning && Boolean.TRUE.equals(bedAlive.get(currentTeam))) {
                    prepareRespawnInventory(player, currentTeam);
                } else {
                    player.getInventory().clear();
                }
            }
        }, 2L);
    }

    private void prepareRespawnInventory(Player player, String team) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().addItem(new ItemStack(Material.WOOD_SWORD, 1));
        player.getInventory().addItem(new ItemStack(Material.WOOD_PICKAXE, 1));
        player.getInventory().addItem(new ItemStack(Material.WOOD_AXE, 1));
        player.getInventory().addItem(new ItemStack(Material.SHEARS, 1));
        player.getInventory().addItem(new ItemStack(Material.WOOL, 64, (short) 0, (byte) ("red".equals(team) ? 14 : 11)));
        player.getInventory().addItem(new ItemStack(Material.WOOD, 32));
        player.getInventory().setHelmet(new ItemStack(Material.LEATHER_HELMET));
        player.getInventory().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
        player.getInventory().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
        player.getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        player.updateInventory();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!isEnabled()) return;
        UUID id = event.getPlayer().getUniqueId();
        queue.remove(id);
        String team = teams.get(id);
        if (team != null && matchRunning && !finishing) {
            String enemyTeam = "red".equals(team) ? "blue" : "red";
            Player enemy = getPlayerByTeam(enemyTeam);
            if (enemy != null) finishMatch(enemy.getName() + ChatColor.GREEN + " remporte la partie par abandon !");
            else finishMatch(ChatColor.YELLOW + "La partie est annulee.");
        }
    }

    private Player getPlayerByTeam(String team) {
        for (Map.Entry<UUID, String> entry : teams.entrySet()) {
            if (team.equals(entry.getValue())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) return player;
            }
        }
        return null;
    }

    private void finishMatch(String message) {
        if (!matchRunning || finishing) return;
        finishing = true;
        matchRunning = false;
        broadcastMatch(ChatColor.GOLD + "Partie terminee ! " + message);
        cleanupPlacedBlocks();
        restoreBeds();
        final List<UUID> formerPlayers = new ArrayList<UUID>(teams.keySet());
        teams.clear();
        bedAlive.put("red", true);
        bedAlive.put("blue", true);

        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                Location lobby = getLobbySpawn();
                for (UUID id : formerPlayers) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) {
                        player.getInventory().clear();
                        player.getInventory().setArmorContents(null);
                        if (lobby != null) player.teleport(lobby);
                        addToQueue(player);
                    }
                }
                finishing = false;
                tryStartMatch();
            }
        }, 100L);
    }

    private void cleanupPlacedBlocks() {
        for (BlockKey key : placedBlocks) {
            World world = Bukkit.getWorld(key.world);
            if (world != null) {
                Block block = world.getBlockAt(key.x, key.y, key.z);
                block.setType(Material.AIR);
            }
        }
        placedBlocks.clear();
    }

    private void broadcastMatch(String message) {
        for (UUID id : teams.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(message);
        }
        for (UUID id : queue) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(message);
        }
    }

    private static final class BlockKey {
        private final String world;
        private final int x;
        private final int y;
        private final int z;

        private BlockKey(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof BlockKey)) return false;
            BlockKey other = (BlockKey) object;
            return world.equals(other.world) && x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            int result = world.hashCode();
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }
}
