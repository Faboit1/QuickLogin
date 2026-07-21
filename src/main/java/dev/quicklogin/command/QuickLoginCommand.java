package dev.quicklogin.command;

import dev.quicklogin.QuickLoginPlugin;
import dev.quicklogin.mojang.MojangApiService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/**
 * {@code /quicklogin <reload|status|reset <player>|check <name>>}.
 */
public final class QuickLoginCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "§6[QuickLogin] §r";

    private final QuickLoginPlugin plugin;

    public QuickLoginCommand(QuickLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(PREFIX + "§7Usage: §f/" + label + " <reload|status|reset|check>");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(PREFIX + "§aConfiguration reloaded.");
            }
            case "status" -> {
                sender.sendMessage(PREFIX + "§7QuickLogin §fv" + plugin.getDescription().getVersion());
                sender.sendMessage("§7 • Premium login: " + onOff(plugin.config().premiumEnabled()));
                sender.sendMessage("§7 • Proxy mode: " + onOff(plugin.isProxyMode())
                        + (plugin.isProxyMode() ? " §7(Mojang API name lookup)" : " §7(PacketEvents handshake)"));
                sender.sendMessage("§7 • Floodgate login: " + onOff(plugin.config().floodgateEnabled()
                        && plugin.hasFloodgate()));
                sender.sendMessage("§7 • AuthMe hooked: " + onOff(true));
            }
            case "check" -> {
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + "§cUsage: /" + label + " check <username>");
                    return true;
                }
                String target = args[1];
                MojangApiService mojang = plugin.getMojang();
                if (mojang == null) {
                    sender.sendMessage(PREFIX + "§cMojang API service not available.");
                    return true;
                }
                sender.sendMessage(PREFIX + "§7Checking Mojang API for '§f" + target + "§7'...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    MojangApiService.Result result = mojang.lookup(target);
                    switch (result) {
                        case PREMIUM -> sender.sendMessage(PREFIX + "§a'" + target + "' is a PREMIUM (paid) Mojang account.");
                        case CRACKED -> sender.sendMessage(PREFIX + "§e'" + target + "' is NOT a premium account (cracked/free).");
                        case UNKNOWN -> sender.sendMessage(PREFIX + "§c'" + target + "' — Mojang API error! Check server console for details.");
                    }
                });
            }
            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + "§cUsage: /" + label + " reset <player>");
                    return true;
                }
                String target = args[1];
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean removed = plugin.resetAccount(target);
                    sender.sendMessage(PREFIX + (removed
                            ? "§aReset stored credentials for §f" + target + "§a. A new password will be generated on next join."
                            : "§eNo stored record found for §f" + target + "§e."));
                });
            }
            default -> sender.sendMessage(PREFIX + "§cUnknown subcommand. Use reload, status, reset or check.");
        }
        return true;
    }

    private static String onOff(boolean value) {
        return value ? "§aenabled" : "§cdisabled";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "status", "reset", "check");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).toList();
        }
        return List.of();
    }
}
