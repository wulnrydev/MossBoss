package dev.wulnry.mossboss;

import dev.wulnry.mossboss.command.MossBossCommand;
import dev.wulnry.mossboss.config.ConfigManager;
import dev.wulnry.mossboss.hook.ModelEngineHook;
import dev.wulnry.mossboss.hook.MythicMobsHook;
import dev.wulnry.mossboss.listener.BossActiveListener;
import dev.wulnry.mossboss.listener.BossDeathListener;
import dev.wulnry.mossboss.listener.BossInteractListener;
import dev.wulnry.mossboss.manager.BossManager;
import dev.wulnry.mossboss.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MossBoss ana sınıfı.
 *
 * <p>BoxPvP sunucuları için tasarlanmış, Tılsım tabanlı Boss tetikleme sistemi.
 * Zorunlu bağımlılık: MythicMobs. Opsiyonel bağımlılık: ModelEngine.</p>
 *
 * @author wulnrydev
 */
public final class MossBoss extends JavaPlugin {

    private ConfigManager configManager;
    private BossManager bossManager;
    private ModelEngineHook modelEngineHook;

    @Override
    public void onEnable() {
        // 1) MythicMobs zorunlu bağımlılık kontrolü
        if (getServer().getPluginManager().getPlugin("MythicMobs") == null) {
            getLogger().severe("MythicMobs bulunamadı! MossBoss devre dışı bırakılıyor.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2) Statik yardımcıları ve hook'ları başlat
        MythicMobsHook.init(this);
        ItemUtil.init(this);
        this.modelEngineHook = new ModelEngineHook(this);

        // 3) Yapılandırmayı yükle
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        // 4) Boss yöneticisini oluştur
        this.bossManager = new BossManager(this, configManager, modelEngineHook);

        // 5) Placeholder mob'ları spawn et — MythicMobs'un tam başlaması için 1 tick bekle.
        //    Önce önceki oturumdan (crash sonrası temizlenememiş) orphan entity'leri sil.
        Bukkit.getScheduler().runTask(this, () -> {
            bossManager.cleanupOrphans();
            bossManager.spawnAllPlaceholders();
        });

        // 6) Olay dinleyicilerini kaydet
        getServer().getPluginManager().registerEvents(new BossInteractListener(bossManager), this);
        getServer().getPluginManager().registerEvents(new BossDeathListener(bossManager), this);
        getServer().getPluginManager().registerEvents(new BossActiveListener(bossManager), this);

        // 7) Komutu bağla
        PluginCommand cmd = getCommand("mossboss");
        if (cmd != null) {
            MossBossCommand executor = new MossBossCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("MossBoss etkinleştirildi. (yapımcı: wulnrydev)");
    }

    @Override
    public void onDisable() {
        if (bossManager != null) {
            bossManager.despawnAll();
        }
        getLogger().info("MossBoss devre dışı bırakıldı.");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public BossManager getBossManager() {
        return bossManager;
    }

    public ModelEngineHook getModelEngineHook() {
        return modelEngineHook;
    }
}
