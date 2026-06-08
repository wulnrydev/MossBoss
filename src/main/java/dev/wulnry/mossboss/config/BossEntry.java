package dev.wulnry.mossboss.config;

import java.util.Map;

/**
 * Bir boss yapılandırma girişini temsil eden değişmez (immutable) veri nesnesi.
 *
 * @param id                    config'deki benzersiz tanımlayıcı
 * @param worldName             boss'un spawn olacağı dünya adı
 * @param x                     X koordinatı
 * @param y                     Y koordinatı
 * @param z                     Z koordinatı
 * @param yaw                   yön açısı
 * @param placeholderMythicId   haritada sabit duran placeholder MythicMob tipi
 * @param realBossMythicId      Tılsım ile tetiklenince spawn olan gerçek boss tipi
 * @param respawnDelaySeconds   boss öldükten sonra placeholder'ın geri gelme süresi (saniye)
 * @param spawnBroadcastMessage bu boss için özel duyuru mesajı; boşsa global mesaj kullanılır
 * @param modelId               ModelEngine model adı; boşsa model uygulanmaz
 * @param bossBarTitle          bossbar başlığı ({health}, {max_health} desteklenir); boşsa bossbar yok
 * @param bossBarColor          bossbar rengi: PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE
 * @param bossBarStyle          bossbar stili: PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20
 * @param despawnAfterSeconds   boss bu süre içinde öldürülmezse otomatik yok olur (-1 = sınırsız)
 * @param rewards               sıralama (1,2,3…) → RewardEntry (isim + komutlar)
 */
public record BossEntry(
        String id,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        String placeholderMythicId,
        String realBossMythicId,
        int respawnDelaySeconds,
        String spawnBroadcastMessage,
        String modelId,
        String bossBarTitle,
        String bossBarColor,
        String bossBarStyle,
        int despawnAfterSeconds,
        Map<Integer, RewardEntry> rewards
) {}
