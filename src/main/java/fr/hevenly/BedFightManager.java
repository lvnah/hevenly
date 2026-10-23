package fr.hevenly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

public class BedFightManager implements Listener, CommandExecutor {

    private static final long INVITE_TIMEOUT_MS = 60000L;

    private final HevenlyPlugin plugin;
    // File 1v1 : un joueur par entree.
    private final List<UUID> soloQueue = new ArrayList<UUID>();
    // File 2v2 : un groupe par entree.
    private final List<Party> duoQueue = new ArrayList<Party>();
    // Groupes 2v2 : chaque joueur d'un groupe pointe vers le meme objet.
    private final Map<UUID, Party> parties = new HashMap<UUID, Party>();
    private final Map<UUID, Invite> invites = new HashMap<UUID, Invite>();
    // Joueurs encore dans la partie en cours (un joueur elimine est retire).
    private final Map<UUID, String> teams = new HashMap<UUID, String>();
    // Participants du match en cours, eliminés compris, pour le retour au lobby.
    private final List<UUID> matchPlayers = new ArrayList<UUID>();
    private final List<Party> matchParties = new ArrayList<Party>();
    private final Set<BlockKey> placedBlocks = new LinkedHashSet<BlockKey>();
    private final Map<String, Boolean> bedAlive = new HashMap<String, Boolean>();
    private boolean matchRunning = false;
    private boolean finishing = false;
    private boolean duoMatch = false;

    public BedFightManager(HevenlyPlugin plugin) {
        this.plugin = plugin;
        bedAlive.put("red", true);
        bedAlive.put("blue", true);
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("bedfight.enabled", false);
    }

    public boolean isInMatch(Player player) {
        return matchRunning && teams.containsKey(player.getUniqueId());
    }

    // Appele par le GUI de la boussole : file 1v1 sur ce serveur.
    public void joinQueue(Player player) {
        if (!isEnabled()) {
            player.sendMessage(ChatColor.RED + "Le BedFight n'est pas active sur ce serveur.");
            return;
        }
        UUID id = player.getUniqueId();
        if (isInMatch(player)) {
            player.sendMessage(ChatColor.RED + "Tu es deja dans une partie.");
            return;
        }
        if (parties.containsKey(id)) {
            player.sendMessage(ChatColor.RED + "Tu es dans un groupe 2v2 : utilise /bfleave pour le quitter.");
            return;
        }
        if (soloQueue.contains(id)) {
            player.sendMessage(ChatColor.RED + "Tu es deja dans la file d'attente.");
            return;
        }
        addToQueue(player);
    }

    // Appele par le GUI de la boussole : file 2v2 avec le groupe du joueur.
    public void joinDuoQueue(Player player) {
        if (!isEnabled()) {
            player.sendMessage(ChatColor.RED + "Le BedFight n'est pas active sur ce serveur.");
            return;
        }
        if (isInMatch(player)) {
            player.sendMessage(ChatColor.RED + "Tu es deja dans une partie.");
            return;
        }
        Party party = parties.get(player.getUniqueId());
        if (party == null) {
            player.sendMessage(ChatColor.RED + "Tu n'as pas de groupe. Invite quelqu'un avec /invite <pseudo>.");
            return;
        }
        if (duoQueue.contains(party)) {
            player.sendMessage(ChatColor.RED + "Ton groupe est deja dans la file 2v2.");
            return;
        }
        duoQueue.add(party);
        for (UUID id : party.members()) {
            Player member = Bukkit.getPlayer(id);
            if (member != null) {
                member.sendMessage(ChatColor.GOLD + "Hevenly BedFight " + ChatColor.GRAY + "» "
                        + ChatColor.YELLOW + "Groupe 2v2 : recherche d'un adversaire...");
            }
        }
        tryStartMatch();
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        String[] commands = {"bfsetspawn", "bfsetbed", "bfstart", "bfleave", "invite"};
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
            if (soloQueue.size() < 2 && duoQueue.size() < 2) {
                sender.sendMessage(ChatColor.RED + "Il faut deux joueurs ou deux groupes dans la file.");
                return true;
            }
            if (!isArenaConfigured()) {
                sender.sendMessage(ChatColor.RED + "Configure les deux spawns et les deux lits avant de lancer une partie.");
                return true;
            }
            startNextMatch();
            sender.sendMessage(ChatColor.GREEN + "Partie BedFight demarree.");
            return true;
        }

