package dev.wulnry.mossboss.manager;

import dev.wulnry.mossboss.MossBoss;
import dev.wulnry.mossboss.config.BossEntry;
import dev.wulnry.mossboss.config.ConfigManager;
import dev.wulnry.mossboss.config.RewardEntry;
import dev.wulnry.mossboss.hook.MythicMobsHook;
import dev.wulnry.mossboss.hook.ModelEngineHook;
import dev.wulnry.mossboss.util.ColorUtil;
import dev.wulnry.mossboss.util.ItemUtil;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BossManager {

    public enum ForcespawnResult { SUCCESS, ALREADY_ACTIVE, SPAWN_FAILED }

    /** Plugin'in spawn ettiği her entity'ye eklenen etiket; açılışta orphan temizliğinde kullanılır. */
    private static final String ENTITY_TAG = "mossboss_entity";

    private final MossBoss plugin;
    private final ConfigManager config;
    private final ModelEngineHook modelEngineHook;

    private final Map<String, UUID> placeholderUuids = new ConcurrentHashMap<>();
    private final Map<String, UUID> realBossUuids    = new ConcurrentHashMap<>();
    private final Map<UUID, String> entityToBossId   = new ConcurrentHashMap<>();
    private final Map<String, Long> respawnAt        = new ConcurrentHashMap<>();
    private BukkitTask watchdogTask;

    // Aktif boss'a ait bossbar, hasar tablosu ve otomatik despawn zamanlayıcısı
    private final Map<String, BossBar>              activeBossBars      = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Double>>    damageTracker       = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask>           despawnTasks        = new ConcurrentHashMap<>();
    /** Aynı tick'te birden fazla bossbar güncellemesi planlanmasını önler. */
    private final Set<String>                       pendingHealthUpdates = ConcurrentHashMap.newKeySet();

    public BossManager(MossBoss plugin, ConfigManager config, ModelEngineHook modelEngineHook) {
        this.plugin = plugin;
        this.config = config;
        this.modelEngineHook = modelEngineHook;
    }

    // ── Toplu işlemler ───────────────────────────────────────

    public void spawnAllPlaceholders() {
        for (BossEntry entry : config.getBosses().values()) {
            spawnPlaceholder(entry);
        }
    }

    /**
     * Önceki oturumdan (örn. crash sonrası onDisable çalışmadığında) kalan, plugin'e ait tüm
     * entity'leri yüklü dünyalardan temizler. Placeholder'lar kalıcı (persistent) olduğundan
     * bu yapılmazsa her açılışta üst üste birikirler.
     */
    public void cleanupOrphans() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!e.getScoreboardTags().contains(ENTITY_TAG)) continue;
                modelEngineHook.removeModel(e);
                MythicMobsHook.removeMob(e.getUniqueId());
                if (e.isValid()) e.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info(removed + " orphan boss entity temizlendi (önceki oturumdan kalan).");
        }
    }

    public void despawnAll() {
        stopWatchdog();
        despawnTasks.values().forEach(BukkitTask::cancel);
        despawnTasks.clear();

        pendingHealthUpdates.clear();
        hideBossBars();
        activeBossBars.clear();
        damageTracker.clear();

        // Entity'leri silmeden ÖNCE reverse-lookup'u ve UUID haritalarını temizle.
        // removeMob senkron MythicMobDeathEvent tetikler; haritalar önceden boşaltıldığı için
        // handleBossDeath hemen return eder ve reload sırasında orphan respawn task oluşmaz.
        final List<UUID> phList;
        final List<UUID> bossList;
        synchronized (this) {
            phList   = new ArrayList<>(placeholderUuids.values());
            bossList = new ArrayList<>(realBossUuids.values());
            placeholderUuids.clear();
            realBossUuids.clear();
            entityToBossId.clear();
        }

        for (UUID uuid : phList) {
            removeEntitySilently(uuid);
        }
        for (UUID uuid : bossList) {
            MythicMobsHook.removeMob(uuid);
        }

        respawnAt.clear();
    }

    // ── Placeholder spawn / despawn ──────────────────────────

    public void spawnPlaceholder(BossEntry entry) {
        World world = Bukkit.getWorld(entry.worldName());
        if (world == null) {
            plugin.getLogger().warning(
                    "'" + entry.id() + "' için dünya bulunamadı: '" + entry.worldName() + "'");
            return;
        }

        Location loc = new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), 0f);
        Entity entity = MythicMobsHook.spawnMob(entry.placeholderMythicId(), loc);
        if (entity == null) {
            plugin.getLogger().warning(
                    "'" + entry.id() + "' için placeholder spawn başarısız: '" + entry.placeholderMythicId() + "'");
            return;
        }

        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setPersistent(true);          // Minecraft doğal despawn'ını engelle
        entity.addScoreboardTag(ENTITY_TAG); // Açılışta orphan temizliği için işaretle
        if (entity instanceof Mob mob) {
            mob.setAI(false);
            mob.setRemoveWhenFarAway(false); // Oyuncu uzaklaşınca silinmeyi engelle
        }

        UUID uuid = entity.getUniqueId();
        synchronized (this) {
            placeholderUuids.put(entry.id(), uuid);
            entityToBossId.put(uuid, entry.id());
        }
        respawnAt.remove(entry.id());

        if (!entry.modelId().isBlank()) {
            modelEngineHook.applyModel(entity, entry.modelId());
        }
    }

    // ── Event işleyicileri ───────────────────────────────────

    public boolean handleInteract(Player player, Entity entity) {
        String bossId = entityToBossId.get(entity.getUniqueId());
        if (bossId == null) {
            return false;
        }

        BossEntry entry = config.getBosses().get(bossId);
        if (entry == null) {
            entityToBossId.remove(entity.getUniqueId());
            return false;
        }

        if (realBossUuids.containsKey(bossId)) {
            // Gerçek boss entitysine tıklanıyorsa (dövüş sırasında) mesaj gösterme, olayı geçir
            if (entity.getUniqueId().equals(realBossUuids.get(bossId))) {
                return false;
            }
            player.sendMessage(ColorUtil.component(
                    replacePrefixPlaceholder(config.getAlreadyActiveMessage())));
            return true;
        }

        if (!placeholderUuids.containsKey(bossId)) {
            return false;
        }

        if (!ItemUtil.isTilsim(player.getInventory().getItemInMainHand(), config)) {
            player.sendMessage(ColorUtil.component(
                    replacePrefixPlaceholder(config.getNoTilsimMessage())));
            return true;
        }

        final UUID phUuid;
        synchronized (this) {
            phUuid = placeholderUuids.remove(bossId);
            if (phUuid != null) entityToBossId.remove(phUuid);
        }
        // Placeholder eş zamanlı olarak başka bir akış tarafından zaten tüketildiyse dur.
        if (phUuid == null) return true;
        modelEngineHook.removeModel(entity);
        MythicMobsHook.removeMob(phUuid);

        World world = Bukkit.getWorld(entry.worldName());
        if (world == null) {
            spawnPlaceholder(entry);
            return true;
        }
        Location loc = new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), 0f);
        Entity boss = MythicMobsHook.spawnMob(entry.realBossMythicId(), loc);
        if (boss == null) {
            spawnPlaceholder(entry);
            return true;
        }

        UUID bossUuid = boss.getUniqueId();
        synchronized (this) {
            realBossUuids.put(bossId, bossUuid);
            entityToBossId.put(bossUuid, bossId);
        }

        ItemUtil.consumeOne(player);
        activateBoss(bossId, boss, entry);

        String raw = entry.spawnBroadcastMessage() != null && !entry.spawnBroadcastMessage().isBlank()
                ? entry.spawnBroadcastMessage()
                : config.getSpawnBroadcastMessage();
        String message = raw
                .replace("%prefix%", config.getPrefix())
                .replace("%player%", player.getName())
                .replace("%boss_name%", entry.displayName());
        Bukkit.broadcast(ColorUtil.component(message));

        return true;
    }

    public void handleBossDeath(UUID deadUuid) {
        final String bossId;
        final UUID tracked;
        synchronized (this) {
            bossId = entityToBossId.remove(deadUuid);
            if (bossId == null) return;
            tracked = realBossUuids.remove(bossId);
        }
        if (tracked == null) {
            // Placeholder dışarıdan öldürüldü — stale entry temizle ve 1 tick sonra yeniden spawn et
            synchronized (this) {
                UUID ph = placeholderUuids.get(bossId);
                if (deadUuid.equals(ph)) placeholderUuids.remove(bossId);
            }
            if (!plugin.isEnabled()) return;
            if (config.getBosses().get(bossId) == null) return;
            respawnAt.put(bossId, System.currentTimeMillis()); // watchdog hemen yakalar
            return;
        }
        if (!tracked.equals(deadUuid)) {
            return;
        }

        BossEntry entry = config.getBosses().get(bossId);
        if (entry == null) {
            deactivateBoss(bossId, null);
            return;
        }

        deactivateBoss(bossId, entry);
        scheduleRespawn(bossId, entry.respawnDelaySeconds());
    }

    private void scheduleRespawn(String bossId, int delaySeconds) {
        respawnAt.put(bossId, System.currentTimeMillis() + (long) delaySeconds * 1000L);
    }

    public void startWatchdog() {
        stopWatchdog();
        watchdogTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWatchdog, 20L, 20L);
    }

    public void stopWatchdog() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
    }

    private void tickWatchdog() {
        long now = System.currentTimeMillis();
        for (BossEntry entry : config.getBosses().values()) {
            String id = entry.id();

            // Placeholder UUID var ama entity gerçekten dünyada mı?
            UUID phUuid = placeholderUuids.get(id);
            if (phUuid != null) {
                Entity ph = Bukkit.getEntity(phUuid);
                if (ph != null && ph.isValid()) continue;
                // Entity dışarıdan kaldırılmış (event tetiklemeden) — stale UUID temizle
                synchronized (this) {
                    if (phUuid.equals(placeholderUuids.get(id))) {
                        placeholderUuids.remove(id);
                        entityToBossId.remove(phUuid);
                    }
                }
            }

            if (realBossUuids.containsKey(id)) continue;
            Long rt = respawnAt.get(id);
            if (rt != null && rt > now) continue;
            spawnPlaceholder(entry);
        }
    }

    /**
     * despawn-after-seconds süresi dolduğunda çağrılır: boss'u ölüm/ödül tetiklemeden
     * sessizce kaldırır ve placeholder respawn'ını zamanlar.
     */
    private void despawnExpired(String bossId) {
        final UUID bossUuid;
        synchronized (this) {
            bossUuid = realBossUuids.remove(bossId);
            if (bossUuid == null) return;          // bu arada öldürülmüş/temizlenmiş
            entityToBossId.remove(bossUuid);
        }
        // Bossbar'ı kapat ama ödül DAĞITMA (boss ölmedi, süresi doldu).
        deactivateBoss(bossId, null);
        // Maps temizlendiği için removeMob'un tetiklediği MythicMobDeathEvent yok sayılır.
        removeEntitySilently(bossUuid);

        BossEntry entry = config.getBosses().get(bossId);
        if (entry != null) scheduleRespawn(bossId, entry.respawnDelaySeconds());
    }

    // ── Admin / komut yardımcıları ────────────────────────────

    public ForcespawnResult forcespawn(String bossId, @Nullable Player sender) {
        BossEntry entry = config.getBosses().get(bossId);
        if (entry == null) {
            return ForcespawnResult.SPAWN_FAILED;
        }

        if (realBossUuids.containsKey(bossId)) {
            return ForcespawnResult.ALREADY_ACTIVE;
        }

        final UUID phUuid;
        synchronized (this) {
            phUuid = placeholderUuids.remove(bossId);
            if (phUuid != null) entityToBossId.remove(phUuid);
        }
        if (phUuid != null) {
            Entity phEntity = Bukkit.getEntity(phUuid);
            if (phEntity != null) {
                modelEngineHook.removeModel(phEntity);
            }
            MythicMobsHook.removeMob(phUuid);
        }

        World world = Bukkit.getWorld(entry.worldName());
        if (world == null) {
            spawnPlaceholder(entry);
            return ForcespawnResult.SPAWN_FAILED;
        }
        Location loc = new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), 0f);
        Entity boss = MythicMobsHook.spawnMob(entry.realBossMythicId(), loc);
        if (boss == null) {
            spawnPlaceholder(entry);
            return ForcespawnResult.SPAWN_FAILED;
        }

        UUID bossUuid = boss.getUniqueId();
        synchronized (this) {
            realBossUuids.put(bossId, bossUuid);
            entityToBossId.put(bossUuid, bossId);
        }

        activateBoss(bossId, boss, entry);

        String raw = entry.spawnBroadcastMessage() != null && !entry.spawnBroadcastMessage().isBlank()
                ? entry.spawnBroadcastMessage()
                : config.getSpawnBroadcastMessage();
        String message = raw
                .replace("%prefix%", config.getPrefix())
                .replace("%player%", sender != null ? sender.getName() : "Konsol")
                .replace("%boss_name%", entry.displayName());
        Bukkit.broadcast(ColorUtil.component(message));

        return ForcespawnResult.SUCCESS;
    }

    public String buildStatusReport() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();

        for (BossEntry entry : config.getBosses().values()) {
            sb.append("\n&#ffd500").append(entry.id()).append(" &8▸ ");
            if (realBossUuids.containsKey(entry.id())) {
                sb.append("&#00D420ᴀᴋᴛɪꜰ &7(gerçek boss canlı)");
            } else if (placeholderUuids.containsKey(entry.id())) {
                sb.append("&7ʙᴇᴋʟɪʏᴏʀ &8(placeholder aktif)");
            } else {
                Long rt = respawnAt.get(entry.id());
                if (rt != null && rt > now) {
                    long secs = (rt - now) / 1000;
                    sb.append("&#ffd500ʏᴇɴɪᴅᴇɴ ᴅᴏɢᴜʏᴏʀ &7(").append(secs).append("s)");
                } else {
                    sb.append("&cʙɪʟɪɴᴍɪʏᴏʀ");
                }
            }
        }

        return sb.length() == 0 ? "&7Hiç boss yapılandırması bulunamadı." : sb.toString();
    }

    // ── Bossbar / hasar / ödül yardımcıları ─────────────────────

    private void activateBoss(String bossId, Entity boss, BossEntry entry) {
        boss.setPersistent(true);
        if (boss instanceof Mob mob) mob.setRemoveWhenFarAway(false);
        boss.addScoreboardTag(ENTITY_TAG);   // Açılışta orphan temizliği için işaretle

        damageTracker.put(bossId, new ConcurrentHashMap<>());

        if (!entry.bossBarTitle().isBlank()) {
            BossBar bar = BossBar.bossBar(
                    ColorUtil.component(buildBarTitle(entry.bossBarTitle(), boss)),
                    1.0f,
                    parseBossBarColor(entry.bossBarColor()),
                    parseBossBarOverlay(entry.bossBarStyle()));
            activeBossBars.put(bossId, bar);
            Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(bar));
        }

        int despawnSecs = entry.despawnAfterSeconds();
        if (despawnSecs > 0) {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                despawnTasks.remove(bossId);
                despawnExpired(bossId);   // ölüm/ödül tetiklemeden sessizce kaldır
            }, (long) despawnSecs * 20L);
            despawnTasks.put(bossId, task);
        }
    }

    private void deactivateBoss(String bossId, @Nullable BossEntry entry) {
        BukkitTask dt = despawnTasks.remove(bossId);
        if (dt != null) dt.cancel();

        pendingHealthUpdates.remove(bossId);

        BossBar bar = activeBossBars.remove(bossId);
        if (bar != null) {
            Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        }

        if (entry != null) distributeRewards(bossId, entry);
        damageTracker.remove(bossId);
    }

    private void distributeRewards(String bossId, BossEntry entry) {
        if (!plugin.isEnabled()) return;   // shutdown/reload sırasında görev zamanlama yasak
        Map<UUID, Double> dmg = damageTracker.get(bossId);

        // Snapshot — async'e geçmeden önce mutable map'ten kopyala
        final List<Map.Entry<UUID, Double>> snapshot = (dmg != null && !dmg.isEmpty())
                ? dmg.entrySet().stream()
                      .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                      .toList()
                : List.of();

        final Map<Integer, RewardEntry> rewardsSnap  = entry.rewards();
        final String bossDisplay  = entry.displayName();
        final String headerTpl    = config.getKillBroadcastHeader();
        final String rankTpl      = config.getKillBroadcastRank();
        final String prefix       = config.getPrefix();
        // En az 3 satır veya yapılandırılmış ödül sayısı kadar sıralama yayınla, oyuncu sayısını geçme
        final int    maxRank      = Math.min(snapshot.size(), Math.max(rewardsSnap.size(), 3));

        // Online oyuncu isimlerini main thread'de topla — Bukkit.getPlayer async'te thread-safe değil
        final Map<UUID, String> onlineNames = new HashMap<>();
        for (Map.Entry<UUID, Double> e : snapshot) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) onlineNames.put(e.getKey(), p.getName());
        }

        // Disk I/O (getOfflinePlayer) async'te yap
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            record RankLine(int rank, String playerName, double damage, @Nullable RewardEntry reward) {}
            List<RankLine> lines = new ArrayList<>(maxRank);

            for (int rank = 1; rank <= maxRank && rank <= snapshot.size(); rank++) {
                UUID   winnerId = snapshot.get(rank - 1).getKey();
                double totalDmg = snapshot.get(rank - 1).getValue();

                String playerName = onlineNames.get(winnerId);
                if (playerName == null) {
                    // Offline oyuncu — disk I/O burada güvenli (async thread)
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(winnerId);
                    playerName = offline.getName();
                    if (playerName == null) continue;
                }
                lines.add(new RankLine(rank, playerName, totalDmg, rewardsSnap.get(rank)));
            }

            // Sunucu kapanıyorsa main-thread görevini zamanlamaya çalışma (IllegalPluginAccessException)
            if (!plugin.isEnabled()) return;

            // Komut dispatch ve broadcast ana thread'de yap
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Ödül komutlarını çalıştır
                for (RankLine line : lines) {
                    if (line.reward() == null) continue;
                    for (String cmd : line.reward().commands()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                cmd.replace("{player}", line.playerName()));
                    }
                }

                // Kill broadcast
                if (!headerTpl.isBlank()) {
                    Bukkit.broadcast(ColorUtil.component(
                            headerTpl.replace("%prefix%", prefix)
                                     .replace("%boss_name%", bossDisplay)));
                }
                if (!rankTpl.isBlank()) {
                    for (RankLine line : lines) {
                        String rewardName = (line.reward() != null && !line.reward().name().isBlank())
                                ? line.reward().name() : "-";
                        Bukkit.broadcast(ColorUtil.component(rankTpl
                                .replace("%prefix%",    prefix)
                                .replace("%boss_name%", bossDisplay)
                                .replace("%rank%",      String.valueOf(line.rank()))
                                .replace("%player%",    line.playerName())
                                .replace("%damage%",    String.format("%.0f", line.damage()))
                                .replace("%reward%",    rewardName)));
                    }
                }
            });
        });
    }

    public void handleBossDamage(UUID entityUuid, UUID attackerUuid, double damage) {
        String bossId = entityToBossId.get(entityUuid);
        if (bossId == null || !entityUuid.equals(realBossUuids.get(bossId))) return;
        damageTracker.computeIfAbsent(bossId, k -> new ConcurrentHashMap<>())
                .merge(attackerUuid, damage, Double::sum);
    }

    public void handleBossHealthUpdate(UUID entityUuid) {
        String bossId = entityToBossId.get(entityUuid);
        if (bossId == null || !entityUuid.equals(realBossUuids.get(bossId))) return;
        if (activeBossBars.get(bossId) == null) return;

        // Aynı tick içinde bu boss için zaten bir güncelleme planlandıysa tekrar planlamaya gerek yok
        if (!pendingHealthUpdates.add(bossId)) return;

        // Tüm entity/attribute okumalarını bir sonraki tick'e ertele (event handler'ı bloklamaz)
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingHealthUpdates.remove(bossId);
            BossBar bar = activeBossBars.get(bossId);
            if (bar == null) return;

            Entity entity = Bukkit.getEntity(entityUuid);
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                bar.progress(0.0f);
                return;
            }

            AttributeInstance attr = living.getAttribute(Attribute.MAX_HEALTH);
            if (attr == null) return;
            double maxHp = attr.getValue();
            if (maxHp <= 0) return;

            float progress = (float) Math.max(0.0, Math.min(1.0, living.getHealth() / maxHp));
            bar.progress(progress);

            BossEntry entry = config.getBosses().get(bossId);
            if (entry != null && !entry.bossBarTitle().isBlank()) {
                bar.name(ColorUtil.component(buildBarTitle(entry.bossBarTitle(), living)));
            }
        });
    }

    public void onPlayerJoin(Player player) {
        activeBossBars.values().forEach(player::showBossBar);
    }

    private void hideBossBars() {
        activeBossBars.values().forEach(bar ->
                Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar)));
    }

    private String buildBarTitle(String template, Entity entity) {
        if (!(entity instanceof LivingEntity living)) return template;
        AttributeInstance attr = living.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = attr != null ? attr.getValue() : 0.0;
        return template
                .replace("{health}", String.valueOf((int) Math.ceil(living.getHealth())))
                .replace("{max_health}", String.valueOf((int) maxHp));
    }

    private BossBar.Color parseBossBarColor(String str) {
        return switch (str) {
            case "PINK"   -> BossBar.Color.PINK;
            case "BLUE"   -> BossBar.Color.BLUE;
            case "GREEN"  -> BossBar.Color.GREEN;
            case "YELLOW" -> BossBar.Color.YELLOW;
            case "PURPLE" -> BossBar.Color.PURPLE;
            case "WHITE"  -> BossBar.Color.WHITE;
            case "RED"    -> BossBar.Color.RED;
            default       -> {
                plugin.getLogger().warning("Geçersiz bossbar rengi: '" + str + "'. RED kullanılıyor.");
                yield BossBar.Color.RED;
            }
        };
    }

    private BossBar.Overlay parseBossBarOverlay(String str) {
        return switch (str) {
            case "NOTCHED_6"  -> BossBar.Overlay.NOTCHED_6;
            case "NOTCHED_10" -> BossBar.Overlay.NOTCHED_10;
            case "NOTCHED_12" -> BossBar.Overlay.NOTCHED_12;
            case "NOTCHED_20" -> BossBar.Overlay.NOTCHED_20;
            case "PROGRESS"   -> BossBar.Overlay.PROGRESS;
            default           -> {
                plugin.getLogger().warning("Geçersiz bossbar stili: '" + str + "'. PROGRESS kullanılıyor.");
                yield BossBar.Overlay.PROGRESS;
            }
        };
    }

    // ── Yardımcı metotlar ─────────────────────────────────────

    private String replacePrefixPlaceholder(String msg) {
        return msg.replace("%prefix%", config.getPrefix());
    }

    private void removeEntitySilently(UUID uuid) {
        Entity entity = Bukkit.getEntity(uuid);
        if (entity != null) {
            modelEngineHook.removeModel(entity);
        }
        MythicMobsHook.removeMob(uuid);
        // MythicMobs'un remove() metodu entity'yi kaldırmazsa ya da sunucu kapanış
        // sırasında chunk zaten kaydedildiyse persistent entity disk'e yazılabilir.
        // Bunu önlemek için Bukkit entity'sini de açıkça kaldır.
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }
}
