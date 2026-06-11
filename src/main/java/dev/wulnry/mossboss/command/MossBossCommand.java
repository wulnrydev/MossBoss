package dev.wulnry.mossboss.command;

import dev.wulnry.mossboss.MossBoss;
import dev.wulnry.mossboss.manager.BossManager;
import dev.wulnry.mossboss.util.ColorUtil;
import dev.wulnry.mossboss.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /mossboss} (takma ad: {@code /mb}) yönetim komutu.
 *
 * <p>Alt komutlar: {@code reload}, {@code status}, {@code forcespawn <id>}</p>
 * <p>Gerekli izin: {@code mossboss.admin}</p>
 */
public final class MossBossCommand implements CommandExecutor, TabCompleter {

    private final MossBoss plugin;

    public MossBossCommand(MossBoss plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mossboss.admin")) {
            sender.sendMessage(ColorUtil.component(
                    plugin.getConfigManager().getPrefix() +
                    "&cBu komutu kullanmak için yetkiniz yok."));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "forcespawn" -> {
                if (args.length < 2) {
                    sendUsage(sender);
                } else {
                    handleForcespawn(sender, args[1]);
                }
            }
            case "give" -> handleGive(sender, args);
            default -> sendUsage(sender);
        }

        return true;
    }

    private void handleReload(CommandSender sender) {
        plugin.getBossManager().despawnAll();
        plugin.getConfigManager().load();
        // onEnable'daki gibi 1 tick bekle — MythicMobs removal queue'su işlensin
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getBossManager().spawnAllPlaceholders();
            plugin.getBossManager().startWatchdog();
        });

        sender.sendMessage(ColorUtil.component(
                replacePrefixPlaceholder(plugin.getConfigManager().getReloadSuccessMessage())));
    }

    private void handleStatus(CommandSender sender) {
        String report = plugin.getBossManager().buildStatusReport();
        String header = plugin.getConfigManager().getPrefix() + "&#00D420ʙᴏss ᴅᴜʀᴜᴍ ʀᴀᴘᴏʀᴜ&8:";
        sender.sendMessage(ColorUtil.component(header + report));
    }

    private void handleForcespawn(CommandSender sender, String bossId) {
        Player player = sender instanceof Player p ? p : null;

        if (!plugin.getConfigManager().getBosses().containsKey(bossId)) {
            String msg = plugin.getConfigManager().getUnknownBossMessage()
                    .replace("%prefix%", plugin.getConfigManager().getPrefix())
                    .replace("%id%", bossId);
            sender.sendMessage(ColorUtil.component(msg));
            return;
        }

        BossManager.ForcespawnResult result = plugin.getBossManager().forcespawn(bossId, player);
        switch (result) {
            case SUCCESS -> sender.sendMessage(ColorUtil.component(
                    plugin.getConfigManager().getPrefix() +
                    "&#00D420ʙᴏss ᴛᴇᴛɪᴋʟᴇɴᴅɪ&7: &#ffd500" + bossId));
            case ALREADY_ACTIVE -> sender.sendMessage(ColorUtil.component(
                    plugin.getConfigManager().getPrefix() +
                    "&7&#ffd500" + bossId + " &7ʙᴏss'ᴜ ᴢᴀᴛᴇɴ ᴀᴋᴛɪꜰ."));
            case SPAWN_FAILED -> sender.sendMessage(ColorUtil.component(
                    plugin.getConfigManager().getPrefix() +
                    "&cSpawn başarısız &7— dünya yüklü değil veya MythicMobs ID hatalı: &#ffd500" + bossId));
        }
    }

    private void handleGive(CommandSender sender, String[] args) {
        Player target;
        if (args.length < 2) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ColorUtil.component(
                        plugin.getConfigManager().getPrefix() +
                        "&cKonsoldan kullanım: /mb give <oyuncu>"));
                return;
            }
            target = p;
        } else {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ColorUtil.component(
                        plugin.getConfigManager().getPrefix() +
                        "&c" + args[1] + " &7adlı oyuncu çevrimiçi değil."));
                return;
            }
        }

        ItemStack tilsim = ItemUtil.createTilsim(plugin.getConfigManager());
        target.getInventory().addItem(tilsim).forEach((slot, overflow) ->
                target.getWorld().dropItemNaturally(target.getLocation(), overflow));

        sender.sendMessage(ColorUtil.component(
                plugin.getConfigManager().getPrefix() +
                "&#00D420Tılsım &7verildi: &#ffd500" + target.getName()));
        if (!target.equals(sender)) {
            target.sendMessage(ColorUtil.component(
                    plugin.getConfigManager().getPrefix() +
                    "&#ffd500Tılsım &7aldınız."));
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ColorUtil.component(
                replacePrefixPlaceholder(plugin.getConfigManager().getUsageMessage())));
    }

    private String replacePrefixPlaceholder(String msg) {
        return msg.replace("%prefix%", plugin.getConfigManager().getPrefix());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mossboss.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filterStartsWith(List.of("reload", "status", "forcespawn", "give"), args[0]);
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("forcespawn")) {
                return filterStartsWith(
                        new ArrayList<>(plugin.getConfigManager().getBosses().keySet()),
                        args[1]);
            }
            if (args[0].equalsIgnoreCase("give")) {
                return filterStartsWith(
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .toList(),
                        args[1]);
            }
        }

        return List.of();
    }

    private List<String> filterStartsWith(List<String> source, String prefix) {
        return source.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                .toList();
    }
}
