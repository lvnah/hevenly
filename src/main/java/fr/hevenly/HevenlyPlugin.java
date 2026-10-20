
package fr.hevenly;

import java.util.Iterator;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class HevenlyPlugin extends JavaPlugin implements Listener {

    private static final String GAME_NAME = ChatColor.RED + "Game";
    private static final String CONFIG_NAME = ChatColor.DARK_GRAY + "Config";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("Hevenly est active !");
    }

    @Override
    public void onDisable() {
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
        String commandName = command.getName().toLowerCase();

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

        return false;
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

        // Slot 0 : boussole Game
        inventory.setItem(0, createGameItem());

        // Slot 8 : name tag Config
        inventory.setItem(8, createConfigItem());

        player.updateInventory();
    }

    // --------------------------------------------------
    // CONNEXION : TELEPORTATION ET OBJETS
    // --------------------------------------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Location spawn = getSpawn();

        if (spawn != null) {
            player.teleport(spawn);
        }

        giveSpecialItems(player);
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

        // Attendre la fin de la reapparition pour remettre les objets.
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    giveSpecialItems(player);
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

        Player player = (Player) event.getWhoClicked();

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
