package fr.hevenly;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

public class HevenlyPlugin extends JavaPlugin implements Listener {

    private static final String GAME_NAME = ChatColor.RED + "Game";
    private static final String CONFIG_NAME = ChatColor.DARK_GRAY + "Config";

    private static final String[] VALID_RANKS = {
        "default", "vip", "helper", "elite", "mod", "owner"
    };

    // Pseudos en minuscules -> grade.
    private final Map<String, String> playerRanks =
            new ConcurrentHashMap<String, String>();

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

        loadRanks();

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
        getLogger().info("Hevenly est desactive !");
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
                        + "Utilisation : /setrank <joueur> <default|vip|helper|elite|mod|owner>");
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
                        + "Grade invalide. Grades disponibles : default, vip, helper, elite, mod, owner.");
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

    private ChatColor getRankColor(String rank) {
        switch (rank.toLowerCase(Locale.ROOT)) {
            case "owner":
                return ChatColor.DARK_RED;
            case "mod":
                return ChatColor.DARK_PURPLE;
            case "elite":
                return ChatColor.AQUA;
            case "helper":
                return ChatColor.YELLOW;
            case "vip":
                return ChatColor.GOLD;
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

        event.setFormat(
                ChatColor.DARK_GRAY + "<"
                + nameColor + safeName
                + ChatColor.DARK_GRAY + "> "
                + ChatColor.WHITE + "%2$s"
        );
    }

    // --------------------------------------------------
    // COULEUR DU PSEUDO : NAMETAG ET TAB
    // Compatible Spigot 1.8.8
    // --------------------------------------------------

    private String getRankTeamName(String rank) {
        return "hv_" + rank;
    }

    private void applyPlayerToBoard(Scoreboard board, Player target) {
        String rank = getRank(target);
        String teamName = getRankTeamName(rank);

        Team team = board.getTeam(teamName);

        if (team == null) {
            team = board.registerNewTeam(teamName);
        }

        // Prefixe colore le pseudo au-dessus de la tete et dans le TAB.
        team.setPrefix(getRankColor(rank).toString());
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
                getRankColor(getRank(target)) + target.getName() + ChatColor.RESET
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
                    getRankColor(getRank(player))
                    + player.getName()
                    + ChatColor.RESET
            );
        }

        refreshAllBoards();
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