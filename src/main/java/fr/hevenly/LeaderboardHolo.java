package fr.hevenly;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.metadata.FixedMetadataValue;

// Hologramme du top 10 ranked : des armor stands invisibles qui portent le texte.
class LeaderboardHolo {
    private static final String META = "hevenly_leaderboard";
    private static final double LINE_SPACING = 0.3;
    private static final int CLEAR_RADIUS = 10;
    private static final int TOP_COUNT = 10;

    private final HevenlyPlugin plugin;
    private final BedFightManager bedFight;

    LeaderboardHolo(HevenlyPlugin plugin, BedFightManager bedFight) {
        this.plugin = plugin;
        this.bedFight = bedFight;
    }

    // Pose l'hologramme a cet endroit et le memorise dans la config.
    void place(Location location) {
        // Un seul hologramme a la fois : on retire tous ceux qui sont affiches.
        clearAll();
        plugin.getConfig().set("holo.leaderboard.world", location.getWorld().getName());
        plugin.getConfig().set("holo.leaderboard.x", location.getX());
        plugin.getConfig().set("holo.leaderboard.y", location.getY());
        plugin.getConfig().set("holo.leaderboard.z", location.getZ());
        plugin.saveConfig();
        spawn(location);
    }

    // Recree l'hologramme memorise : au demarrage puis a intervalle regulier.
    void restore() {
        Location location = stored();
        if (location != null) spawn(location);
    }

    private Location stored() {
        String worldName = plugin.getConfig().getString("holo.leaderboard.world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world,
                plugin.getConfig().getDouble("holo.leaderboard.x"),
                plugin.getConfig().getDouble("holo.leaderboard.y"),
                plugin.getConfig().getDouble("holo.leaderboard.z"));
    }

    private void spawn(Location base) {
        base.getWorld().getChunkAt(base).load();
        clear(base);
        List<String> lines = lines();
        // Le titre est en haut ; le point de pose est le bas de l'hologramme.
        for (int i = 0; i < lines.size(); i++) {
            Location at = base.clone().add(0, (lines.size() - 1 - i) * LINE_SPACING, 0);
            ArmorStand stand = (ArmorStand) base.getWorld().spawnEntity(at, EntityType.ARMOR_STAND);
            stand.setVisible(false);
            stand.setBasePlate(false);
            stand.setCustomNameVisible(true);
            stand.setCustomName(lines.get(i));
            disableGravity(stand);
            stand.setMetadata(META, new FixedMetadataValue(plugin, true));
        }
    }

    // Sans gravite, l'armor stand ne tombe pas. Appel par reflexion : la methode n'est pas
    // garantie dans toutes les versions de l'API 1.8 ; sans elle, on garde le comportement normal.
    private void disableGravity(ArmorStand stand) {
        try {
            stand.getClass().getMethod("setGravity", boolean.class).invoke(stand, false);
        } catch (ReflectiveOperationException ignored) {
            // Methode absente : l'hologramme reste soumis a la gravite.
        }
    }

    // Retire tous les hologrammes et oublie l'emplacement memorise.
    void remove() {
        clearAll();
        plugin.getConfig().set("holo.leaderboard", null);
        plugin.saveConfig();
    }

    // Retire les armor stands de l'hologramme : ceux poses par le plugin, et ceux qui en portent le texte.
    private void clearAll() {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.hasMetadata(META) || isLeaderboardText(stand.getCustomName())) stand.remove();
            }
        }
    }

    private boolean isLeaderboardText(String name) {
        if (name == null) return false;
        String plain = ChatColor.stripColor(name);
        return plain.equals("TOP 10 LEADERBOARD") || plain.equals("Aucune partie classee")
                || plain.matches("^\\d+\\. .+ - \\d+$");
    }

    private void clear(Location base) {
        for (Entity entity : base.getWorld().getNearbyEntities(base, CLEAR_RADIUS, CLEAR_RADIUS, CLEAR_RADIUS)) {
            if (entity.hasMetadata(META)) entity.remove();
        }
    }

    // Titre, puis "rang. pseudo - ELO" pour chaque joueur du top.
    private List<String> lines() {
        List<String> lines = new ArrayList<String>();
        lines.add(ChatColor.RED + "TOP 10 LEADERBOARD");
        List<UUID> ids = bedFight.rankedIds();
        if (ids.isEmpty()) {
            lines.add(ChatColor.GRAY + "Aucune partie classee");
        }
        for (int i = 0; i < Math.min(TOP_COUNT, ids.size()); i++) {
            UUID id = ids.get(i);
            String name = Bukkit.getOfflinePlayer(id).getName();
            lines.add(ChatColor.GRAY + "" + (i + 1) + ". " + ChatColor.WHITE + (name == null ? "?" : name)
                    + ChatColor.GRAY + " - " + ChatColor.GREEN + bedFight.getElo(id));
        }
        // Recompenses de fin de saison, affichees sous le classement.
        lines.add(ChatColor.YELLOW + "Fin de saison :");
        lines.add(ChatColor.GRAY + "1er : grade " + ChatColor.RED + "PREMIUM" + ChatColor.GRAY + " 1 mois");
        lines.add(ChatColor.GRAY + "2e et 3e : grade " + ChatColor.RED + "PLUS" + ChatColor.GRAY + " 1 mois");
        return lines;
    }
}
