package dev.wulnry.mossboss.config;

/**
 * Bir boss yapılandırma girişini temsil eden değişmez (immutable) veri nesnesi.
 *
 * <p>Java 21 record olarak tanımlanmıştır; tüm alanlar thread-safe şekilde okunabilir.
 * Bir örnek oluşturulduktan sonra asla değişmez; bu yüzden ConcurrentHashMap değerleri
 * olarak güvenle kullanılabilir.</p>
 *
 * @param id                  config'deki benzersiz tanımlayıcı (örn. "ancient_golem")
 * @param worldName           boss'un spawn olacağı dünya adı
 * @param x                   X koordinatı
 * @param y                   Y koordinatı
 * @param z                   Z koordinatı
 * @param yaw                 yön açısı
 * @param placeholderMythicId haritada sabit duran placeholder MythicMob tipi
 * @param realBossMythicId    Tılsım ile tetiklenince spawn olan gerçek boss tipi
 * @param respawnDelaySeconds boss öldükten sonra placeholder'ın geri gelme süresi (saniye)
 * @param spawnBroadcastMessage bu boss için özel duyuru mesajı; boşsa global mesaj kullanılır
 * @param modelId             ModelEngine model adı; boşsa model uygulanmaz
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
        String modelId
) {}
