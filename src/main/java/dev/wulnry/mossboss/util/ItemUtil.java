package dev.wulnry.mossboss.util;

import dev.wulnry.mossboss.config.ConfigManager;
import dev.wulnry.mossboss.util.ColorUtil;
import net.kyori.adventure.text.Component;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Tılsım eşyası için yardımcı metotlar.
 */
public final class ItemUtil {

    private static NamespacedKey tilsimKey;

    /** Eklenti başlatılırken PDC anahtarını plugin instance'ı ile oluşturur. Mutlaka onEnable'da çağrılmalı. */
    public static void init(JavaPlugin plugin) {
        tilsimKey = new NamespacedKey(plugin, "tilsim_type");
    }

    private ItemUtil() {
        // Yardımcı sınıf; örneklenemez.
    }

    /**
     * Verilen eşyanın geçerli bir Tılsım olup olmadığını kontrol eder.
     *
     * <p>Kontrol sırası:</p>
     * <ol>
     *   <li>Null/AIR kontrolü</li>
     *   <li>Material eşleşmesi</li>
     *   <li>PDC değeri kontrolü</li>
     *   <li>CustomModelData kontrolü (yalnızca config'de etkinse)</li>
     * </ol>
     *
     * @param item   kontrol edilecek eşya
     * @param config yapılandırma
     * @return {@code true} ise geçerli Tılsım
     */
    public static boolean isTilsim(ItemStack item, ConfigManager config) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        if (item.getType() != config.getTilsimMaterial()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        // PDC kontrolü (en güvenilir yöntem; ad değiştirilse bile çalışır)
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(tilsimKey, PersistentDataType.STRING)) {
            return false;
        }

        String storedValue = pdc.get(tilsimKey, PersistentDataType.STRING);
        if (!config.getTilsimPdcValue().equals(storedValue)) {
            return false;
        }

        // CustomModelData ikincil kontrol (config'de -1 ise atlanır)
        int requiredCMD = config.getTilsimCustomModelData();
        if (requiredCMD != -1) {
            if (!meta.hasCustomModelData() || meta.getCustomModelData() != requiredCMD) {
                return false;
            }
        }

        return true;
    }

    /**
     * Config'e göre PDC, material ve custom model data içeren hazır Tılsım eşyası oluşturur.
     *
     * @param config yapılandırma
     * @return oluşturulan ItemStack
     */
    public static ItemStack createTilsim(ConfigManager config) {
        ItemStack item = new ItemStack(config.getTilsimMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(ColorUtil.component(config.getTilsimDisplayName()));

        List<String> loreLines = config.getTilsimLore();
        if (!loreLines.isEmpty()) {
            meta.lore(loreLines.stream().map(ColorUtil::component).toList());
        }

        meta.getPersistentDataContainer().set(tilsimKey, PersistentDataType.STRING, config.getTilsimPdcValue());

        int cmd = config.getTilsimCustomModelData();
        if (cmd != -1) {
            meta.setCustomModelData(cmd);
        }

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Oyuncunun ana elindeki eşyadan bir adet düşürür.
     * Miktar 0'a düşerse eşyayı kaldırır.
     *
     * @param player eşyası tüketilecek oyuncu
     */
    public static void consumeOne(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            return;
        }
        held.setAmount(held.getAmount() - 1);
    }
}
