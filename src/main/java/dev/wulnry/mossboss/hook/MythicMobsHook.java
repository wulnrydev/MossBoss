package dev.wulnry.mossboss.hook;

import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Optional;
import java.util.UUID;

/**
 * MythicMobs API'sini saran facade sınıfı.
 *
 * <p>Eklentinin geri kalanı bu sınıf aracılığıyla MythicMobs ile iletişim kurar;
 * böylece API değişikliklerinde yalnızca bu sınıf güncellenmesi gerekir.</p>
 */
public final class MythicMobsHook {

    private MythicMobsHook() {
        // Yardımcı sınıf; örneklenemez.
    }

    /**
     * Belirtilen MythicMob tipini verilen konuma spawn eder.
     *
     * @param mythicId MythicMobs konfigürasyonunda tanımlı mob tipi
     * @param location spawn konumu (Bukkit Location)
     * @return oluşturulan Bukkit entity; hata durumunda {@code null}
     */
    public static Entity spawnMob(String mythicId, Location location) {
        try {
            ActiveMob activeMob = MythicBukkit.inst()
                    .getMobManager()
                    .spawnMob(mythicId, BukkitAdapter.adapt(location), 1);
            if (activeMob == null || activeMob.getEntity() == null) {
                return null;
            }
            return activeMob.getEntity().getBukkitEntity();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("MossBoss")
                    .warning("MythicMobs spawn hatası [" + mythicId + "]: " + e.getMessage());
            return null;
        }
    }

    /**
     * UUID'sine göre aktif bir MythicMob varlığını döndürür.
     *
     * @param uuid varlık UUID'si
     * @return aktif mob; bulunamazsa boş Optional
     */
    public static Optional<ActiveMob> getActiveMob(UUID uuid) {
        try {
            return MythicBukkit.inst().getMobManager().getActiveMob(uuid);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * UUID ile eşleşen MythicMob'u sunucudan kaldırır.
     *
     * @param uuid kaldırılacak mob'un UUID'si
     */
    public static void removeMob(UUID uuid) {
        getActiveMob(uuid).ifPresent(ActiveMob::remove);
    }

    /**
     * Verilen varlığın aktif bir MythicMob olup olmadığını kontrol eder.
     *
     * @param entity kontrol edilecek varlık
     * @return {@code true} ise MythicMob
     */
    public static boolean isMythicMob(Entity entity) {
        try {
            return MythicBukkit.inst().getMobManager().isActiveMob(entity.getUniqueId());
        } catch (Exception e) {
            return false;
        }
    }
}
