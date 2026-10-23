package fr.hevenly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

public class BedFightManager implements Listener, CommandExecutor {

    private static final long INVITE_TIMEOUT_MS = 60000L;
    // Nom de la hache du kit : tout autre bois de hache est refuse en match (outil WorldEdit).
    static final String MATCH_AXE_NAME = ChatColor.GRAY + "Hache en bois";
    // Slots du kit par disposition : [epee, laine, cisaille, hache].
    private static final int[][] KIT_SLOTS = {
        {0, 1, 2, 3},
        {1, 0, 3, 2},
        {3, 2, 1, 0}
    };
    // Hauteur maximale de pose, au-dessus du spawn de l'equipe.
    private static final int MAX_BUILD_HEIGHT = 10;
    private static final long MATCHMAKING_PERIOD_TICKS = 100L;
    private static final String ARENA_PREFIX = "bedfight.arenas.";

    // Elo : depart, ecart accepte qui s'elargit avec le temps d'attente.
    private static final int DEFAULT_ELO = 1000;
    private static final int BASE_ELO_RANGE = 100;
    private static final int ELO_RANGE_STEP = 50;
    private static final int ELO_RANGE_MAX = 1000;
    private static final long ELO_RANGE_STEP_MS = 15000L;

    private final HevenlyPlugin plugin;
    // File 1v1 non classee : un joueur par entree.
    private final List<UUID> soloQueue = new ArrayList<UUID>();
    // File 1v1 classee : joueurs apparies selon leur elo.
    private final List<UUID> rankedQueue = new ArrayList<UUID>();
    private final Map<UUID, Long> rankedSince = new HashMap<UUID, Long>();
    // File 2v2 non classee : un groupe par entree.
    private final List<Party> duoQueue = new ArrayList<Party>();
    // Groupes 2v2 : chaque membre pointe vers le meme objet.
    private final Map<UUID, Party> parties = new HashMap<UUID, Party>();
    private final Map<UUID, Invite> invites = new HashMap<UUID, Invite>();
    // Matchs en cours, un par arene.
    private final List<Match> matches = new ArrayList<Match>();
    // Participants de chaque match, eliminés compris, jusqu'a la fin de la partie.
    private final Map<UUID, Match> playerMatch = new HashMap<UUID, Match>();

    public BedFightManager(HevenlyPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("bedfight.enabled", false);
    }

    public boolean isInMatch(Player player) {
        return activeMatchOf(player.getUniqueId()) != null;
    }

    private Match activeMatchOf(UUID id) {
        Match match = playerMatch.get(id);
        if (match == null || match.finished || !match.alive.contains(id)) return null;
        return match;
    }

    // --------------------------------------------------
    // ELO ET STATISTIQUES
    // --------------------------------------------------

    public int getElo(UUID id) {
        return plugin.getConfig().getInt("ranked.elo." + id, DEFAULT_ELO);
    }

    private void setElo(UUID id, int elo) {
        plugin.getConfig().set("ranked.elo." + id, elo);
        plugin.saveConfig();
    }

    private int getStat(UUID id, String stat) {
        return plugin.getConfig().getInt("ranked.stats." + id + "." + stat, 0);
    }

    private void addStat(UUID id, String stat) {
        plugin.getConfig().set("ranked.stats." + id + "." + stat, getStat(id, stat) + 1);
    }

    // Les K sont plus forts pour les bas elos : ils bougent plus vite.
    private static int kFactor(int elo) {
        if (elo < 1000) return 40;
        if (elo < 2000) return 32;
        return 24;
    }

    public static String tierName(int elo) {
        if (elo < 1000) return "Bronze";
        if (elo < 1400) return "Argent";
        if (elo < 1800) return "Or";
        if (elo < 2200) return "Diamant";
        return "Maitre";
    }

    private void applyRankedResult(Match match, String winner) {
        String loser = "red".equals(winner) ? "blue" : "red";
        List<UUID> winners = membersOf(match, winner);
        List<UUID> losers = membersOf(match, loser);
        // Le classe se joue en 1v1 : sans un joueur de chaque cote, rien a calculer.
        if (winners.size() != 1 || losers.size() != 1) return;

        UUID w = winners.get(0);
        UUID l = losers.get(0);
        int ew = getElo(w);
        int el = getElo(l);

        double expectedW = 1.0 / (1.0 + Math.pow(10, (el - ew) / 400.0));
        double surprise = 1.0 - expectedW;
        int gain = Math.max(1, (int) Math.round(kFactor(ew) * surprise));
        int loss = Math.max(1, (int) Math.round(kFactor(el) * surprise));
        int newW = ew + gain;
        int newL = Math.max(0, el - loss);

        setElo(w, newW);
        setElo(l, newL);
        addStat(w, "wins");
        addStat(l, "losses");
        plugin.saveConfig();

        notifyElo(w, ew, newW, "+" + gain);
        notifyElo(l, el, newL, "-" + loss);
    }

