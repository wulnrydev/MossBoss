package dev.wulnry.mossboss.config;

import dev.wulnry.mossboss.MossBoss;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config.yml} dosyasını okuyup bellekte tutar.
 *
 * <p>Değerler tek seferde okunur ve alanlarda saklanır; böylece her olayda
 * disk/konfig erişimi yapılmaz (sıfır-lag felsefesi). {@link #load()} metodu
 * ile istenildiği anda yeniden yüklenebilir.</p>
 */
public final class ConfigManager {

    private final MossBoss plugin;

    private String prefix;
    private String spawnBroadcastMessage;
    private String noTilsimMessage;
    private String alreadyActiveMessage;
    private String reloadSuccessMessage;
    private String usageMessage;
    private String unknownBossMessage;

    private Material tilsimMaterial;
    private String tilsimDisplayName;
    private List<String> tilsimLore;
    private int tilsimCustomModelData;
    private String tilsimPdcValue;

    /** Boss ID → BossEntry; ekleme sırasını korur. */
    private Map<String, BossEntry> bosses = Collections.emptyMap();

    public ConfigManager(MossBoss plugin) {
        this.plugin = plugin;
    }

    /**
     * Yapılandırmayı diskten (yeniden) yükler.
     */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        prefix = config.getString("prefix", "&#15FF08&lʙᴏxᴘᴠᴘ &8▸ ");

        spawnBroadcastMessage  = config.getString("messages.spawn-broadcast", "");
        noTilsimMessage        = config.getString("messages.no-tilsim", "");
        alreadyActiveMessage   = config.getString("messages.already-active", "");
        reloadSuccessMessage   = config.getString("messages.reload-success", "");
        usageMessage           = config.getString("messages.usage", "");
        unknownBossMessage     = config.getString("messages.unknown-boss", "");

        // Tılsım eşya ayarları
        String matName = config.getString("tilsim.material", "NETHER_STAR").toUpperCase();
        tilsimMaterial = parseMaterial(matName);
        tilsimDisplayName  = config.getString("tilsim.display-name", "&#ffd500&lTılsım");
        tilsimLore         = Collections.unmodifiableList(config.getStringList("tilsim.lore"));
        tilsimCustomModelData = config.getInt("tilsim.custom-model-data", -1);
        tilsimPdcValue = config.getString("tilsim.pdc-value", "mossboss_tilsim");

        // Boss listesini ayrıştır
        Map<String, BossEntry> parsed = new LinkedHashMap<>();
        for (Map<?, ?> map : config.getMapList("bosses")) {
            BossEntry entry = parseBossEntry(map);
            if (entry != null) {
                parsed.put(entry.id(), entry);
            }
        }
        bosses = Collections.unmodifiableMap(parsed);

        plugin.getLogger().info(bosses.size() + " boss girişi yüklendi.");
    }

    private BossEntry parseBossEntry(Map<?, ?> map) {
        String id = (String) map.get("id");
        if (id == null || id.isBlank()) {
            plugin.getLogger().warning("'id' alanı eksik olan bir boss girişi atlandı.");
            return null;
        }

        String world     = getString(map, "world", "world");
        double x         = toDouble(map.get("x"), 0.0);
        double y         = toDouble(map.get("y"), 64.0);
        double z         = toDouble(map.get("z"), 0.0);
        float  yaw       = (float) toDouble(map.get("yaw"), 0.0);
        String phId      = getString(map, "placeholder-mythic-id", "");
        String realId    = getString(map, "real-boss-mythic-id", "");
        int    delay     = toInt(map.get("respawn-delay-seconds"), 3600);
        String broadcast = getString(map, "spawn-broadcast", "");
        String modelId   = getString(map, "model-id", "");

        if (phId.isBlank() || realId.isBlank()) {
            plugin.getLogger().warning("'" + id + "' boss girişi için mythic-id alanları eksik; atlandı.");
            return null;
        }

        return new BossEntry(id, world, x, y, z, yaw, phId, realId, delay, broadcast, modelId);
    }

    private static double toDouble(Object val, double def) {
        return val instanceof Number n ? n.doubleValue() : def;
    }

    private static int toInt(Object val, int def) {
        return val instanceof Number n ? n.intValue() : def;
    }

    private static String getString(Map<?, ?> map, String key, String def) {
        Object val = map.get(key);
        return val instanceof String s ? s : def;
    }

    private Material parseMaterial(String name) {
        Material mat = Material.matchMaterial(name);
        if (mat == null) {
            plugin.getLogger().warning("Geçersiz tılsım materyali: '" + name + "'. NETHER_STAR kullanılıyor.");
            return Material.NETHER_STAR;
        }
        return mat;
    }

    // ── Getter'lar ───────────────────────────────────────────

    public String getPrefix()                { return prefix; }
    public String getSpawnBroadcastMessage() { return spawnBroadcastMessage; }
    public String getNoTilsimMessage()       { return noTilsimMessage; }
    public String getAlreadyActiveMessage()  { return alreadyActiveMessage; }
    public String getReloadSuccessMessage()  { return reloadSuccessMessage; }
    public String getUsageMessage()          { return usageMessage; }
    public String getUnknownBossMessage()    { return unknownBossMessage; }
    public Material getTilsimMaterial()      { return tilsimMaterial; }
    public String getTilsimDisplayName()     { return tilsimDisplayName; }
    public List<String> getTilsimLore()      { return tilsimLore; }
    public int getTilsimCustomModelData()    { return tilsimCustomModelData; }
    public String getTilsimPdcValue()        { return tilsimPdcValue; }
    public Map<String, BossEntry> getBosses(){ return bosses; }
}
