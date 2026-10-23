package fr.hevenly;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;

// Couleurs personnalisables (grades plus, premium, mod, owner) : pseudo, laine et armure.
enum PlayerColor {
    WHITE("Blanc", ChatColor.WHITE, DyeColor.WHITE, 0xF9FFFE),
    YELLOW("Jaune", ChatColor.YELLOW, DyeColor.YELLOW, 0xFED83D),
    ORANGE("Orange", ChatColor.GOLD, DyeColor.ORANGE, 0xF9801D),
    LIME("Vert clair", ChatColor.GREEN, DyeColor.LIME, 0x80C71F),
    GREEN("Vert", ChatColor.DARK_GREEN, DyeColor.GREEN, 0x5E7C16),
    LIGHT_BLUE("Bleu clair", ChatColor.AQUA, DyeColor.LIGHT_BLUE, 0x3AB3DA),
    BLUE("Bleu", ChatColor.DARK_BLUE, DyeColor.BLUE, 0x3C44AA),
    PURPLE("Violet", ChatColor.DARK_PURPLE, DyeColor.PURPLE, 0x8932B8),
    MAGENTA("Magenta", ChatColor.LIGHT_PURPLE, DyeColor.MAGENTA, 0xC74EBD),
    RED("Rouge", ChatColor.RED, DyeColor.RED, 0xB02E26),
    CYAN("Cyan", ChatColor.DARK_AQUA, DyeColor.CYAN, 0x169C9C),
    GRAY("Gris", ChatColor.GRAY, DyeColor.GRAY, 0x474F52);

    final String label;
    final ChatColor chat;
    final DyeColor dye;
    private final int rgb;

    PlayerColor(String label, ChatColor chat, DyeColor dye, int rgb) {
        this.label = label;
        this.chat = chat;
        this.dye = dye;
        this.rgb = rgb;
    }

    Color leather() {
        return Color.fromRGB(rgb);
    }
}
