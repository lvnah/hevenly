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
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
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
    private static final String CONFIG_GUI_TITLE = ChatColor.DARK_GRAY + "Configuration";
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String[] STAFF_RANKS = {"owner", "mod", "helper"};
    private static final String QUEUE_LEAVE_NAME = ChatColor.RED + "Quitter la file";
    private static final String WEBSITE = "hevenly.games";
    // Couleurs qui defilent sur hevenly.games dans le scoreboard.
    private static final ChatColor[] WAVE_COLORS = {
        ChatColor.RED, ChatColor.GOLD, ChatColor.YELLOW, ChatColor.GREEN,
        ChatColor.AQUA, ChatColor.BLUE, ChatColor.LIGHT_PURPLE
    };
    private int sidebarTick = 0;
    private static final String STRIKE_LINE = ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "---------------------------";
    // 16 caracteres (codes couleur compris) en prefixe + 16 en suffixe : limite 1.8 par equipe.
    private static final String SIDEBAR_SEPARATOR = ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "------------";
    // Pseudos en minuscules -> true si la boussole et le name tag sont inverses.
    private final Map<String, Boolean> swappedLayout =
            new ConcurrentHashMap<String, Boolean>();
    private BedFightManager bedFightManager;

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
        getConfig().addDefault("bedfight.enabled", false);
        // false = file d'attente locale (meme serveur), true = connexion BungeeCord.
        getConfig().addDefault("bedfight.bungee", false);
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
        if (getCommand("clearchat") != null) {
            getCommand("clearchat").setExecutor(this);
        }
        if (getCommand("help") != null) {
            getCommand("help").setExecutor(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        bedFightManager = new BedFightManager(this);
        bedFightManager.register();

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

        getLogger().info("Hevenly est actiff !");
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

        if (commandName.equals("clearchat")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            Player player = (Player) sender;

            if (!isStaff(getRank(player))) {
                player.sendMessage(ChatColor.RED
                        + "Tu n'as pas la permission !");
                return true;
            }

            for (Player online : Bukkit.getOnlinePlayers()) {
                clearChat(online);
            }
            Bukkit.broadcastMessage(ChatColor.GRAY + "Le chat a ete efface par "
                    + player.getName() + ChatColor.GRAY + ".");
            return true;
        }

        if (commandName.equals("help")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            sendHelp((Player) sender);
            return true;
        }

        return false;
    }

    // --------------------------------------------------
    // COMMANDES UTILITAIRES : /help, /clearchat ET MESSAGE D'ACCUEIL
    // --------------------------------------------------

    private boolean isStaff(String rank) {
        for (String staffRank : STAFF_RANKS) {
            if (staffRank.equalsIgnoreCase(rank)) return true;
        }
        return false;
    }

    private void clearChat(Player player) {
        for (int i = 0; i < 100; i++) {
            player.sendMessage("");
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(STRIKE_LINE);
        player.sendMessage(ChatColor.DARK_RED + "Hevenly " + ChatColor.GRAY + "» Commandes");
        player.sendMessage(ChatColor.YELLOW + "/spawn " + ChatColor.GRAY + "- Retour au lobby");
        player.sendMessage(ChatColor.YELLOW + "/invite <pseudo> " + ChatColor.GRAY + "- Inviter un joueur en 2v2 BedFight");
        player.sendMessage(ChatColor.YELLOW + "/invite accept " + ChatColor.GRAY + "- Accepter une invitation 2v2");
        player.sendMessage(ChatColor.YELLOW + "/bfleave " + ChatColor.GRAY + "- Quitter la file BedFight");
        player.sendMessage(ChatColor.YELLOW + "/elo [pseudo] " + ChatColor.GRAY + "- Voir ton elo classe");
        player.sendMessage(ChatColor.YELLOW + "/help " + ChatColor.GRAY + "- Afficher cette aide");
        if (isStaff(getRank(player))) {
            player.sendMessage(ChatColor.YELLOW + "/clearchat " + ChatColor.GRAY + "- Vider le chat");
        }
        if (isArenaStaff(player)) {
            player.sendMessage(ChatColor.YELLOW + "/bfarenas " + ChatColor.GRAY + "- Voir les arenes");
            player.sendMessage(ChatColor.YELLOW + "/bfarena <nom> " + ChatColor.GRAY + "- Aller dans une arene");
        }
        if (player.hasPermission("hevenly.setrank")) {
            player.sendMessage(ChatColor.YELLOW + "/setrank <joueur> <grade> " + ChatColor.GRAY + "- Changer un grade");
        }
        if (player.hasPermission("hevenly.setspawn")) {
            player.sendMessage(ChatColor.YELLOW + "/setspawn " + ChatColor.GRAY + "- Definir le spawn du lobby");
        }
        player.sendMessage(STRIKE_LINE);
    }

    private void sendWelcome(Player player) {
        String[] lines = {
            "&7&m---------------------------",
            "&7Bienvenue sur &4Hevenly &c" + player.getName(),
            "",
            "&7Fais /help pour voir la liste des commandes",
            "&7Site : hevenly.games",
            "&7&m---------------------------"
        };
        for (String line : lines) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
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

        // Prefixe [RANK] puis un espace ; le pseudo prend la couleur du grade.
        // En 1.8, le prefixe d'une equipe est limite a 16 caracteres (codes couleur inclus).
        String teamPrefix;
        if (nametagEnabled.getOrDefault(getPlayerKey(target), true)) {
            String label = getRankLabel(rank);
            if ("default".equalsIgnoreCase(rank)) label = "DEF";
            if ("premium".equalsIgnoreCase(rank)) label = "PREM";
            teamPrefix = ChatColor.GRAY + "[" + getRankColor(rank) + label
                    + ChatColor.GRAY + "] " + getRankColor(rank);
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
        return bedFightManager.getElo(player.getUniqueId());
    }

    // Une lettre change de couleur a chaque tick : le texte semble defiler.
    private String animatedWebsite(int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < to; i++) {
            text.append(WAVE_COLORS[(i + sidebarTick) % WAVE_COLORS.length]).append(WEBSITE.charAt(i));
        }
        return text.toString();
    }

    private void updateScoreboards() {
        sidebarTick++;
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
            setSidebarTeamLine(board, objective, "hv_sb1", "§1", SIDEBAR_SEPARATOR, SIDEBAR_SEPARATOR, 6);
            setSidebarTeamLine(board, objective, "hv_sb2", "§2", "§fJoueur en ", "§fligne: §a" + onlineCount, 5);
            // Chaque suffixe reçoit sa propre couleur pour éviter les couleurs héritées.
            setSidebarTeamLine(board, objective, "hv_sb3", "§3", "§fGrade: ", getRankColor(rank).toString() + rank, 4);
            setSidebarTeamLine(board, objective, "hv_sb4", "§4", "§fRanked: ", "§a" + getPlayerElo(player), 3);
            setSidebarTeamLine(board, objective, "hv_sb5", "§5", SIDEBAR_SEPARATOR, SIDEBAR_SEPARATOR, 2);
            // 8 lettres en prefixe et 5 en suffixe : chaque lettre compte pour 2 caracteres de couleur.
            setSidebarTeamLine(board, objective, "hv_sb6", "§6",
                    animatedWebsite(0, 8), animatedWebsite(8, WEBSITE.length()), 1);
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

    // Rose rouge (351:1) donnee en file d'attente pour la quitter.
    ItemStack createQueueLeaveItem() {
        ItemStack item = new ItemStack(Material.INK_SACK, 1, (short) 1);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(QUEUE_LEAVE_NAME);
        item.setItemMeta(meta);

        return item;
    }

    boolean isArenaStaff(Player player) {
        String rank = getRank(player);
        return "owner".equalsIgnoreCase(rank) || "mod".equalsIgnoreCase(rank);
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
                    && CONFIG_NAME.equals(name))
                || (item.getType() == Material.INK_SACK
                    && QUEUE_LEAVE_NAME.equals(name));
    }

    void giveSpecialItems(Player player) {
        PlayerInventory inventory = player.getInventory();

        // Efface les deux emplacements reserves avant de placer les objets selon la disposition.
        inventory.setItem(0, null);
        inventory.setItem(8, null);

        inventory.setItem(getCompassSlot(player), createGameItem());
        inventory.setItem(getNametagSlot(player), createConfigItem());

        player.updateInventory();
    }

    // Par defaut : boussole en slot 1 (index 0), name tag en slot 9 (index 8).
    private int getCompassSlot(Player player) {
        return isSwapped(player) ? 8 : 0;
    }

    private int getNametagSlot(Player player) {
        return isSwapped(player) ? 0 : 8;
    }

    private boolean isSwapped(Player player) {
        return swappedLayout.getOrDefault(getPlayerKey(player), false);
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
        inventory.setItem(22, createGuiItem(Material.DIAMOND_CHESTPLATE,
                ChatColor.AQUA + "BedFight 2v2 Unranked",
                ChatColor.GRAY + "Partie en equipe avec ton groupe", ChatColor.YELLOW + "Clique pour rejoindre"));
        inventory.setItem(13, createGuiItem(Material.BOOK,
                ChatColor.DARK_RED + "Hevenly BedFight",
                ChatColor.GRAY + "Choisis ton mode de jeu"));
        player.openInventory(inventory);
    }

    private void openNametagGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, CONFIG_GUI_TITLE);
        boolean enabled = nametagEnabled.getOrDefault(getPlayerKey(player), true);
        inventory.setItem(11, createGuiItem(enabled ? Material.NAME_TAG : Material.BARRIER,
                (enabled ? ChatColor.GREEN : ChatColor.RED) + "Prefixe du grade : "
                        + (enabled ? "ACTIVE" : "DESACTIVE"),
                ChatColor.GRAY + "Affiche le grade au-dessus de la tete et dans le TAB.",
                ChatColor.YELLOW + "Clique pour changer"));
        boolean swapped = isSwapped(player);
        inventory.setItem(15, createGuiItem(Material.CHEST,
                ChatColor.AQUA + "Disposition de l'inventaire",
                ChatColor.GRAY + "Boussole : " + (swapped ? "slot 9" : "slot 1"),
                ChatColor.GRAY + "Name tag : " + (swapped ? "slot 1" : "slot 9"),
                ChatColor.YELLOW + "Clique pour inverser"));
        inventory.setItem(22, createGuiItem(Material.ARROW,
                ChatColor.GRAY + "Fermer", ChatColor.DARK_GRAY + "Retour au jeu"));
        player.openInventory(inventory);
    }

    private void connectToServer(Player player, String mode) {
        // Meme serveur : le joueur entre directement dans la file BedFight.
        if (!getConfig().getBoolean("bedfight.bungee", false)) {
            player.closeInventory();
            if ("duo".equals(mode)) {
                bedFightManager.joinDuoQueue(player);
            } else if ("ranked".equals(mode)) {
                bedFightManager.joinRankedQueue(player);
            } else {
                bedFightManager.joinQueue(player);
            }
            return;
        }

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
        swappedLayout.clear();
        if (getConfig().isConfigurationSection("nametag.enabled")) {
            for (String name : getConfig().getConfigurationSection("nametag.enabled").getKeys(false)) {
                nametagEnabled.put(name.toLowerCase(Locale.ROOT),
                        getConfig().getBoolean("nametag.enabled." + name, true));
            }
        }
        if (getConfig().isConfigurationSection("layout.swapped")) {
            for (String name : getConfig().getConfigurationSection("layout.swapped").getKeys(false)) {
                swappedLayout.put(name.toLowerCase(Locale.ROOT),
                        getConfig().getBoolean("layout.swapped." + name, false));
            }
        }
    }

    private void saveNametagSettings() {
        getConfig().set("nametag.enabled", null);
        for (Map.Entry<String, Boolean> entry : nametagEnabled.entrySet()) {
            getConfig().set("nametag.enabled." + entry.getKey(), entry.getValue());
        }
        getConfig().set("layout.swapped", null);
        for (Map.Entry<String, Boolean> entry : swappedLayout.entrySet()) {
            getConfig().set("layout.swapped." + entry.getKey(), entry.getValue());
        }
        saveConfig();
    }

    // LOWEST : nos objets sont annules avant les autres plugins (dont WorldEdit) pour les ops aussi.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpecialItemUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return;

        String name = item.getItemMeta().getDisplayName();
        boolean isGame = item.getType() == Material.COMPASS && GAME_NAME.equals(name);
        boolean isConfig = item.getType() == Material.NAME_TAG && CONFIG_NAME.equals(name);
        boolean isLeave = item.getType() == Material.INK_SACK && QUEUE_LEAVE_NAME.equals(name);
        if (!isGame && !isConfig && !isLeave) return;

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!rightClick) return;

        if (isGame) {
            openGameGui(event.getPlayer());
        } else if (isLeave) {
            bedFightManager.leaveQueue(event.getPlayer());
        } else {
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
            } else if (event.getRawSlot() == 22) {
                connectToServer(player, "duo");
            }
        } else if (CONFIG_GUI_TITLE.equals(title)) {
            if (event.getRawSlot() == 11) {
                String key = getPlayerKey(player);
                boolean enabled = !nametagEnabled.getOrDefault(key, true);
                nametagEnabled.put(key, enabled);
                saveNametagSettings();
                refreshAllBoards();
                openNametagGui(player);
                player.sendMessage(ChatColor.GREEN + "Prefixe du grade "
                        + (enabled ? "active" : "desactive") + ".");
            } else if (event.getRawSlot() == 15) {
                if (bedFightManager.isInMatch(player)) {
                    player.sendMessage(ChatColor.RED + "Tu ne peux pas changer la disposition pendant une partie.");
                    return;
                }
                String key = getPlayerKey(player);
                boolean swapped = !isSwapped(player);
                swappedLayout.put(key, swapped);
                saveNametagSettings();
                giveSpecialItems(player);
                openNametagGui(player);
                player.sendMessage(ChatColor.GREEN + "Disposition : boussole en slot "
                        + (swapped ? "9" : "1") + ", name tag en slot " + (swapped ? "1" : "9") + ".");
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

        // Pas de message de connexion public : seul le message d'accueil est envoye.
        event.setJoinMessage(null);
        clearChat(player);
        sendWelcome(player);

        // Initialise le grade par defaut uniquement si aucun grade n'est sauvegarde.
        String key = getPlayerKey(player);
        if (!playerRanks.containsKey(key)) {
            playerRanks.put(key, "default");
            saveRanks();
        }

        // Le joueur arrive toujours au lobby ; il rejoint BedFight avec la boussole.
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
        event.setQuitMessage(null);

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
        // Pas d'ecran de mort : le joueur reapparait tout de suite.
        final Player dead = event.getEntity();
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                if (dead.isOnline() && dead.isDead()) {
                    dead.spigot().respawn();
                }
            }
        });

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
        if (bedFightManager != null && bedFightManager.isInMatch(event.getPlayer())) {
            return; // Le gestionnaire BedFight choisit le spawn de l'equipe.
        }

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

        // En partie, l'inventaire est libre : les slots reserves ne concernent que le lobby.
        if (bedFightManager.isInMatch(player)) return;

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

        if (bedFightManager.isInMatch((Player) event.getWhoClicked())) return;

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

    // --------------------------------------------------
    // FAIM : LA NOURRITURE NE DIMINUE JAMAIS
    // --------------------------------------------------

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isProtectedItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }
}