    private void notifyElo(UUID id, int before, int after, String delta) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        player.sendMessage(ChatColor.GRAY + "ELO : " + before + " -> " + after + " (" + delta + ") "
                + ChatColor.YELLOW + tierName(after));
    }

    // --------------------------------------------------
    // FILES D'ATTENTE
    // --------------------------------------------------

    // Refuse l'entree dans une file si le joueur est deja occupe.
    private boolean rejectQueueEntry(Player player) {
        UUID id = player.getUniqueId();
        if (!isEnabled()) {
            player.sendMessage(ChatColor.RED + "Le BedFight n'est pas active sur ce serveur.");
            return true;
        }
        if (activeMatchOf(id) != null) {
            player.sendMessage(ChatColor.RED + "Tu es deja dans une partie.");
            return true;
        }
        if (soloQueue.contains(id) || rankedQueue.contains(id)) {
            player.sendMessage(ChatColor.RED + "Tu es deja dans la file d'attente.");
            return true;
        }
        Party party = parties.get(id);
        if (party != null && duoQueue.contains(party)) {
            player.sendMessage(ChatColor.RED + "Ton groupe 2v2 est deja en file : /bfleave pour la quitter.");
            return true;
        }
        return false;
    }

    // Appele par le GUI : 1v1 non classee.
    public void joinQueue(Player player) {
        addToQueue(player, false);
    }

    // Appele par le GUI : 1v1 classee, appariement par elo.
    public void joinRankedQueue(Player player) {
        addToQueue(player, true);
    }

    private void addToQueue(Player player, boolean ranked) {
        if (rejectQueueEntry(player)) return;
        UUID id = player.getUniqueId();
        if (parties.containsKey(id)) {
            player.sendMessage(ChatColor.RED + "Tu es dans un groupe 2v2 : utilise /bfleave pour le quitter.");
            return;
        }

        if (ranked) {
            rankedQueue.add(id);
            rankedSince.put(id, System.currentTimeMillis());
        } else {
            soloQueue.add(id);
        }
        giveQueueItems(player);

        if (ranked) {
            player.sendMessage(ChatColor.GOLD + "Hevenly BedFight " + ChatColor.GRAY + "» "
                    + ChatColor.YELLOW + "Recherche d'un adversaire classe (ELO " + getElo(id) + ")...");
        } else {
            player.sendMessage(ChatColor.GOLD + "Hevenly BedFight " + ChatColor.GRAY + "» "
                    + ChatColor.YELLOW + "Recherche d'un adversaire...");
        }
        warnIfNoArena(player);
        tryMatchmaking();
    }

    // Appele par le GUI : 2v2 non classee avec le groupe du joueur.
    public void joinDuoQueue(Player player) {
        if (!isEnabled()) {
            player.sendMessage(ChatColor.RED + "Le BedFight n'est pas active sur ce serveur.");
            return;
        }
        if (activeMatchOf(player.getUniqueId()) != null) {
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
            if (member == null) continue;
            giveQueueItems(member);
            member.sendMessage(ChatColor.GOLD + "Hevenly BedFight " + ChatColor.GRAY + "» "
                    + ChatColor.YELLOW + "Groupe 2v2 : recherche d'un adversaire...");
        }
        warnIfNoArena(player);
        tryMatchmaking();
    }

    // Appele par /bfleave et par le bouton "Quitter la file".
    public void leaveQueue(Player player) {
        if (activeMatchOf(player.getUniqueId()) != null) {
            player.sendMessage(ChatColor.RED + "Tu ne peux pas quitter une partie en cours avec /bfleave.");
            player.sendMessage(ChatColor.GRAY + "Deconnecte-toi pour abandonner la partie.");
            return;
        }
        UUID id = player.getUniqueId();
        boolean queued = soloQueue.remove(id);
        if (rankedQueue.remove(id)) queued = true;
        rankedSince.remove(id);

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
        returnToLobby(player);
    }

    private boolean isSearching(UUID id) {
        return soloQueue.contains(id) || rankedQueue.contains(id);
    }

    // except : membre deja traite par l'appelant (ex. celui qui quitte). Un membre en partie reste sur l'arene.
    private void dissolveParty(Party party, UUID except, String message) {
        duoQueue.remove(party);
        for (UUID id : party.members()) {
            parties.remove(id);
            if (id.equals(except)) continue;
            Player member = Bukkit.getPlayer(id);
            if (member == null || activeMatchOf(id) != null) continue;
            if (message != null) member.sendMessage(message);
            returnToLobby(member);
        }
    }

    // /invite disband : seul le createur du groupe peut le dissoudre.
    private void disbandParty(Player player) {
        UUID id = player.getUniqueId();
        Party party = parties.get(id);
        if (party == null) {
            player.sendMessage(ChatColor.RED + "Tu n'as pas de groupe.");
            return;
        }
        if (isInMatch(player)) {
            player.sendMessage(ChatColor.RED + "Impossible de dissoudre le groupe pendant une partie.");
            return;
        }
        if (!party.first.equals(id)) {
            player.sendMessage(ChatColor.RED + "Seul le createur du groupe peut le dissoudre. Utilise /invite leave.");
            return;
        }
        dissolveParty(party, null, ChatColor.YELLOW + "Le groupe a ete dissous par " + player.getName() + ".");
    }

    // /invite leave : un membre quitte le groupe.
    private void leaveParty(Player player) {
        UUID id = player.getUniqueId();
        Party party = parties.get(id);
        if (party == null) {
            player.sendMessage(ChatColor.RED + "Tu n'as pas de groupe.");
            return;
        }
        if (isInMatch(player)) {
            player.sendMessage(ChatColor.RED + "Impossible de quitter le groupe pendant une partie.");
            return;
        }
        dissolveParty(party, id, ChatColor.YELLOW + player.getName() + " a quitte le groupe.");
        player.sendMessage(ChatColor.YELLOW + "Tu as quitte le groupe.");
        returnToLobby(player);
    }

    private void giveQueueItems(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(null);
        inv.setItem(8, plugin.createQueueLeaveItem());
        player.updateInventory();
    }

    // Quitter une file ou un groupe ne deplace pas le joueur : seuls les objets du lobby reviennent.
    private void returnToLobby(Player player) {
        plugin.giveSpecialItems(player);
    }

    private void warnIfNoArena(Player player) {
        if (!hasAnyArena()) {
            player.sendMessage(ChatColor.RED + "Aucune arene BedFight n'est configuree. Contacte un administrateur.");
        }
    }

    private List<UUID> queuedPlayerIds() {
        List<UUID> ids = new ArrayList<UUID>(soloQueue);
        ids.addAll(rankedQueue);
        for (Party party : duoQueue) {
            ids.addAll(party.members());
        }
        return ids;
    }

    // --------------------------------------------------
    // APPARIEMENT ET DEMARRAGE DES PARTIES
    // --------------------------------------------------

    private void tryMatchmaking() {
        if (!isEnabled()) return;
        while (startNextMatch()) {
            // Tant qu'une arene libre et deux adversaires existent, on lance un match.
        }
    }

    // Ordre : 2v2, puis 1v1 classee (elo proche), puis 1v1 non classee.
    private boolean startNextMatch() {
        String arena = freeArena();
        if (arena == null) return false;

        if (duoQueue.size() >= 2) {
            Party red = duoQueue.remove(0);
            Party blue = duoQueue.remove(0);
            startMatch(arena, false, true, red.members(), blue.members(), Arrays.asList(red, blue));
            return true;
        }

        UUID[] pair = findRankedPair();
        if (pair != null) {
            rankedQueue.remove(pair[0]);
            rankedQueue.remove(pair[1]);
            rankedSince.remove(pair[0]);
            rankedSince.remove(pair[1]);
            startMatch(arena, true, false, Arrays.asList(pair[0]), Arrays.asList(pair[1]), new ArrayList<Party>());
            return true;
        }

        if (soloQueue.size() >= 2) {
            UUID red = soloQueue.remove(0);
            UUID blue = soloQueue.remove(0);
            startMatch(arena, false, false, Arrays.asList(red), Arrays.asList(blue), new ArrayList<Party>());
            return true;
        }
        return false;
    }

    // L'ecart d'elo accepte est le plus large des deux joueurs, il grandit avec l'attente.
    private UUID[] findRankedPair() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < rankedQueue.size(); i++) {
            UUID a = rankedQueue.get(i);
            for (int j = i + 1; j < rankedQueue.size(); j++) {
                UUID b = rankedQueue.get(j);
                int allowed = Math.max(eloRange(a, now), eloRange(b, now));
                if (Math.abs(getElo(a) - getElo(b)) <= allowed) {
                    return new UUID[] {a, b};
                }
            }
        }
        return null;
    }

    private int eloRange(UUID id, long now) {
        Long since = rankedSince.get(id);
        long waited = since == null ? 0L : now - since;
        int range = BASE_ELO_RANGE + (int) (waited / ELO_RANGE_STEP_MS) * ELO_RANGE_STEP;
        return Math.min(ELO_RANGE_MAX, range);
    }

    private void startMatch(String arena, boolean ranked, boolean duo,
                            List<UUID> reds, List<UUID> blues, List<Party> groups) {
        Match match = new Match(arena, ranked, duo);
        match.parties.addAll(groups);
        for (UUID id : reds) addToMatch(match, id, "red");
        for (UUID id : blues) addToMatch(match, id, "blue");
        matches.add(match);

        restoreBeds(arena);
        for (UUID id : match.players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) preparePlayer(match, player, match.teams.get(id));
        }
        broadcastMatch(match, ChatColor.GOLD + "La partie commence ! " + teamNames(match, "red")
                + ChatColor.GRAY + " contre " + teamNames(match, "blue") + ChatColor.GOLD + ".");
        checkMatchEnd(match, "");
    }

    private void addToMatch(Match match, UUID id, String team) {
        if (Bukkit.getPlayer(id) == null) return;
        match.teams.put(id, team);
        match.players.add(id);
        match.alive.add(id);
        playerMatch.put(id, match);
    }

    private String freeArena() {
        for (String name : arenaNames()) {
            if (isArenaComplete(name) && !isArenaBusy(name)) return name;
        }
        return null;
    }

    private boolean isArenaBusy(String name) {
        for (Match match : matches) {
            if (!match.finished && match.arena.equals(name)) return true;
        }
        return false;
    }

    private boolean hasAnyArena() {
        for (String name : arenaNames()) {
            if (isArenaComplete(name)) return true;
        }
        return false;
    }

    private List<String> arenaNames() {
        List<String> names = new ArrayList<String>();
        if (!plugin.getConfig().isConfigurationSection("bedfight.arenas")) return names;
        names.addAll(plugin.getConfig().getConfigurationSection("bedfight.arenas").getKeys(false));
        return names;
    }

    private boolean isArenaComplete(String name) {
        String base = ARENA_PREFIX + name;
        return readLocation(base + ".spawns.red") != null
                && readLocation(base + ".spawns.blue") != null
                && !getConfiguredBedLocations(name, "red").isEmpty()
                && !getConfiguredBedLocations(name, "blue").isEmpty();
    }

    // --------------------------------------------------
    // COMMANDES
    // --------------------------------------------------

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        String[] commands = {"bfsetspawn", "bfsetbed", "bfstart", "bfleave", "invite",
                "bfarenas", "bfarena", "elo", "leaderboard"};
        for (String name : commands) {
            if (plugin.getCommand(name) != null) {
                plugin.getCommand(name).setExecutor(this);
            }
        }
        // Relance l'appariement : l'elo accepte s'elargit avec le temps d'attente.
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                tryMatchmaking();
            }
        }, MATCHMAKING_PERIOD_TICKS, MATCHMAKING_PERIOD_TICKS);
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
            if (args.length != 2 || (!args[0].equalsIgnoreCase("red") && !args[0].equalsIgnoreCase("blue"))
                    || args[1].isEmpty() || args[1].contains(".")) {
                player.sendMessage(ChatColor.RED + "Utilisation : /" + name + " <red|blue> <arene>");
                return true;
            }

            String team = args[0].toLowerCase(Locale.ROOT);
            String arena = args[1].toLowerCase(Locale.ROOT);
            if (name.equals("bfsetspawn")) {
                saveLocation(ARENA_PREFIX + arena + ".spawns." + team, player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Spawn de l'equipe " + teamColor(team) + team.toUpperCase(Locale.ROOT)
                        + ChatColor.GREEN + " defini pour l'arene " + arena + ".");
            } else {
                Block target = player.getTargetBlock((HashSet<Byte>) null, 6);
                if (target == null || target.getType() != Material.BED_BLOCK) {
                    player.sendMessage(ChatColor.RED + "Regarde un lit de l'equipe " + team.toUpperCase(Locale.ROOT)
                            + ChatColor.RED + " a moins de 6 blocs.");
                    return true;
                }
                saveBed(arena, team, target);
                player.sendMessage(ChatColor.GREEN + "Lit de l'equipe " + teamColor(team)
                        + team.toUpperCase(Locale.ROOT) + ChatColor.GREEN + " enregistre pour l'arene " + arena + ".");
            }
            return true;
        }

        if (name.equals("bfstart")) {
            if (!sender.hasPermission("hevenly.bedfight.admin")) {
                sender.sendMessage(ChatColor.RED + "Tu n'as pas la permission !");
                return true;
            }
            if (!hasAnyArena()) {
                sender.sendMessage(ChatColor.RED + "Configure au moins une arene avant de lancer une partie.");
                return true;
            }
            if (startNextMatch()) {
                sender.sendMessage(HevenlyPlugin.PREFIX + "Partie BedFight demarree.");
            } else {
                sender.sendMessage(ChatColor.RED + "Pas assez de joueurs en file ou aucune arene libre.");
            }
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

        if (name.equals("bfarenas")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }
            Player player = (Player) sender;
            if (!plugin.isArenaStaff(player)) {
                player.sendMessage(ChatColor.RED + "Tu n'as pas la permission !");
                return true;
            }
            listArenas(player);
            return true;
        }

        if (name.equals("bfarena")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }
            Player player = (Player) sender;
            if (!plugin.isArenaStaff(player)) {
                player.sendMessage(ChatColor.RED + "Tu n'as pas la permission !");
                return true;
            }
            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Utilisation : /bfarena <arene>");
                return true;
            }
            Location spawn = readLocation(ARENA_PREFIX + args[0].toLowerCase(Locale.ROOT) + ".spawns.red");
            if (spawn == null) {
                player.sendMessage(ChatColor.RED + "Arene introuvable ou sans spawn rouge.");
                return true;
            }
            player.teleport(spawn);
            player.sendMessage(HevenlyPlugin.PREFIX + "Tu es teleporte a l'arene " + args[0].toLowerCase(Locale.ROOT) + ".");
            return true;
        }

        if (name.equals("leaderboard")) {
            showLeaderboard(sender);
            return true;
        }

        if (name.equals("elo")) {
            Player target;
            if (args.length == 0 && sender instanceof Player) {
                target = (Player) sender;
            } else if (args.length == 1) {
                target = Bukkit.getPlayer(args[0]);
            } else {
                sender.sendMessage(ChatColor.RED + "Utilisation : /elo [pseudo]");
                return true;
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Ce joueur n'est pas connecte.");
                return true;
            }
            UUID id = target.getUniqueId();
            int elo = getElo(id);
            sender.sendMessage(HevenlyPlugin.PREFIX + "ELO de " + target.getName() + " : " + ChatColor.GREEN + elo
                    + ChatColor.GRAY + " (" + tierName(elo) + ") - "
                    + getStat(id, "wins") + " victoires, " + getStat(id, "losses") + " defaites");
            return true;
        }

        return false;
    }

    // Top 10 du classe, trie par elo decroissant.
    private void showLeaderboard(CommandSender sender) {
        List<UUID> ids = new ArrayList<UUID>();
        if (plugin.getConfig().isConfigurationSection("ranked.elo")) {
            for (String key : plugin.getConfig().getConfigurationSection("ranked.elo").getKeys(false)) {
                try {
                    ids.add(UUID.fromString(key));
                } catch (IllegalArgumentException ignored) {
                    // Cle qui n'est pas un UUID : on l'ignore.
                }
            }
        }
        Collections.sort(ids, new Comparator<UUID>() {
            @Override
            public int compare(UUID a, UUID b) {
                return Integer.compare(getElo(b), getElo(a));
            }
        });

        sender.sendMessage(HevenlyPlugin.PREFIX + "Classement ranked (top 10) :");
        if (ids.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Aucune partie classee pour le moment.");
            return;
        }
        for (int i = 0; i < Math.min(10, ids.size()); i++) {
            UUID id = ids.get(i);
            String name = Bukkit.getOfflinePlayer(id).getName();
            int elo = getElo(id);
            sender.sendMessage(ChatColor.GRAY + "" + (i + 1) + ". " + ChatColor.WHITE
                    + (name == null ? "?" : name) + ChatColor.GRAY + " - " + ChatColor.GREEN + elo
                    + ChatColor.GRAY + " (" + tierName(elo) + ") " + getStat(id, "wins") + "V / "
                    + getStat(id, "losses") + "D");
        }
    }

    private void listArenas(Player player) {
        List<String> names = arenaNames();
        if (names.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Aucune arene configuree.");
            return;
        }
        player.sendMessage(HevenlyPlugin.PREFIX + "Arenes BedFight :");
        for (String name : names) {
            String status;
            if (!isArenaComplete(name)) {
                status = ChatColor.RED + "incomplete";
            } else if (isArenaBusy(name)) {
                status = ChatColor.YELLOW + "en jeu";
            } else {
                status = ChatColor.GREEN + "libre";
            }
            player.sendMessage(ChatColor.GRAY + "- " + ChatColor.AQUA + name + ChatColor.GRAY + " : " + status);
        }
        player.sendMessage(ChatColor.GRAY + "Tape /bfarena <nom> pour y aller.");
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
        if (args[0].equalsIgnoreCase("disband")) {
            disbandParty(player);
            return;
        }
        if (args[0].equalsIgnoreCase("leave")) {
            leaveParty(player);
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
        if (isSearching(inviter.getUniqueId()) || isSearching(other.getUniqueId())) {
            return "Quitte la file 1v1 avant de former un groupe.";
        }
        return null;
    }

    // --------------------------------------------------
    // ARENE : CONFIGURATION ET REGLES
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

    private void saveBed(String arena, String team, Block target) {
        List<Block> parts = getBedParts(target);
        String base = ARENA_PREFIX + arena + ".beds." + team + ".blocks";
        plugin.getConfig().set(base, null);
        int i = 0;
        for (Block block : parts) {
            String path = base + "." + i++;
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

    private List<Location> getConfiguredBedLocations(String arena, String team) {
        List<Location> locations = new ArrayList<Location>();
        String path = ARENA_PREFIX + arena + ".beds." + team + ".blocks";
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

    private void restoreBeds(String arena) {
        for (String team : Arrays.asList("red", "blue")) {
            String path = ARENA_PREFIX + arena + ".beds." + team + ".blocks";
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

    private boolean isConfiguredBedBlock(String arena, Block block, String team) {
        for (Location loc : getConfiguredBedLocations(arena, team)) {
            if (loc.getWorld().equals(block.getWorld())
                    && loc.getBlockX() == block.getX()
                    && loc.getBlockY() == block.getY()
                    && loc.getBlockZ() == block.getZ()) return true;
        }
        return false;
    }

    private String bedTeamForBlock(String arena, Block block) {
        if (isConfiguredBedBlock(arena, block, "red")) return "red";
        if (isConfiguredBedBlock(arena, block, "blue")) return "blue";
        return null;
    }

    private void preparePlayer(Match match, Player player, String team) {
        player.setGameMode(GameMode.SURVIVAL);
        plugin.setMatchColor(player, teamColor(team));
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
        giveMatchKit(player, team);
        Location spawn = readLocation(ARENA_PREFIX + match.arena + ".spawns." + team);
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
        // Ordre des objets : epee, laine, cisaille, hache ; la disposition vient du menu Config.
        int[] slots = KIT_SLOTS[plugin.getKitLayout(player) % KIT_SLOTS.length];
        inv.setItem(slots[0], unbreakable(new ItemStack(Material.WOOD_SWORD)));
        inv.setItem(slots[1], new ItemStack(Material.WOOL, 64, (short) 0, (byte) (red ? 14 : 11)));
        inv.setItem(slots[2], unbreakable(new ItemStack(Material.SHEARS)));
        inv.setItem(slots[3], namedUnbreakable(Material.WOOD_AXE, MATCH_AXE_NAME));
        inv.setItem(4, new ItemStack(Material.WOOD_PICKAXE));
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

    private ItemStack namedUnbreakable(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
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
        Match match = activeMatchOf(player.getUniqueId());
        // Hors partie, HevenlyPlugin decide (/build).
        if (match == null) return;

        Block block = event.getBlock();
        String bedTeam = bedTeamForBlock(match.arena, block);
        if (block.getType() == Material.BED_BLOCK) {
            // Annule l'evenement : le lit disparait tout de suite, sans drop au sol.
            event.setCancelled(true);
            if (bedTeam == null || !Boolean.TRUE.equals(match.bedAlive.get(bedTeam))) return;
            String ownTeam = match.teams.get(player.getUniqueId());
            if (bedTeam.equals(ownTeam)) {
                player.sendMessage(ChatColor.RED + "Tu ne peux pas casser ton propre lit !");
                return;
            }
            match.bedAlive.put(bedTeam, false);
            for (Location loc : getConfiguredBedLocations(match.arena, bedTeam)) {
                loc.getBlock().setType(Material.AIR);
            }
            broadcastMatch(match, ChatColor.GOLD + "Le lit de l'equipe " + teamColor(bedTeam)
                    + bedTeam.toUpperCase(Locale.ROOT) + ChatColor.GOLD + " a ete detruit par "
                    + player.getName() + " !");
            for (UUID id : match.players) {
                Player participant = Bukkit.getPlayer(id);
                if (participant != null) {
                    participant.playSound(participant.getLocation(), Sound.ENDERDRAGON_GROWL, 1.0f, 1.0f);
                }
            }
            return;
        }

        Material type = block.getType();
        if (type != Material.WOOL && type != Material.WOOD && type != Material.LOG && type != Material.LOG_2) {
            event.setCancelled(true);
            return;
        }
        // Le bloc casse sera remis en place a la fin de la partie.
        match.rememberOriginal(new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()),
                type, block.getData());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        Match match = activeMatchOf(player.getUniqueId());
        // Hors partie, HevenlyPlugin decide (/build).
        if (match == null) return;
        Material type = event.getBlockPlaced().getType();
        if (type != Material.WOOL && type != Material.WOOD && type != Material.LOG && type != Material.LOG_2) {
            event.setCancelled(true);
            return;
        }
        Location spawn = readLocation(ARENA_PREFIX + match.arena + ".spawns." + match.teams.get(player.getUniqueId()));
        if (spawn != null && event.getBlockPlaced().getY() > spawn.getBlockY() + MAX_BUILD_HEIGHT) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Tu ne peux pas construire plus de "
                    + MAX_BUILD_HEIGHT + " blocs au-dessus du spawn.");
            return;
        }

        BlockState replaced = event.getBlockReplacedState();
        match.rememberOriginal(new BlockKey(event.getBlockPlaced().getWorld().getName(),
                event.getBlockPlaced().getX(), event.getBlockPlaced().getY(), event.getBlockPlaced().getZ()),
                replaced.getType(), replaced.getRawData());
    }

    // En match, mort des la couche -1 pour aller plus vite. Hors match, rien ne change.
    @EventHandler
    public void onMatchFall(PlayerMoveEvent event) {
        if (event.getTo().getBlockY() > -1) return;
        Player player = event.getPlayer();
        if (activeMatchOf(player.getUniqueId()) == null || player.isDead()) return;
        player.setHealth(0.0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!isEnabled()) return;
        Player player = event.getEntity();
        Match match = activeMatchOf(player.getUniqueId());
        if (match == null) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setDeathMessage(null);

        UUID id = player.getUniqueId();
        String team = match.teams.get(id);
        if (Boolean.TRUE.equals(match.bedAlive.get(team))) {
            player.sendMessage(ChatColor.GREEN + "Ton lit est encore intact : tu peux revenir dans la partie !");
            return;
        }

        match.alive.remove(id);
        player.sendMessage(ChatColor.RED + "Ton lit est detruit : tu es elimine !");
        checkMatchEnd(match, "");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!isEnabled()) return;
        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();
        Match match = playerMatch.get(id);
        // Hors partie ou elimine : HevenlyPlugin gere le lobby.
        if (match == null || match.finished || !match.alive.contains(id)) return;

        String team = match.teams.get(id);
        if (Boolean.TRUE.equals(match.bedAlive.get(team))) {
            Location spawn = readLocation(ARENA_PREFIX + match.arena + ".spawns." + team);
            if (spawn != null) event.setRespawnLocation(spawn);
        } else {
            // Le lit a ete detruit pendant la mort : elimination au respawn.
            match.alive.remove(id);
            Location lobby = getLobbySpawn();
            if (lobby != null) event.setRespawnLocation(lobby);
            checkMatchEnd(match, "");
        }

        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                Match current = activeMatchOf(id);
                if (current != null) {
                    giveMatchKit(player, current.teams.get(id));
                } else {
                    plugin.giveSpecialItems(player);
                }
            }
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        soloQueue.remove(id);
        rankedQueue.remove(id);
        rankedSince.remove(id);
        Party party = parties.get(id);
        if (party != null) {
            dissolveParty(party, id, ChatColor.RED + player.getName() + " a quitte ton groupe.");
        }

        Match match = activeMatchOf(id);
        if (match != null) {
            // Quitter en partie compte comme une elimination.
            match.alive.remove(id);
            checkMatchEnd(match, " par abandon");
        }
    }

    private List<UUID> membersOf(Match match, String team) {
        List<UUID> ids = new ArrayList<UUID>();
        for (UUID id : match.players) {
            if (team.equals(match.teams.get(id))) ids.add(id);
        }
        return ids;
    }

    private String teamNames(Match match, String team) {
        StringBuilder names = new StringBuilder();
        for (UUID id : membersOf(match, team)) {
            Player player = Bukkit.getPlayer(id);
            if (names.length() > 0) names.append(ChatColor.GRAY).append(", ");
            names.append(teamColor(team)).append(player == null ? "?" : player.getName());
        }
        return names.toString();
    }

    // Fin de partie quand une equipe n'a plus aucun joueur en jeu.
    private void checkMatchEnd(Match match, String reason) {
        if (match.finished) return;
        boolean redAlive = match.countAlive("red") > 0;
        boolean blueAlive = match.countAlive("blue") > 0;
        if (redAlive && blueAlive) return;
        if (!redAlive && !blueAlive) {
            finishMatch(match, null, ChatColor.YELLOW + "La partie est annulee.");
            return;
        }
        String winner = redAlive ? "red" : "blue";
        finishMatch(match, winner, teamNames(match, winner) + ChatColor.GREEN
                + " remporte la partie" + reason + " !");
    }

    private void finishMatch(Match match, String winner, String message) {
        if (match.finished) return;
        match.finished = true;
        matches.remove(match);

        broadcastMatch(match, ChatColor.GOLD + "Partie terminee ! " + message);
        restoreChangedBlocks(match);
        restoreBeds(match.arena);
        if (match.ranked && winner != null) applyRankedResult(match, winner);

        final List<UUID> participants = new ArrayList<UUID>(match.players);
        for (UUID id : participants) {
            playerMatch.remove(id);
        }

        // Fin de partie : tout le monde retourne au spawn avec ses objets, sans remise en file.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Location lobby = getLobbySpawn();
                for (UUID id : participants) {
                    Player player = Bukkit.getPlayer(id);
                    if (player == null) continue;
                    plugin.setMatchColor(player, null);
                    if (player.isDead()) continue;
                    player.getInventory().clear();
                    player.getInventory().setArmorContents(null);
                    if (lobby != null) player.teleport(lobby);
                    plugin.giveSpecialItems(player);
                }
                tryMatchmaking();
            }
        });
    }

    // Remet chaque bloc pose ou casse dans son etat d'origine.
    private void restoreChangedBlocks(Match match) {
        for (Map.Entry<BlockKey, BlockSnapshot> entry : match.changed.entrySet()) {
            BlockKey key = entry.getKey();
            World world = Bukkit.getWorld(key.world);
            if (world == null) continue;
            Block block = world.getBlockAt(key.x, key.y, key.z);
            block.setType(entry.getValue().type);
            block.setData(entry.getValue().data, false);
        }
        match.changed.clear();
    }

    private void broadcastMatch(Match match, String message) {
        for (UUID id : match.players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(message);
        }
        for (UUID id : queuedPlayerIds()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(message);
        }
    }

    // Un match sur une arene. Les joueurs eliminés restent dans players/teams.
    private static final class Match {
        private final String arena;
        private final boolean ranked;
        private final boolean duo;
        private final List<UUID> players = new ArrayList<UUID>();
        private final Map<UUID, String> teams = new HashMap<UUID, String>();
        private final Set<UUID> alive = new HashSet<UUID>();
        private final List<Party> parties = new ArrayList<Party>();
        private final Map<String, Boolean> bedAlive = new HashMap<String, Boolean>();
        // Etat d'origine de chaque bloc touche pendant la partie, pour remettre la map en place.
        private final Map<BlockKey, BlockSnapshot> changed = new LinkedHashMap<BlockKey, BlockSnapshot>();
        private boolean finished = false;

        private Match(String arena, boolean ranked, boolean duo) {
            this.arena = arena;
            this.ranked = ranked;
            this.duo = duo;
            bedAlive.put("red", true);
            bedAlive.put("blue", true);
        }

        private void rememberOriginal(BlockKey key, Material type, byte data) {
            if (!changed.containsKey(key)) {
                changed.put(key, new BlockSnapshot(type, data));
            }
        }

        private int countAlive(String team) {
            int count = 0;
            for (UUID id : alive) {
                if (team.equals(teams.get(id))) count++;
            }
            return count;
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

    private static final class BlockSnapshot {
        private final Material type;
        private final byte data;

        private BlockSnapshot(Material type, byte data) {
            this.type = type;
            this.data = data;
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
