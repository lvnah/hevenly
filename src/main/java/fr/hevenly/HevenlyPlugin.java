package fr.hevenly;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

public class HevenlyPlugin extends JavaPlugin implements Listener {

    private static final String GAME_NAME = ChatColor.RED + "Game";
    private static final String CONFIG_NAME = ChatColor.DARK_GRAY + "Config";

    private static final String[] VALID_RANKS = {
        "default", "plus", "premium", "helper", "mod", "owner"
    };

    // Pseudos en minuscules -> grade.
    private final Map<String, String> playerRanks =
            new ConcurrentHashMap<String, String>();
    private final Map<String, Boolean> nametagEnabled =
            new ConcurrentHashMap<String, Boolean>();

    private static final String GAME_GUI_TITLE = ChatColor.DARK_RED + "Hevenly » BedFight";
    private static final String CONFIG_GUI_TITLE = ChatColor.DARK_GRAY + "Configuration du Nametag";
    private static final String BUNGEE_CHANNEL = "BungeeCord";

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Cree config.yml si le fichier n'existe pas.
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveConfig();
        }

        getConfig().addDefault("bedfight.servers.unranked", "bedfight-unranked");
        getConfig().addDefault("bedfight.servers.ranked", "bedfight-ranked");
        getConfig().options().copyDefaults(true);
        saveConfig();

        loadRanks();
        loadNametagSettings();
        getServer().getMessenger().registerOutgoingPluginChannel(this, BUNGEE_CHANNEL);

        // Enregistre les commandes declarees dans plugin.yml.
        if (getCommand("setspawn") != null) {
            getCommand("setspawn").setExecutor(this);
        }
        if (getCommand("spawn") != null) {
            getCommand("spawn").setExecutor(this);
        }
        if (getCommand("setrank") != null) {
            getCommand("setrank").setExecutor(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        // Met a jour le scoreboard une fois par seconde.
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                updateScoreboards();
            }
        }, 0L, 20L);

        // Actualise les grades et les couleurs après le chargement.
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                refreshAllVisuals();
            }
        }, 1L);

        getLogger().info("Hevenly est actif !");
    }

    @Override
    public void onDisable() {
        saveRanks();
        saveNametagSettings();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getLogger().info("Hevenly est disable !");
    }

    // --------------------------------------------------
    // COMMANDES
    // --------------------------------------------------

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);

        if (commandName.equals("setspawn")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            Player player = (Player) sender;

            if (!player.hasPermission("hevenly.setspawn")) {
                player.sendMessage(ChatColor.RED
                        + "Tu n'as pas la permission !");
                return true;
            }

            Location loc = player.getLocation();

            getConfig().set("spawn.world", loc.getWorld().getName());
            getConfig().set("spawn.x", loc.getX());
            getConfig().set("spawn.y", loc.getY());
            getConfig().set("spawn.z", loc.getZ());
            getConfig().set("spawn.yaw", loc.getYaw());
            getConfig().set("spawn.pitch", loc.getPitch());

            saveConfig();

            player.sendMessage(ChatColor.GREEN
                    + "Le spawn a ete defini avec succes !");
            return true;
        }

        if (commandName.equals("spawn")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            Player player = (Player) sender;
            Location spawn = getSpawn();

            if (spawn == null) {
                player.sendMessage(ChatColor.RED
                        + "Le spawn n'a pas ete defini ou son monde est introuvable !");
                return true;
            }

            player.teleport(spawn);
            player.sendMessage(ChatColor.GREEN
                    + "Tu as ete teleporte au spawn !");
            return true;
        }

        if (commandName.equals("setrank")) {
            if (!sender.hasPermission("hevenly.setrank")) {
                sender.sendMessage(ChatColor.RED
                        + "Tu n'as pas la permission !");
                return true;
            }

            if (args.length != 2) {
                sender.sendMessage(ChatColor.RED
                        + "Utilisation : /setrank <joueur> <default|plus|premium|helper|mod|owner>");
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);

            if (target == null) {
                sender.sendMessage(ChatColor.RED
                        + "Ce joueur n'est pas connecte.");
                return true;
            }

            String newRank = args[1].toLowerCase(Locale.ROOT);

            if (!Arrays.asList(VALID_RANKS).contains(newRank)) {
                sender.sendMessage(ChatColor.RED
                        + "Grade invalide. Grades disponibles : default, plus, premium, helper, mod, owner.");
                return true;
            }

            String targetKey = getPlayerKey(target);
            playerRanks.put(targetKey, newRank);

            saveRanks();
            updatePlayerVisuals(target);

            String coloredRank = getRankColor(newRank)
                    + newRank.toUpperCase(Locale.ROOT);

            sender.sendMessage(ChatColor.GREEN
                    + "Le grade de " + target.getName()
                    + " est maintenant " + coloredRank + ChatColor.GREEN + ".");

            if (!(sender instanceof Player)
                    || !((Player) sender).getUniqueId().equals(target.getUniqueId())) {
                target.sendMessage(ChatColor.GREEN
                        + "Ton grade a ete modifie : " + coloredRank
                        + ChatColor.GREEN + ".");
            }

            return true;
        }

        return false;
    }

    // --------------------------------------------------
    // GESTION ET SAUVEGARDE DES GRADES
    // --------------------------------------------------

    private String getPlayerKey(Player player) {
        return player.getName().toLowerCase(Locale.ROOT);
    }

    private void loadRanks() {
        playerRanks.clear();

        if (!getConfig().isConfigurationSection("ranks")) {
            return;
        }

        for (String playerName : getConfig().getConfigurationSection("ranks").getKeys(false)) {
            String rank = getConfig().getString("ranks." + playerName, "default")
                    .toLowerCase(Locale.ROOT);

            if (!Arrays.asList(VALID_RANKS).contains(rank)) {
                rank = "default";
            }

            playerRanks.put(playerName.toLowerCase(Locale.ROOT), rank);
        }
    }

    private void saveRanks() {
        // Ne touche pas aux autres sections de config.yml, notamment au spawn.
        getConfig().set("ranks", null);

        for (Map.Entry<String, String> entry : playerRanks.entrySet()) {
            getConfig().set("ranks." + entry.getKey(), entry.getValue());
        }

        saveConfig();
    }

    private String getRank(Player player) {
        return playerRanks.getOrDefault(getPlayerKey(player), "default");
    }

    private String getRankLabel(String rank) {
        switch (rank.toLowerCase(Locale.ROOT)) {
            case "owner": return "OWNER";
            case "mod": return "MOD";
            case "helper": return "HELPER";
            case "premium": return "PREMIUM";
            case "plus": return "PLUS";
            default: return "DEFAULT";
        }
    }

    // Format demandé : §7[<couleur du grade>GRADE§7]
    private String getRankPrefix(String rank) {
        return ChatColor.GRAY + "[" + getRankColor(rank)
                + getRankLabel(rank) + ChatColor.GRAY + "] ";
    }

    private ChatColor getRankColor(String rank) {
        switch (rank.toLowerCase(Locale.ROOT)) {
            case "owner":
                return ChatColor.DARK_RED;
            case "mod":
                return ChatColor.DARK_PURPLE;
            case "helper":
                return ChatColor.YELLOW;
            case "premium":
                return ChatColor.AQUA;
            case "plus":
                return ChatColor.GREEN;
            default:
                return ChatColor.WHITE;
        }
    }

    // --------------------------------------------------
    // CHAT COLORE
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        ChatColor nameColor = getRankColor(getRank(player));

        // Les joueurs ne peuvent pas utiliser de codes couleur dans leur message.
        String message = ChatColor.stripColor(event.getMessage());
        event.setMessage(message);

        // Echappe les pourcentages pour eviter les erreurs de formatage.
        String safeName = player.getName().replace("%", "%%");

        String rankPrefix = getRankPrefix(getRank(player));
        event.setFormat(
                ChatColor.RESET + rankPrefix + nameColor + safeName
                + ChatColor.DARK_GRAY + " > "
                + ChatColor.WHITE + "%2$s"
        );
    }

    // --------------------------------------------------
    // COULEUR DU PSEUDO : NAMETAG ET TAB
    // Compatible Spigot 1.8.8
    // --------------------------------------------------

    private String getRankTeamName(Player target) {
        // Une team par joueur permet au GUI de masquer son prefixe sans
        // modifier celui des autres joueurs qui ont le meme grade.
        String teamName = "h_" + target.getName();
        return teamName.length() > 16 ? teamName.substring(0, 16) : teamName;
    }

    private void applyPlayerToBoard(Scoreboard board, Player target) {
        String rank = getRank(target);
        String teamName = getRankTeamName(target);

        Team team = board.getTeam(teamName);

        if (team == null) {
            team = board.registerNewTeam(teamName);
        }

        // Prefixe [RANK] colore au-dessus de la tete et dans le TAB.
        String teamPrefix;
        if (nametagEnabled.getOrDefault(getPlayerKey(target), true)) {
            String label = getRankLabel(rank);
            // Format exact : §7[§6VIP§7]. Les libelles restent assez courts pour la limite 1.8.
            teamPrefix = ChatColor.GRAY + "[" + getRankColor(rank) + label + ChatColor.GRAY + "]";
        } else {
            teamPrefix = getRankColor(rank).toString();
        }
        if (teamPrefix.length() > 16) teamPrefix = teamPrefix.substring(0, 16);
        team.setPrefix(teamPrefix);
        team.setSuffix("");

        // Evite qu'un joueur reste dans une ancienne equipe de grade.
        for (Team existing : board.getTeams()) {
            if (existing.equals(team)) {
                continue;
            }

            if (existing.hasEntry(target.getName())) {
                existing.removeEntry(target.getName());
            }
        }

        if (!team.hasEntry(target.getName())) {
            team.addEntry(target.getName());
        }
    }

    private Scoreboard getOrCreatePlayerBoard(Player viewer) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();

        if (manager == null) {
            return viewer.getScoreboard();
        }

        Scoreboard board = viewer.getScoreboard();

        // Utilise un scoreboard dedie pour ne pas modifier le scoreboard principal.
        if (board == manager.getMainScoreboard()) {
            board = manager.getNewScoreboard();
            viewer.setScoreboard(board);
        }

        return board;
    }

    private void updatePlayerVisuals(Player target) {
        // Le display name est egalement utilise par certains plugins de chat.
        target.setDisplayName(
                getRankPrefix(getRank(target)) + getRankColor(getRank(target))
                + target.getName() + ChatColor.RESET
        );

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = getOrCreatePlayerBoard(viewer);
            applyPlayerToBoard(board, target);
        }

        // Actualise aussi les autres joueurs sur le scoreboard de chaque viewer.
        refreshAllBoards();
    }

    private void refreshAllBoards() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = getOrCreatePlayerBoard(viewer);

            for (Player target : Bukkit.getOnlinePlayers()) {
                applyPlayerToBoard(board, target);
            }
        }
    }

    private void refreshAllVisuals() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setDisplayName(
                    getRankPrefix(getRank(player))
                    + getRankColor(getRank(player))
                    + player.getName()
                    + ChatColor.RESET
            );
        }

        refreshAllBoards();
    }

    // --------------------------------------------------
    // SCOREBOARD SIDEBAR - mises a jour sans recreer les lignes
    // --------------------------------------------------

    private Scoreboard getScoreboard(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return player.getScoreboard();

        Scoreboard board = player.getScoreboard();
        if (board == manager.getMainScoreboard()) {
            board = manager.getNewScoreboard();
            player.setScoreboard(board);
        }
        return board;
    }

    private int getPlayerElo(Player player) {
        return getConfig().getInt("ranked.elo."
                + player.getName().toLowerCase(Locale.ROOT), 1000);
    }

    private void updateScoreboards() {
        int onlineCount = Bukkit.getOnlinePlayers().size();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = getScoreboard(player);
            Objective objective = board.getObjective("hevenly");

            if (objective == null) {
                objective = board.registerNewObjective("hevenly", "dummy");
                objective.setDisplayName(ChatColor.DARK_RED + "Hevenly");
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            } else {
                if (objective.getDisplaySlot() != DisplaySlot.SIDEBAR) {
                    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                }
                if (!objective.getDisplayName().equals(ChatColor.DARK_RED + "Hevenly")) {
                    objective.setDisplayName(ChatColor.DARK_RED + "Hevenly");
                }
            }

            String rank = getRank(player).toUpperCase(Locale.ROOT);
            setSidebarTeamLine(board, objective, "hv_sb1", "§1", "§7§m-----------", "", 6);
            setSidebarTeamLine(board, objective, "hv_sb2", "§2", "§fJoueur en ", "§fligne: §a" + onlineCount, 5);
            setSidebarTeamLine(board, objective, "hv_sb3", "§3", "§fGrade: §a", rank, 4);
            setSidebarTeamLine(board, objective, "hv_sb4", "§4", "§fRanked: §a", String.valueOf(getPlayerElo(player)), 3);
            setSidebarTeamLine(board, objective, "hv_sb5", "§5", "§7§m-----------", "", 2);
            setSidebarTeamLine(board, objective, "hv_sb6", "§6", "§chevenly.games", "", 1);
        }
    }

    private void setSidebarTeamLine(Scoreboard board, Objective objective,
                                    String teamName, String entry,
                                    String prefix, String suffix, int score) {
        Team team = board.getTeam(teamName);
        if (team == null) team = board.registerNewTeam(teamName);

        if (prefix.length() > 16) prefix = prefix.substring(0, 16);
        if (suffix.length() > 16) suffix = suffix.substring(0, 16);

        if (!team.getPrefix().equals(prefix)) team.setPrefix(prefix);
        if (!team.getSuffix().equals(suffix)) team.setSuffix(suffix);
        if (!team.hasEntry(entry)) team.addEntry(entry);

        // Ne change le score que si nécessaire : évite le clignotement côté client.
        if (!objective.getScore(entry).isScoreSet()
                || objective.getScore(entry).getScore() != score) {
            objective.getScore(entry).setScore(score);
        }
    }

    // --------------------------------------------------
    // RECUPERATION DU SPAWN
    // --------------------------------------------------

    private Location getSpawn() {
        if (!getConfig().contains("spawn.world")) {
            return null;
        }

        String worldName = getConfig().getString("spawn.world");
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            return null;
        }

        double x = getConfig().getDouble("spawn.x");
        double y = getConfig().getDouble("spawn.y");
        double z = getConfig().getDouble("spawn.z");
        float yaw = (float) getConfig().getDouble("spawn.yaw");
        float pitch = (float) getConfig().getDouble("spawn.pitch");

        return new Location(world, x, y, z, yaw, pitch);
    }

    // --------------------------------------------------
    // CREATION DES OBJETS SPECIAUX
    // --------------------------------------------------

    private ItemStack createGameItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(GAME_NAME);
        item.setItemMeta(meta);

        return item;
    }

    private ItemStack createConfigItem() {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(CONFIG_NAME);
        item.setItemMeta(meta);

        return item;
    }

    private boolean isProtectedItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR
                || !item.hasItemMeta()) {
            return false;
        }

        if (!item.getItemMeta().hasDisplayName()) {
            return false;
        }

        String name = item.getItemMeta().getDisplayName();

        return (item.getType() == Material.COMPASS
                    && GAME_NAME.equals(name))
                || (item.getType() == Material.NAME_TAG
                    && CONFIG_NAME.equals(name));
    }

    private void giveSpecialItems(Player player) {
        PlayerInventory inventory = player.getInventory();

        // Slot 0 : boussole Game.
        inventory.setItem(0, createGameItem());

        // Slot 8 : name tag Config.
        inventory.setItem(8, createConfigItem());

        player.updateInventory();
    }

    // --------------------------------------------------
    // GUI GAME ET CONFIGURATION DU NAMETAG
    // --------------------------------------------------

    private ItemStack createGuiItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (loreLines != null && loreLines.length > 0) {
            java.util.List<String> lore = new java.util.ArrayList<String>();
            for (String line : loreLines) lore.add(line);
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void openGameGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, GAME_GUI_TITLE);
        inventory.setItem(11, createGuiItem(Material.IRON_SWORD,
                ChatColor.GREEN + "BedFight 1v1 Unranked",
                ChatColor.GRAY + "Partie sans Elo", ChatColor.YELLOW + "Clique pour rejoindre"));
        inventory.setItem(15, createGuiItem(Material.DIAMOND_SWORD,
                ChatColor.RED + "BedFight 1v1 Ranked",
                ChatColor.GRAY + "Partie classee", ChatColor.YELLOW + "Clique pour rejoindre"));
        inventory.setItem(13, createGuiItem(Material.BOOK,
                ChatColor.DARK_RED + "Hevenly BedFight",
                ChatColor.GRAY + "Choisis ton mode de jeu"));
        player.openInventory(inventory);
    }

    private void openNametagGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, CONFIG_GUI_TITLE);
        boolean enabled = nametagEnabled.getOrDefault(getPlayerKey(player), true);
        inventory.setItem(13, createGuiItem(enabled ? Material.NAME_TAG : Material.BARRIER,
                (enabled ? ChatColor.GREEN : ChatColor.RED) + "Prefixe du grade : "
                        + (enabled ? "ACTIVE" : "DESACTIVE"),
                ChatColor.GRAY + "Affiche le grade au-dessus de la tete et dans le TAB.",
                ChatColor.YELLOW + "Clique pour changer"));
        inventory.setItem(22, createGuiItem(Material.ARROW,
                ChatColor.GRAY + "Fermer", ChatColor.DARK_GRAY + "Retour au jeu"));
        player.openInventory(inventory);
    }

    private void connectToServer(Player player, String mode) {
        String path = "bedfight.servers." + mode;
        String serverName = getConfig().getString(path, "").trim();
        if (serverName.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Le serveur BedFight n'est pas configure.");
            return;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(serverName);
            player.sendPluginMessage(this, BUNGEE_CHANNEL, bytes.toByteArray());
            player.closeInventory();
        } catch (IOException ex) {
            player.sendMessage(ChatColor.RED + "Impossible de rejoindre ce serveur.");
            getLogger().warning("Erreur de connexion BungeeCord : " + ex.getMessage());
        }
    }

    private void loadNametagSettings() {
        nametagEnabled.clear();
        if (!getConfig().isConfigurationSection("nametag.enabled")) return;
        for (String name : getConfig().getConfigurationSection("nametag.enabled").getKeys(false)) {
            nametagEnabled.put(name.toLowerCase(Locale.ROOT),
                    getConfig().getBoolean("nametag.enabled." + name, true));
        }
    }

    private void saveNametagSettings() {
        getConfig().set("nametag.enabled", null);
        for (Map.Entry<String, Boolean> entry : nametagEnabled.entrySet()) {
            getConfig().set("nametag.enabled." + entry.getKey(), entry.getValue());
        }
        saveConfig();
    }

    @EventHandler
    public void onSpecialItemUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return;

        String name = item.getItemMeta().getDisplayName();
        if (item.getType() == Material.COMPASS && GAME_NAME.equals(name)) {
            event.setCancelled(true);
            openGameGui(event.getPlayer());
        } else if (item.getType() == Material.NAME_TAG && CONFIG_NAME.equals(name)) {
            event.setCancelled(true);
            openNametagGui(event.getPlayer());
        }
    }

    @EventHandler
    public void onHevenlyGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        if (!GAME_GUI_TITLE.equals(title) && !CONFIG_GUI_TITLE.equals(title)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        Player player = (Player) event.getWhoClicked();
        if (GAME_GUI_TITLE.equals(title)) {
            if (event.getRawSlot() == 11) {
                connectToServer(player, "unranked");
            } else if (event.getRawSlot() == 15) {
                connectToServer(player, "ranked");
            }
        } else if (CONFIG_GUI_TITLE.equals(title)) {
            if (event.getRawSlot() == 13) {
                String key = getPlayerKey(player);
                boolean enabled = !nametagEnabled.getOrDefault(key, true);
                nametagEnabled.put(key, enabled);
                saveNametagSettings();
                refreshAllBoards();
                openNametagGui(player);
                player.sendMessage(ChatColor.GREEN + "Prefixe du grade "
                        + (enabled ? "active" : "desactive") + ".");
            } else if (event.getRawSlot() == 22) {
                player.closeInventory();
            }
        }
    }

    // --------------------------------------------------
    // CONNEXION : SPAWN, OBJETS ET GRADE
    // --------------------------------------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        // Initialise le grade par defaut uniquement si aucun grade n'est sauvegarde.
        String key = getPlayerKey(player);
        if (!playerRanks.containsKey(key)) {
            playerRanks.put(key, "default");
            saveRanks();
        }

        Location spawn = getSpawn();

        if (spawn != null) {
            player.teleport(spawn);
        }

        giveSpecialItems(player);

        // Attend que le joueur soit totalement connecte avant d'actualiser le TAB.
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    refreshAllVisuals();
                    updateScoreboards();
                }
            }
        }, 2L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Recalcule les equipes restantes après le depart.
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                refreshAllVisuals();
            }
        });
    }

    // --------------------------------------------------
    // MORT : NE PAS LAISSER TOMBER LES OBJETS
    // --------------------------------------------------

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Iterator<ItemStack> iterator = event.getDrops().iterator();

        while (iterator.hasNext()) {
            ItemStack item = iterator.next();

            if (isProtectedItem(item)) {
                iterator.remove();
            }
        }
    }

    // --------------------------------------------------
    // REAPPARITION AU SPAWN
    // --------------------------------------------------

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Location spawn = getSpawn();

        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }

        final Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    giveSpecialItems(player);
                    updatePlayerVisuals(player);
                    updateScoreboards();
                }
            }
        });
    }

    // --------------------------------------------------
    // INTERDIRE DE DEPLACER LES OBJETS
    // --------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();

        // Bloquer les clics sur les slots reserves.
        if (event.getClickedInventory() != null
                && event.getClickedInventory().equals(player.getInventory())) {

            int slot = event.getSlot();

            if (slot == 0 || slot == 8) {
                event.setCancelled(true);
                return;
            }
        }

        // Bloquer les echanges avec les touches numeriques 1 et 9.
        if (event.getHotbarButton() == 0
                || event.getHotbarButton() == 8) {
            event.setCancelled(true);
            return;
        }

        // Bloquer le deplacement des objets proteges.
        if (isProtectedItem(event.getCurrentItem())
                || isProtectedItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    // --------------------------------------------------
    // INTERDIRE LE DEPLACEMENT PAR GLISSER-DEPOSER
    // --------------------------------------------------

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        String viewTitle = event.getView().getTitle();
        if (GAME_GUI_TITLE.equals(viewTitle) || CONFIG_GUI_TITLE.equals(viewTitle)) {
            event.setCancelled(true);
            return;
        }

        if (isProtectedItem(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();

        for (int rawSlot : event.getRawSlots()) {
            // Seuls les slots de l'inventaire du joueur sont concernes.
            if (rawSlot >= topSize) {
                int slot = event.getView().convertSlot(rawSlot);

                if (slot == 0 || slot == 8) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    // --------------------------------------------------
    // INTERDIRE DE JETER LES OBJETS
    // --------------------------------------------------

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isProtectedItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }
}