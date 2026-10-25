package fr.hevenly;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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

    private static final String GAME_GUI_TITLE = ChatColor.RED + "Hevenly » BedFight";
    private static final String CONFIG_GUI_TITLE = ChatColor.DARK_GRAY + "Configuration";
    private static final String COLOR_GUI_TITLE = ChatColor.DARK_GRAY + "Couleur du pseudo";
    private static final String MAP_GUI_TITLE = ChatColor.DARK_GRAY + "Choisir la map";
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String[] STAFF_RANKS = {"owner", "mod", "helper"};
    private static final String QUEUE_LEAVE_NAME = ChatColor.RED + "Quitter la file";
    private static final String WEBSITE = "hevenly.games";
    private int shineTick = 0;
    private static final String STRIKE_LINE = ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "---------------------------";
    // 16 caracteres (codes couleur compris) en prefixe + 16 en suffixe : limite 1.8 par equipe.
    private static final String SIDEBAR_SEPARATOR = ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "------------";
    // Ops ayant active /build : seuls eux peuvent casser et poser hors match.
    private final Set<UUID> buildMode = new HashSet<UUID>();
    // Pseudos en minuscules -> disposition du kit BedFight (0 a KIT_LAYOUT_COUNT - 1).
    private final Map<String, Integer> kitLayouts =
            new ConcurrentHashMap<String, Integer>();
    // Pseudos en minuscules -> couleur d'equipe pendant un match (pas de grade au-dessus de la tete).
    private final Map<String, ChatColor> matchColors =
            new ConcurrentHashMap<String, ChatColor>();
    // Pseudos en minuscules -> couleur choisie retenue pour le match en cours.
    private final Map<String, PlayerColor> matchChoices =
            new ConcurrentHashMap<String, PlayerColor>();
    // Pseudos en minuscules -> couleur choisie (grades plus, premium, mod, owner).
    private final Map<String, PlayerColor> colorChoices =
            new ConcurrentHashMap<String, PlayerColor>();
    static final int KIT_LAYOUT_COUNT = 3;
    static final String PREFIX = ChatColor.GRAY + "[" + ChatColor.RED + "Hevenly"
            + ChatColor.GRAY + "] " + ChatColor.WHITE;
    private BedFightManager bedFightManager;
    private LeaderboardHolo leaderboardHolo;

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
        if (getCommand("build") != null) {
            getCommand("build").setExecutor(this);
        }
        if (getCommand("lbholo") != null) {
            getCommand("lbholo").setExecutor(this);
        }
        if (getCommand("gm") != null) {
            getCommand("gm").setExecutor(this);
        }
        if (getCommand("season") != null) {
            getCommand("season").setExecutor(this);
        }
        // Grades temporaires expires : verifies toutes les 10 minutes.
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                checkTemporaryRanks();
            }
        }, 12000L, 12000L);
        if (getCommand("mod") != null) {
            getCommand("mod").setExecutor(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        bedFightManager = new BedFightManager(this);
        bedFightManager.register();

        // Hologramme du top 10 : recree au demarrage, puis rafraichi chaque minute.
        leaderboardHolo = new LeaderboardHolo(this, bedFightManager);
        leaderboardHolo.restore();
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                leaderboardHolo.restore();
            }
        }, 1200L, 1200L);

        // Defilement du reflet sur hevenly.games, toutes les 2 ticks.
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                updateWebsiteLines();
            }
        }, 0L, 2L);

        // Met a jour le scoreboard une fois par seconde.
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                updateScoreboards();
            }
        }, 0L, 4L);

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

            player.sendMessage(PREFIX + "Le spawn a ete defini avec succes !");
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
            player.sendMessage(PREFIX + "Tu as ete teleporte au spawn !");
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
                    + getRankLabel(newRank);

            sender.sendMessage(PREFIX
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
            Bukkit.broadcastMessage(PREFIX + "Le chat a ete efface par "
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

        if (commandName.equals("gm")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            Player player = (Player) sender;
            if (args.length != 1 || !(args[0].equals("0") || args[0].equals("1"))) {
                player.sendMessage(ChatColor.RED + "Utilisation : /gm <0|1>");
                return true;
            }
            if (args[0].equals("1")) {
                if (!player.isOp()) {
                    player.sendMessage(ChatColor.RED + "Le mode creatif est reserve aux op.");
                    return true;
                }
                player.setGameMode(GameMode.CREATIVE);
                player.sendMessage(PREFIX + "Mode de jeu : creatif.");
            } else {
                player.setGameMode(GameMode.SURVIVAL);
                player.sendMessage(PREFIX + "Mode de jeu : survie.");
            }
            return true;
        }

        if (commandName.equals("mod")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            Player player = (Player) sender;
            if (!isArenaStaff(player)) {
                player.sendMessage(ChatColor.RED + "Cette commande est reservee aux mod et owner.");
                return true;
            }

            player.sendMessage(PREFIX + "Outils mod :");
            player.sendMessage(ChatColor.YELLOW + "/cc " + ChatColor.GRAY + "- Vider le chat");
            player.sendMessage(ChatColor.YELLOW + "/bf arenes " + ChatColor.GRAY + "- Voir les arenes");
            player.sendMessage(ChatColor.YELLOW + "/bf tp <nom> " + ChatColor.GRAY + "- Aller dans une arene");
            return true;
        }

        if (commandName.equals("season")) {
            if (!(sender instanceof Player) || !((Player) sender).isOp()) {
                sender.sendMessage(ChatColor.RED + "Cette commande est reservee aux op.");
                return true;
            }
            if (args.length != 1 || !args[0].equalsIgnoreCase("reward")) {
                sender.sendMessage(ChatColor.RED + "Utilisation : /season reward");
                return true;
            }
            rewardSeason(sender);
            return true;
        }

        if (commandName.equals("lbholo")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            Player player = (Player) sender;
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Cette commande est reservee aux op.");
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
                leaderboardHolo.remove();
                player.sendMessage(PREFIX + "Tous les hologrammes du top 10 ont ete supprimes.");
                return true;
            }

            leaderboardHolo.place(player.getLocation());
            player.sendMessage(PREFIX + "Hologramme du top 10 place ici.");
            return true;
        }

        if (commandName.equals("build")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            Player player = (Player) sender;
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Cette commande est reservee aux op.");
                return true;
            }

            UUID id = player.getUniqueId();
            if (buildMode.remove(id)) {
                player.sendMessage(PREFIX + "Mode construction desactive.");
            } else {
                buildMode.add(id);
                player.sendMessage(PREFIX + "Mode construction active : tu peux casser et poser hors match.");
            }
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
        player.sendMessage(PREFIX + "Liste des commandes :");
        player.sendMessage(ChatColor.YELLOW + "/spawn " + ChatColor.GRAY + "- Retour au lobby");
        player.sendMessage(ChatColor.YELLOW + "/invite <pseudo> " + ChatColor.GRAY + "- Inviter un joueur en 2v2 BedFight");
        player.sendMessage(ChatColor.YELLOW + "/invite accept " + ChatColor.GRAY + "- Accepter une invitation 2v2");
        player.sendMessage(ChatColor.YELLOW + "/invite disband " + ChatColor.GRAY + "- Dissoudre ton groupe (createur)");
        player.sendMessage(ChatColor.YELLOW + "/invite leave " + ChatColor.GRAY + "- Quitter ton groupe");
        player.sendMessage(ChatColor.YELLOW + "/bfleave " + ChatColor.GRAY + "- Quitter la file BedFight");
        player.sendMessage(ChatColor.YELLOW + "/elo [pseudo] " + ChatColor.GRAY + "- Voir ton elo classe");
        player.sendMessage(ChatColor.YELLOW + "/help " + ChatColor.GRAY + "- Afficher cette aide");
        if (isStaff(getRank(player))) {
            player.sendMessage(ChatColor.YELLOW + "/clearchat " + ChatColor.GRAY + "- Vider le chat");
        }
        if (isArenaStaff(player)) {
            player.sendMessage(ChatColor.YELLOW + "/bf arenes " + ChatColor.GRAY + "- Voir les arenes");
            player.sendMessage(ChatColor.YELLOW + "/bf tp <nom> " + ChatColor.GRAY + "- Aller dans une arene");
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
            default: return "BASIQUE";
        }
    }

    // Format demandé : §7[<couleur du grade>GRADE§7]
    private String getRankPrefix(String rank) {
        return getRankPrefix(rank, getRankColor(rank));
    }

    // Le crochet prend la couleur donnee (couleur choisie par le joueur, si elle existe).
    private String getRankPrefix(String rank, ChatColor color) {
        if ("default".equalsIgnoreCase(rank)) return "";
        return ChatColor.GRAY + "[" + color + getRankLabel(rank) + ChatColor.GRAY + "] ";
    }

    private ChatColor getRankColor(String rank) {
        switch (rank.toLowerCase(Locale.ROOT)) {
            case "owner":
                return ChatColor.RED;
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

        // En match : couleur choisie retenue, sinon couleur de l'equipe. Hors match : couleur du grade.
        PlayerColor chosen = getColorChoice(player);
        ChatColor teamColor = matchColors.get(getPlayerKey(player));
        ChatColor shown = chosen != null ? chosen.chat : teamColor;
        ChatColor nameColor = shown != null ? shown : getRankColor(getRank(player));

        // Les joueurs ne peuvent pas utiliser de codes couleur dans leur message.
        String message = ChatColor.stripColor(event.getMessage());
        event.setMessage(message);

        // Echappe les pourcentages pour eviter les erreurs de formatage.
        String safeName = player.getName().replace("%", "%%");

        // Couleur choisie : crochet, pseudo et message ; sinon couleurs du grade.
        ChatColor messageColor = shown != null ? shown : ChatColor.WHITE;
        String rankPrefix = shown != null
                ? getRankPrefix(getRank(player), shown)
                : getRankPrefix(getRank(player));
        event.setFormat(
                ChatColor.RESET + rankPrefix + nameColor + safeName
                + ChatColor.GRAY + " » "
                + messageColor + "%2$s"
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
        ChatColor matchColor = matchColors.get(getPlayerKey(target));
        PlayerColor chosen = getColorChoice(target);
        ChatColor nameColor = chosen != null ? chosen.chat : null;
        if (matchColor != null) {
            // En match : pas de grade, le pseudo a la couleur choisie ou celle de l'equipe.
            teamPrefix = (nameColor != null ? nameColor : matchColor).toString();
        } else if ("default".equalsIgnoreCase(rank)) {
            // Grade default : pas de prefixe, pseudo blanc.
            teamPrefix = (nameColor != null ? nameColor : getRankColor(rank)).toString();
        } else {
            String label = getRankLabel(rank);
            ChatColor shownColor = nameColor != null ? nameColor : getRankColor(rank);
            teamPrefix = ChatColor.GRAY + "[" + shownColor + label
                    + ChatColor.GRAY + "] " + shownColor;
            // 16 caracteres max par prefixe : si le grade est long (PREMIUM), crochets dans la couleur du grade.
            if (teamPrefix.length() > 16) {
                teamPrefix = shownColor + "[" + label + "] " + shownColor;
            }
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

    // Reflet blanc qui glisse sur hevenly.games : mise a jour toutes les 2 ticks pour un defilement fluide.
    private void updateWebsiteLines() {
        shineTick++;
        int shine = shineTick % (WEBSITE.length() + 4) - 2;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = getScoreboard(player);
            Objective objective = board.getObjective("hevenly");
            if (objective == null) continue;
            // 8 lettres en prefixe et 5 en suffixe : chaque lettre compte pour 2 caracteres de couleur.
            String[] parts = websiteParts(shine);
            setSidebarTeamLine(board, objective, "hv_sb6", "§6", parts[0], parts[1], 1);
        }
    }

    // Lettre au centre du reflet en blanc, voisines en gris, le reste en rouge clair.
    // Un code couleur n'est ecrit que quand la couleur change, pour tenir dans les 16 caracteres
    // de prefixe et de suffixe. Le suffixe repart toujours avec son propre code.
    private String[] websiteParts(int shine) {
        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();
        ChatColor last = null;
        int i = 0;
        for (; i < WEBSITE.length(); i++) {
            ChatColor color = websiteColor(i, shine);
            String piece = (color != last ? color.toString() : "") + WEBSITE.charAt(i);
            if (prefix.length() + piece.length() > 16) break;
            prefix.append(piece);
            last = color;
        }
        last = null;
        for (; i < WEBSITE.length(); i++) {
            ChatColor color = websiteColor(i, shine);
            suffix.append(color != last ? color.toString() : "").append(WEBSITE.charAt(i));
            last = color;
        }
        return new String[] {prefix.toString(), suffix.toString()};
    }

    private ChatColor websiteColor(int index, int shine) {
        int distance = Math.abs(index - shine);
        return distance == 0 ? ChatColor.WHITE : distance == 1 ? ChatColor.GRAY : ChatColor.RED;
    }

    private void updateScoreboards() {
        int onlineCount = Bukkit.getOnlinePlayers().size();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = getScoreboard(player);
            Objective objective = board.getObjective("hevenly");

            if (objective == null) {
                objective = board.registerNewObjective("hevenly", "dummy");
                objective.setDisplayName(ChatColor.RED + "Hevenly");
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            } else {
                if (objective.getDisplaySlot() != DisplaySlot.SIDEBAR) {
                    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                }
                if (!objective.getDisplayName().equals(ChatColor.RED + "Hevenly")) {
                    objective.setDisplayName(ChatColor.RED + "Hevenly");
                }
            }

            // En file d'attente : seulement le temps d'attente.
            long waiting = bedFightManager.queueSeconds(player);
            if (waiting >= 0) {
                // Separateurs, site et lignes de file gardes ; les lignes du lobby (Couleur, et Elo hors file classee) disparaissent.
                boolean ranked = bedFightManager.isRankedQueued(player);
                removeSidebarLine(board, "hv_sb7", "§0");
                if (!ranked) removeSidebarLine(board, "hv_sb4", "§4");
                String map = bedFightManager.getPreferredArena(player);
                setSidebarTeamLine(board, objective, "hv_sb1", "§1", SIDEBAR_SEPARATOR, SIDEBAR_SEPARATOR, 7);
                setSidebarTeamLine(board, objective, "hv_sb2", "§2", "§fFile d'attente", " §7» §a" + waiting + " sec", 6);
                setSidebarTeamLine(board, objective, "hv_sb3", "§3", "§fMap", " §7» §a"
                        + BedFightManager.shortName(map == null ? "Aléatoire" : map, 9), 5);
                if (ranked) {
                    setSidebarTeamLine(board, objective, "hv_sb4", "§4", "§fElo", " §7» §a" + getPlayerElo(player), 4);
                }
                setSidebarTeamLine(board, objective, "hv_sb5", "§5", SIDEBAR_SEPARATOR, SIDEBAR_SEPARATOR, 2);
                continue;
            }
            removeSidebarLine(board, "hv_sbq", "§9");

            // En match : adversaire, duree et lit adverse, a la place des infos du lobby.
            String[] match = bedFightManager.matchLines(player);
            if (match != null) {
                setSidebarTeamLine(board, objective, "hv_sb1", "§1", SIDEBAR_SEPARATOR, SIDEBAR_SEPARATOR, 7);
                setSidebarTeamLine(board, objective, "hv_sb2", "§2", "§fAdversaire ", "§7» §c" + match[0], 6);
                setSidebarTeamLine(board, objective, "hv_sb3", "§3", "§fDurée ", "§7» §a" + match[1], 5);
                setSidebarTeamLine(board, objective, "hv_sb4", "§4", "§fLit détruit ",
                        "§7» " + ("Oui".equals(match[2]) ? "§aOui" : "§cNon"), 4);
                setSidebarTeamLine(board, objective, "hv_sb5", "§5", SIDEBAR_SEPARATOR, SIDEBAR_SEPARATOR, 2);
                removeSidebarLine(board, "hv_sb7", "§0");
                continue;
            }

            String rank = getRankLabel(getRank(player));
            setSidebarTeamLine(board, objective, "hv_sb1", "§1", SIDEBAR_SEPARATOR, SIDEBAR_SEPARATOR, 7);
            setSidebarTeamLine(board, objective, "hv_sb2", "§2", "§fEn ligne ", "§7» §a" + onlineCount, 6);
            // Chaque suffixe reçoit sa propre couleur pour éviter les couleurs héritées.
            setSidebarTeamLine(board, objective, "hv_sb3", "§3", "§fGrade ", "§7» " + getRankColor(rank) + rank, 5);
            // Couleur par defaut, ou la couleur choisie et enregistree dans la config.
            PlayerColor stored = getStoredColor(player);
            setSidebarTeamLine(board, objective, "hv_sb7", "§0", "§fCouleur ",
                    "§7» " + (stored == null ? "§fDefaut" : stored.chat + stored.label), 4);
            setSidebarTeamLine(board, objective, "hv_sb4", "§4", "§fElo ", "§7» §a" + getPlayerElo(player), 3);
            setSidebarTeamLine(board, objective, "hv_sb5", "§5", SIDEBAR_SEPARATOR, SIDEBAR_SEPARATOR, 2);
        }
    }

    // Retire une ligne du lobby quand elle n'a plus lieu d'etre (ex. pendant un match).
    private void removeSidebarLine(Scoreboard board, String teamName, String entry) {
        Team team = board.getTeam(teamName);
        if (team != null) team.unregister();
        board.resetScores(entry);
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

        // Boussole en slot 1, name tag en slot 9.
        inventory.setItem(0, createGameItem());
        inventory.setItem(8, createConfigItem());

        player.updateInventory();
    }

    // Disposition du kit BedFight choisie dans le menu Config.
    int getKitLayout(Player player) {
        return kitLayouts.getOrDefault(getPlayerKey(player), 0);
    }

    // Grade plus : 3 couleurs. Premium, mod et owner : toutes les couleurs.
    static List<PlayerColor> allowedColors(String rank) {
        List<PlayerColor> all = Arrays.asList(PlayerColor.values());
        if ("plus".equalsIgnoreCase(rank)) return all.subList(0, 3);
        if ("premium".equalsIgnoreCase(rank) || "mod".equalsIgnoreCase(rank) || "owner".equalsIgnoreCase(rank)) {
            return all;
        }
        return Collections.emptyList();
    }

    static boolean canChooseColor(String rank) {
        return !allowedColors(rank).isEmpty();
    }

    // Couleur choisie, telle qu'enregistree (grade permettant).
    PlayerColor getStoredColor(Player player) {
        PlayerColor stored = colorChoices.get(getPlayerKey(player));
        if (stored == null || !allowedColors(getRank(player)).contains(stored)) return null;
        return stored;
    }

    // Couleur effectivement utilisee : seulement pendant un match, sinon le grade prime.
    PlayerColor getColorChoice(Player player) {
        return matchChoices.get(getPlayerKey(player));
    }

    private void setColorChoice(Player player, PlayerColor color) {
        String key = getPlayerKey(player);
        if (color == null) {
            colorChoices.remove(key);
        } else {
            colorChoices.put(key, color);
        }
        saveNametagSettings();
        refreshAllBoards();
    }

    private void openColorGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, COLOR_GUI_TITLE);
        PlayerColor current = getStoredColor(player);
        List<PlayerColor> colors = allowedColors(getRank(player));
        for (int i = 0; i < colors.size(); i++) {
            PlayerColor color = colors.get(i);
            ItemStack item = new ItemStack(Material.WOOL, 1, (short) 0, color.dye.getWoolData());
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color.chat + color.label
                    + (color == current ? ChatColor.GRAY + " (choisie)" : ""));
            item.setItemMeta(meta);
            inventory.setItem(10 + i, item);
        }
        inventory.setItem(22, createGuiItem(Material.ARROW,
                ChatColor.GRAY + "Retour", ChatColor.DARK_GRAY + "Retour a la configuration"));
        inventory.setItem(23, createGuiItem(Material.BARRIER,
                ChatColor.RED + "Couleur par defaut", ChatColor.GRAY + "Retire ta couleur personnalisee"));
        player.openInventory(inventory);
    }

    // Pendant un match : couleur d'equipe par defaut, et couleur choisie si elle est retenue.
    // null = fin de match, le grade revient.
    void setMatchColor(Player player, ChatColor teamColor, PlayerColor chosen) {
        String key = getPlayerKey(player);
        if (teamColor == null) {
            matchColors.remove(key);
            matchChoices.remove(key);
        } else {
            matchColors.put(key, teamColor);
            if (chosen == null) {
                matchChoices.remove(key);
            } else {
                matchChoices.put(key, chosen);
            }
        }
        refreshAllBoards();
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

    // Grades premium, mod et owner : choix de la map pour la file.
    private boolean canChooseMap(Player player) {
        String rank = getRank(player);
        return "premium".equalsIgnoreCase(rank) || "mod".equalsIgnoreCase(rank) || "owner".equalsIgnoreCase(rank);
    }

    private void openMapGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, MAP_GUI_TITLE);
        List<String> arenas = bedFightManager.completeArenaNames();
        String current = bedFightManager.getPreferredArena(player);
        for (int i = 0; i < Math.min(arenas.size(), 12); i++) {
            String name = arenas.get(i);
            inventory.setItem(10 + i, createGuiItem(Material.MAP, ChatColor.AQUA + name,
                    name.equals(current) ? ChatColor.GREEN + "Choisie" : ChatColor.GRAY + "Clique pour choisir"));
        }
        inventory.setItem(22, createGuiItem(Material.EYE_OF_ENDER, ChatColor.YELLOW + "Aleatoire",
                ChatColor.GRAY + "Pas de map choisie"));
        player.openInventory(inventory);
    }

    private void openGameGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, GAME_GUI_TITLE);
        inventory.setItem(11, createGuiItem(Material.IRON_SWORD,
                ChatColor.GREEN + "BedFight 1v1 Unranked",
                ChatColor.GRAY + "Partie sans Elo", ChatColor.YELLOW + "Clique pour rejoindre"));
        inventory.setItem(15, createGuiItem(Material.DIAMOND_SWORD,
                ChatColor.RED + "BedFight 1v1 Ranked",
                ChatColor.GRAY + "Partie classee", ChatColor.YELLOW + "Clique pour rejoindre"));
        boolean canMap = canChooseMap(player);
        inventory.setItem(4, createGuiItem(Material.MAP,
                ChatColor.AQUA + "Choisir la map",
                canMap ? ChatColor.GRAY + "Tu tomberas sur la map choisie si elle est libre"
                        : ChatColor.RED + "Reserve aux grades Premium, Mod et Owner",
                canMap ? ChatColor.YELLOW + "Clique pour choisir" : ChatColor.GRAY + "Grade requis"));
        inventory.setItem(22, createGuiItem(Material.DIAMOND_CHESTPLATE,
                ChatColor.AQUA + "BedFight 2v2 Unranked",
                ChatColor.GRAY + "Partie en equipe avec ton groupe", ChatColor.YELLOW + "Clique pour rejoindre"));
        inventory.setItem(13, createGuiItem(Material.BOOK,
                ChatColor.RED + "Hevenly BedFight",
                ChatColor.GRAY + "Choisis ton mode de jeu"));
        player.openInventory(inventory);
    }

    private void openNametagGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, CONFIG_GUI_TITLE);
        PlayerColor chosen = getStoredColor(player);
        inventory.setItem(13, createGuiItem(Material.WOOL,
                ChatColor.AQUA + "Couleur du pseudo",
                ChatColor.GRAY + "Pseudo, laine et armure : " + (chosen == null ? "par defaut" : chosen.label),
                canChooseColor(getRank(player))
                        ? ChatColor.YELLOW + "Clique pour changer"
                        : ChatColor.RED + "Reserve aux grades Plus, Premium, Mod et Owner"));
        int[] slots = BedFightManager.kitSlots(getKitLayout(player));
        inventory.setItem(15, createGuiItem(Material.CHEST,
                ChatColor.AQUA + "Disposition du kit BedFight",
                ChatColor.GRAY + "Disposition " + (getKitLayout(player) + 1) + " / " + KIT_LAYOUT_COUNT,
                ChatColor.GRAY + "Epee : slot " + (slots[0] + 1),
                ChatColor.GRAY + "Laine : slot " + (slots[1] + 1),
                ChatColor.GRAY + "Cisaille : slot " + (slots[2] + 1),
                ChatColor.GRAY + "Hache : slot " + (slots[3] + 1),
                ChatColor.YELLOW + "Clique pour changer"));
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
        kitLayouts.clear();
        if (getConfig().isConfigurationSection("nametag.enabled")) {
            for (String name : getConfig().getConfigurationSection("nametag.enabled").getKeys(false)) {
                nametagEnabled.put(name.toLowerCase(Locale.ROOT),
                        getConfig().getBoolean("nametag.enabled." + name, true));
            }
        }
        colorChoices.clear();
        if (getConfig().isConfigurationSection("colors")) {
            for (String name : getConfig().getConfigurationSection("colors").getKeys(false)) {
                try {
                    colorChoices.put(name.toLowerCase(Locale.ROOT),
                            PlayerColor.valueOf(getConfig().getString("colors." + name, "")));
                } catch (IllegalArgumentException ignored) {
                    // Couleur inconnue dans la config : on l'ignore.
                }
            }
        }
        if (getConfig().isConfigurationSection("layout.kit")) {
            for (String name : getConfig().getConfigurationSection("layout.kit").getKeys(false)) {
                kitLayouts.put(name.toLowerCase(Locale.ROOT),
                        getConfig().getInt("layout.kit." + name, 0));
            }
        }
    }

    private void saveNametagSettings() {
        getConfig().set("nametag.enabled", null);
        for (Map.Entry<String, Boolean> entry : nametagEnabled.entrySet()) {
            getConfig().set("nametag.enabled." + entry.getKey(), entry.getValue());
        }
        getConfig().set("colors", null);
        for (Map.Entry<String, PlayerColor> entry : colorChoices.entrySet()) {
            getConfig().set("colors." + entry.getKey(), entry.getValue().name());
        }
        getConfig().set("layout.kit", null);
        for (Map.Entry<String, Integer> entry : kitLayouts.entrySet()) {
            getConfig().set("layout.kit." + entry.getKey(), entry.getValue());
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

    // WorldEdit prend une hache en bois comme outil : en match, seule la hache du kit est utilisable.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldEditAxe(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.WOOD_AXE) return;
        if (!bedFightManager.isInMatch(event.getPlayer())) return;
        if (item.hasItemMeta() && BedFightManager.MATCH_AXE_NAME.equals(item.getItemMeta().getDisplayName())) return;

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.getPlayer().sendMessage(ChatColor.RED + "Tu ne peux pas utiliser cette hache en partie.");
    }

    @EventHandler
    public void onHevenlyGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        if (!GAME_GUI_TITLE.equals(title) && !CONFIG_GUI_TITLE.equals(title)
                && !COLOR_GUI_TITLE.equals(title) && !MAP_GUI_TITLE.equals(title)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        Player player = (Player) event.getWhoClicked();
        if (GAME_GUI_TITLE.equals(title) && event.getRawSlot() == 4) {
            if (!canChooseMap(player)) {
                player.sendMessage(PREFIX + "Cette option est reservee aux grades Premium, Mod et Owner.");
                return;
            }
            openMapGui(player);
            return;
        }
        if (MAP_GUI_TITLE.equals(title)) {
            int slot = event.getRawSlot();
            List<String> arenas = bedFightManager.completeArenaNames();
            if (slot >= 10 && slot < 10 + Math.min(arenas.size(), 12)) {
                bedFightManager.setPreferredArena(player, arenas.get(slot - 10));
                player.closeInventory();
            } else if (slot == 22) {
                bedFightManager.setPreferredArena(player, null);
                player.closeInventory();
            }
            return;
        }
        if (COLOR_GUI_TITLE.equals(title)) {
            int slot = event.getRawSlot();
            List<PlayerColor> colors = allowedColors(getRank(player));
            if (slot >= 10 && slot < 10 + colors.size()) {
                PlayerColor picked = colors.get(slot - 10);
                setColorChoice(player, picked);
                player.sendMessage(PREFIX + "Couleur choisie : " + picked.chat + picked.label + ChatColor.WHITE + ".");
                openColorGui(player);
            } else if (slot == 23) {
                setColorChoice(player, null);
                player.sendMessage(PREFIX + "Couleur par defaut restauree.");
                openColorGui(player);
            } else if (slot == 22) {
                openNametagGui(player);
            }
            return;
        }
        if (GAME_GUI_TITLE.equals(title)) {
            if (event.getRawSlot() == 11) {
                connectToServer(player, "unranked");
            } else if (event.getRawSlot() == 15) {
                connectToServer(player, "ranked");
            } else if (event.getRawSlot() == 22) {
                connectToServer(player, "duo");
            }
        } else if (CONFIG_GUI_TITLE.equals(title)) {
            if (event.getRawSlot() == 13) {
                if (!canChooseColor(getRank(player))) {
                    player.sendMessage(PREFIX + "Cette option est reservee aux grades Plus, Premium, Mod et Owner.");
                    return;
                }
                openColorGui(player);
            } else if (event.getRawSlot() == 15) {
                if (bedFightManager.isInMatch(player)) {
                    player.sendMessage(ChatColor.RED + "Tu ne peux pas changer la disposition pendant une partie.");
                    return;
                }
                int next = (getKitLayout(player) + 1) % KIT_LAYOUT_COUNT;
                kitLayouts.put(getPlayerKey(player), next);
                saveNametagSettings();
                openNametagGui(player);
                player.sendMessage(PREFIX + "Disposition du kit BedFight : " + (next + 1) + " / " + KIT_LAYOUT_COUNT + ".");
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

        checkTemporaryRanks();

        // Pas de message de connexion public : seul le message d'accueil est envoye.
        event.setJoinMessage(null);
        clearChat(player);
        sendWelcome(player);

        // Le lobby est toujours en survie.
        if (!player.isOp()) {
            player.setGameMode(GameMode.SURVIVAL);
        }

        // Efface ce qui reste d'une partie deconnectee : le lobby donne ses propres objets.
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);

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
        buildMode.remove(event.getPlayer().getUniqueId());
        matchColors.remove(getPlayerKey(event.getPlayer()));
        matchChoices.remove(getPlayerKey(event.getPlayer()));

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

        // En partie, l'inventaire est libre sauf l'armure en cuir, qui reste equipee.
        if (bedFightManager.isInMatch(player)) {
            if (event.getSlotType() == SlotType.ARMOR) event.setCancelled(true);
            return;
        }

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

        if (bedFightManager.isInMatch((Player) event.getWhoClicked())) {
            // Armure = slots 36 a 39 de l'inventaire joueur.
            int topSize = event.getView().getTopInventory().getSize();
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < topSize) continue;
                int slot = event.getView().convertSlot(rawSlot);
                if (slot >= 36 && slot <= 39) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        String viewTitle = event.getView().getTitle();
        if (GAME_GUI_TITLE.equals(viewTitle) || CONFIG_GUI_TITLE.equals(viewTitle)
                || COLOR_GUI_TITLE.equals(viewTitle) || MAP_GUI_TITLE.equals(viewTitle)) {
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

    // --------------------------------------------------
    // CONSTRUCTION : HORS MATCH, SEULEMENT AVEC /build POUR LES OP
    // Les matchs gerent leurs propres regles dans BedFightManager.
    // --------------------------------------------------

    private boolean canBuildOutsideMatch(Player player) {
        return player.isOp() && buildMode.contains(player.getUniqueId());
    }

    @EventHandler
    public void onBuildBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (bedFightManager.isInMatch(player) || canBuildOutsideMatch(player)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onBuildPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (bedFightManager.isInMatch(player) || canBuildOutsideMatch(player)) return;
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "Tu ne peux pas poser de bloc ici.");
    }

    // Creatif interdit : seule la survie est autorisee.
    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        // Les ops gardent le creatif (/gamemode 1) ; les autres joueurs restent en survie.
        if (event.getNewGameMode() == GameMode.CREATIVE && !event.getPlayer().isOp()) {
            event.setCancelled(true);
        }
    }

    // Eau : retour au spawn du lobby, ou au spawn de son equipe en match.
    // Les op avec /build actif ne sont pas concernes.
    @EventHandler
    public void onWaterTouch(PlayerMoveEvent event) {
        Material type = event.getTo().getBlock().getType();
        if (type != Material.WATER && type != Material.STATIONARY_WATER) return;
        Player player = event.getPlayer();
        if (canBuildOutsideMatch(player)) return;

        final Location target = bedFightManager.isInMatch(player)
                ? bedFightManager.teamSpawn(player)
                : getSpawn();
        if (target == null) return;
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) player.teleport(target);
            }
        });
    }

    // --------------------------------------------------
    // GRADES TEMPORAIRES : RECOMPENSES DE FIN DE SAISON
    // --------------------------------------------------

    // Donne un grade pour une duree ; l'ancien grade revient a l'expiration.
    private void grantTemporaryRank(String name, String rank, long until) {
        String key = name.toLowerCase(Locale.ROOT);
        String previous = getConfig().contains("temp." + key + ".previous")
                ? getConfig().getString("temp." + key + ".previous")
                : playerRanks.getOrDefault(key, "default");
        getConfig().set("temp." + key + ".rank", rank);
        getConfig().set("temp." + key + ".previous", previous);
        getConfig().set("temp." + key + ".until", until);
        playerRanks.put(key, rank);
        saveRanks();
        refreshAllVisuals();

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            online.sendMessage(PREFIX + "Tu as gagne le grade " + getRankColor(rank) + getRankLabel(rank)
                    + ChatColor.WHITE + " pour 1 mois !");
        }
    }

    // Retire les grades temporaires expires et remet l'ancien grade.
    private void checkTemporaryRanks() {
        if (!getConfig().isConfigurationSection("temp")) return;
        long now = System.currentTimeMillis();
        List<String> expired = new java.util.ArrayList<String>();
        for (String key : getConfig().getConfigurationSection("temp").getKeys(false)) {
            if (getConfig().getLong("temp." + key + ".until") <= now) expired.add(key);
        }
        if (expired.isEmpty()) return;

        for (String key : expired) {
            playerRanks.put(key, getConfig().getString("temp." + key + ".previous", "default"));
            getConfig().set("temp." + key, null);
            Player online = Bukkit.getPlayerExact(key);
            if (online != null) {
                online.sendMessage(PREFIX + "Ton grade temporaire est termine.");
            }
        }
        saveRanks();
        refreshAllVisuals();
    }

    // Fin de saison : 1er PREMIUM, 2e et 3e PLUS, pour 1 mois.
    private void rewardSeason(CommandSender sender) {
        List<UUID> top = bedFightManager.rankedIds();
        long until = System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L;
        String[] ranks = {"premium", "plus", "plus"};
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            String name = Bukkit.getOfflinePlayer(top.get(i)).getName();
            if (name == null) continue;
            grantTemporaryRank(name, ranks[i], until);
            sender.sendMessage(PREFIX + (i + 1) + ". " + name + " : grade "
                    + ranks[i].toUpperCase(Locale.ROOT) + " pour 1 mois.");
        }
    }

    // Chute dans le vide : mort instantanee, sans passer par les degats normaux.
    @EventHandler
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID) return;
        if (!(event.getEntity() instanceof Player)) return;
        event.setDamage(1000.0);
    }

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