        if (name.equals("bfleave")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }
            leaveQueue((Player) sender);
            return true;
        }

        if (name.equals("invite")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }
            handleInvite((Player) sender, args);
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

    // --------------------------------------------------
    // GROUPES 2V2 : INVITATION ET ACCEPTATION
    // --------------------------------------------------

    private void handleInvite(Player player, String[] args) {
        if (!isEnabled()) {
            player.sendMessage(ChatColor.RED + "Le BedFight n'est pas active sur ce serveur.");
            return;
        }
        if (args.length != 1) {
            player.sendMessage(ChatColor.RED + "Utilisation : /invite <pseudo> ou /invite accept");
            return;
        }
        if (args[0].equalsIgnoreCase("accept")) {
            acceptInvite(player);
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Ce joueur n'est pas connecte.");
            return;
        }
        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "Tu ne peux pas t'inviter toi-meme.");
            return;
        }
        String error = inviteError(player, target);
        if (error != null) {
            player.sendMessage(ChatColor.RED + error);
            return;
        }

        invites.put(target.getUniqueId(), new Invite(player.getUniqueId(), System.currentTimeMillis()));
        player.sendMessage(ChatColor.GREEN + "Invitation envoyee a " + target.getName() + ".");
        target.sendMessage(ChatColor.GOLD + player.getName() + ChatColor.YELLOW
                + " t'invite a jouer en 2v2 BedFight. " + ChatColor.GRAY + "Tape /invite accept.");
    }

    private void acceptInvite(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || System.currentTimeMillis() - invite.time > INVITE_TIMEOUT_MS) {
            player.sendMessage(ChatColor.RED + "Tu n'as aucune invitation en attente.");
            return;
        }
        Player inviter = Bukkit.getPlayer(invite.from);
        if (inviter == null) {
            player.sendMessage(ChatColor.RED + "Ce joueur n'est plus connecte.");
            return;
        }
        String error = inviteError(inviter, player);
        if (error != null) {
            player.sendMessage(ChatColor.RED + error);
            return;
        }

        Party party = new Party(inviter.getUniqueId(), player.getUniqueId());
        parties.put(inviter.getUniqueId(), party);
        parties.put(player.getUniqueId(), party);

        String text = ChatColor.GREEN + "Groupe 2v2 forme ! Ouvre le menu Game et clique sur 2v2 Unranked.";
        inviter.sendMessage(text);
        player.sendMessage(text);
    }

    // Retourne le motif du refus, ou null si l'invitation est possible.
    private String inviteError(Player inviter, Player other) {
        if (isInMatch(inviter) || isInMatch(other)) {
            return "Impossible de former un groupe pendant une partie.";
        }
        if (parties.containsKey(inviter.getUniqueId())) {
            return "Ton groupe est deja complet.";
        }
        if (parties.containsKey(other.getUniqueId())) {
            return other.getName() + " a deja un groupe.";
        }
        if (soloQueue.contains(inviter.getUniqueId()) || soloQueue.contains(other.getUniqueId())) {
            return "Quitte la file 1v1 avant de former un groupe.";
        }
        return null;
    }

    private void dissolveParty(Party party, UUID except, String message) {
        duoQueue.remove(party);
        for (UUID id : party.members()) {
            parties.remove(id);
            if (id.equals(except) || message == null) continue;
            Player member = Bukkit.getPlayer(id);
            if (member != null) member.sendMessage(message);
        }
    }

    // --------------------------------------------------
    // FILES D'ATTENTE
    // --------------------------------------------------

    private void leaveQueue(Player player) {
        if (isInMatch(player)) {
            player.sendMessage(ChatColor.RED + "Tu ne peux pas quitter une partie en cours avec /bfleave.");
            player.sendMessage(ChatColor.GRAY + "Deconnecte-toi pour abandonner la partie.");
            return;
        }
        UUID id = player.getUniqueId();
        boolean queued = soloQueue.remove(id);
        Party party = parties.get(id);
        if (party != null) {
            queued = true;
            dissolveParty(party, id, ChatColor.YELLOW + player.getName() + " a quitte le groupe.");
        }
        if (!queued) {
            player.sendMessage(ChatColor.RED + "Tu n'es pas dans la file d'attente.");
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "Tu as quitte la file BedFight.");
        Location lobby = getLobbySpawn();
        if (lobby != null) player.teleport(lobby);
    }

    private void addToQueue(Player player) {
        UUID id = player.getUniqueId();
        if (teams.containsKey(id) || soloQueue.contains(id)) return;
        soloQueue.add(id);
        // Garde la boussole et le name tag : ce sont les seuls moyens de quitter la file.
        plugin.giveSpecialItems(player);
        Location lobby = getLobbySpawn();
        if (lobby != null) player.teleport(lobby);
        player.sendMessage(ChatColor.GOLD + "Hevenly BedFight " + ChatColor.GRAY + "» "
                + ChatColor.YELLOW + "Recherche d'un adversaire...");
        tryStartMatch();
    }

    private List<UUID> queuedPlayerIds() {
        List<UUID> ids = new ArrayList<UUID>(soloQueue);
        for (Party party : duoQueue) {
            ids.addAll(party.members());
        }
        return ids;
    }

    // --------------------------------------------------
    // ARENE
    // --------------------------------------------------

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

    private boolean isArenaConfigured() {
        return readLocation("bedfight.spawns.red") != null
                && readLocation("bedfight.spawns.blue") != null
                && !getConfiguredBedLocations("red").isEmpty()
                && !getConfiguredBedLocations("blue").isEmpty();
    }

    // --------------------------------------------------
    // DEMARRAGE DES PARTIES
    // --------------------------------------------------

    private void tryStartMatch() {
        if (!isEnabled() || matchRunning || finishing) return;
        if (soloQueue.size() < 2 && duoQueue.size() < 2) return;
        if (!isArenaConfigured()) {
            for (UUID id : queuedPlayerIds()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(ChatColor.RED + "La map BedFight n'est pas configuree. Contacte un administrateur.");
            }
            return;
        }
        startNextMatch();
    }

    // Une partie 2v2 passe avant une partie 1v1 quand les deux sont possibles.
    private boolean startNextMatch() {
        if (matchRunning) return false;
        if (duoQueue.size() >= 2) {
            startDuoMatch();
            return true;
        }
        if (soloQueue.size() >= 2) {
            startSoloMatch();
            return true;
        }
        return false;
    }

    private void startDuoMatch() {
        Party red = duoQueue.remove(0);
        Party blue = duoQueue.remove(0);
        duoMatch = true;
        matchParties.clear();
        matchParties.add(red);
        matchParties.add(blue);
        beginMatch(red.members(), blue.members());
    }

    private void startSoloMatch() {
        UUID red = soloQueue.remove(0);
        UUID blue = soloQueue.remove(0);
        duoMatch = false;
        matchParties.clear();
        beginMatch(Arrays.asList(red), Arrays.asList(blue));
    }

    private void beginMatch(List<UUID> reds, List<UUID> blues) {
        restoreBeds();
        placedBlocks.clear();
        bedAlive.put("red", true);
        bedAlive.put("blue", true);
        teams.clear();
        matchPlayers.clear();
        matchRunning = true;
        finishing = false;

        for (UUID id : reds) addToMatch(id, "red");
        for (UUID id : blues) addToMatch(id, "blue");

        for (UUID id : matchPlayers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) preparePlayer(player, teams.get(id));
        }
        broadcastMatch(ChatColor.GOLD + "La partie commence ! " + teamNames("red")
                + ChatColor.GRAY + " contre " + teamNames("blue") + ChatColor.GOLD + ".");
        checkMatchEnd("");
    }

    private void addToMatch(UUID id, String team) {
        if (Bukkit.getPlayer(id) == null) return;
        teams.put(id, team);
        matchPlayers.add(id);
    }

    private void preparePlayer(Player player, String team) {
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
        giveMatchKit(player, team);
        Location spawn = readLocation("bedfight.spawns." + team);
        if (spawn != null) player.teleport(spawn);
        player.sendMessage(ChatColor.GOLD + "Ton equipe : " + teamColor(team) + team.toUpperCase(Locale.ROOT));
        player.sendMessage(ChatColor.GRAY + "Protege ton lit et detruis celui de ton adversaire !");
        player.updateInventory();
    }

    // Kit identique pour tous les BedFight : cuir teinte aux couleurs de l'equipe.
    private void giveMatchKit(Player player, String team) {
        boolean red = "red".equals(team);
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(null);
        inv.setItem(0, unbreakable(new ItemStack(Material.WOOD_SWORD)));
        inv.setItem(1, new ItemStack(Material.WOOL, 64, (short) 0, (byte) (red ? 14 : 11)));
        inv.setItem(2, unbreakable(new ItemStack(Material.SHEARS)));
        inv.setItem(3, unbreakable(new ItemStack(Material.WOOD_AXE)));
        inv.setItem(4, new ItemStack(Material.WOOD_PICKAXE));
        inv.setItem(5, new ItemStack(Material.WOOD, 32));
        Color color = red ? Color.RED : Color.BLUE;
        inv.setHelmet(leather(Material.LEATHER_HELMET, color));
        inv.setChestplate(leather(Material.LEATHER_CHESTPLATE, color));
        inv.setLeggings(leather(Material.LEATHER_LEGGINGS, color));
        inv.setBoots(leather(Material.LEATHER_BOOTS, color));
        player.updateInventory();
    }

    private ItemStack unbreakable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.spigot().setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack leather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    // --------------------------------------------------
    // REGLES DE PARTIE
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isAdmin(player)) return;

        if (!isInMatch(player)) {
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
        if (!isInMatch(player)) {
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
        if (!isInMatch(player)) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setDeathMessage(null);

        String team = teams.get(player.getUniqueId());
        if (Boolean.TRUE.equals(bedAlive.get(team))) {
            player.sendMessage(ChatColor.GREEN + "Ton lit est encore intact : tu peux revenir dans la partie !");
            return;
        }

        // Lit detruit : le joueur est elimine, sans respawn dans la partie.
        teams.remove(player.getUniqueId());
        player.sendMessage(ChatColor.RED + "Ton lit est detruit : tu es elimine !");
        checkMatchEnd("");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!isEnabled()) return;
        // Hors partie, le respawn au lobby est gere par HevenlyPlugin.
        final Player player = event.getPlayer();
        String team = teams.get(player.getUniqueId());
        if (team == null || !matchRunning) return;

        if (Boolean.TRUE.equals(bedAlive.get(team))) {
            Location spawn = readLocation("bedfight.spawns." + team);
            if (spawn != null) event.setRespawnLocation(spawn);
        } else {
            // Le lit a ete detruit pendant la mort : elimination au respawn.
            teams.remove(player.getUniqueId());
            Location lobby = getLobbySpawn();
            if (lobby != null) event.setRespawnLocation(lobby);
            checkMatchEnd("");
        }

        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                String currentTeam = teams.get(player.getUniqueId());
                if (currentTeam != null && matchRunning && Boolean.TRUE.equals(bedAlive.get(currentTeam))) {
                    prepareRespawnInventory(player, currentTeam);
                } else if (!isInMatch(player)) {
                    plugin.giveSpecialItems(player);
                }
            }
        }, 2L);
    }

    private void prepareRespawnInventory(Player player, String team) {
        giveMatchKit(player, team);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        soloQueue.remove(id);
        Party party = parties.get(id);
        if (party != null) {
            dissolveParty(party, id, ChatColor.RED + player.getName() + " a quitte ton groupe.");
        }

        if (isInMatch(player)) {
            // Quitter en partie compte comme une elimination.
            teams.remove(id);
            checkMatchEnd(" par abandon");
        }
    }

    private int countAlive(String team) {
        int count = 0;
        for (String value : teams.values()) {
            if (team.equals(value)) count++;
        }
        return count;
    }

    private String teamNames(String team) {
        StringBuilder names = new StringBuilder();
        for (UUID id : matchPlayers) {
            if (!team.equals(teams.get(id))) continue;
            Player player = Bukkit.getPlayer(id);
            if (names.length() > 0) names.append(ChatColor.GRAY).append(", ");
            names.append(teamColor(team)).append(player == null ? "?" : player.getName());
        }
        return names.toString();
    }

    // Fin de partie quand une equipe n'a plus aucun joueur en jeu.
    private void checkMatchEnd(String reason) {
        if (!matchRunning || finishing) return;
        boolean redAlive = countAlive("red") > 0;
        boolean blueAlive = countAlive("blue") > 0;
        if (redAlive && blueAlive) return;
        if (!redAlive && !blueAlive) {
            finishMatch(ChatColor.YELLOW + "La partie est annulee.");
            return;
        }
        String winner = redAlive ? "red" : "blue";
        finishMatch(teamNames(winner) + ChatColor.GREEN + " remporte la partie" + reason + " !");
    }

    private void finishMatch(String message) {
        if (!matchRunning || finishing) return;
        finishing = true;
        matchRunning = false;
        broadcastMatch(ChatColor.GOLD + "Partie terminee ! " + message);
        cleanupPlacedBlocks();
        restoreBeds();
        final List<UUID> formerPlayers = new ArrayList<UUID>(matchPlayers);
        final List<Party> formerParties = new ArrayList<Party>(matchParties);
        final boolean wasDuo = duoMatch;
        teams.clear();
        matchPlayers.clear();
        matchParties.clear();
        bedAlive.put("red", true);
        bedAlive.put("blue", true);

        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                Location lobby = getLobbySpawn();
                for (UUID id : formerPlayers) {
                    Player player = Bukkit.getPlayer(id);
                    if (player == null) continue;
                    player.getInventory().clear();
                    player.getInventory().setArmorContents(null);
                    if (lobby != null) player.teleport(lobby);
                    if (wasDuo) plugin.giveSpecialItems(player);
                }

                // Les joueurs sont remis dans la file de meme type que la partie.
                if (wasDuo) {
                    for (Party party : formerParties) {
                        requeueParty(party);
                    }
                } else {
                    for (UUID id : formerPlayers) {
                        Player player = Bukkit.getPlayer(id);
                        if (player != null) addToQueue(player);
                    }
                }

                finishing = false;
                tryStartMatch();
            }
        }, 100L);
    }

    private void requeueParty(Party party) {
        // Le groupe a pu etre dissous pendant la partie (depart d'un membre).
        if (parties.get(party.first) != party || parties.get(party.second) != party) return;
        for (UUID id : party.members()) {
            if (Bukkit.getPlayer(id) == null) {
                dissolveParty(party, null, null);
                return;
            }
        }
        duoQueue.add(party);
        for (UUID id : party.members()) {
            Bukkit.getPlayer(id).sendMessage(ChatColor.GRAY + "Ton groupe 2v2 est remis dans la file.");
        }
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
        for (UUID id : queuedPlayerIds()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(message);
        }
    }

    // Groupe 2v2 : deux joueurs qui partent ensemble.
    private static final class Party {
        private final UUID first;
        private final UUID second;

        private Party(UUID first, UUID second) {
            this.first = first;
            this.second = second;
        }

        private List<UUID> members() {
            return Arrays.asList(first, second);
        }
    }

    // Invitation 2v2 en attente, expire apres INVITE_TIMEOUT_MS.
    private static final class Invite {
        private final UUID from;
        private final long time;

        private Invite(UUID from, long time) {
            this.from = from;
            this.time = time;
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
