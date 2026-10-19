package fr.hevenly;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class HevenlyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // Cree la configuration si necessaire
        saveConfig();

        getLogger().info("Hevenly est active !");
    }

    @Override
    public void onDisable() {
        getLogger().info("Hevenly est desactive !");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        String commandName = command.getName().toLowerCase();

        // Commande /setspawn
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

            getConfig().set("spawn.world",
                    loc.getWorld().getName());
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

        // Commande /spawn
        if (commandName.equals("spawn")) {

            if (!(sender instanceof Player)) {
                sender.sendMessage("Cette commande est reservee aux joueurs.");
                return true;
            }

            Player player = (Player) sender;

            if (!getConfig().contains("spawn.world")) {
                player.sendMessage(ChatColor.RED
                        + "Le spawn n'a pas encore ete defini !");
                return true;
            }

            String worldName = getConfig().getString("spawn.world");
            World world = Bukkit.getWorld(worldName);

            if (world == null) {
                player.sendMessage(ChatColor.RED
                        + "Le monde du spawn est introuvable !");
                return true;
            }

            double x = getConfig().getDouble("spawn.x");
            double y = getConfig().getDouble("spawn.y");
            double z = getConfig().getDouble("spawn.z");
            float yaw = (float) getConfig().getDouble("spawn.yaw");
            float pitch = (float) getConfig().getDouble("spawn.pitch");

            Location spawn = new Location(
                    world, x, y, z, yaw, pitch
            );

            player.teleport(spawn);

            player.sendMessage(ChatColor.GREEN
                    + "Tu as ete teleporte au spawn !");
            return true;
        }

        return false;
    }
}
