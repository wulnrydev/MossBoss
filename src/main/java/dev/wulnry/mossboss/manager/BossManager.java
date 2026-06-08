package dev.wulnry.mossboss.manager;

import dev.wulnry.mossboss.MossBoss;
import dev.wulnry.mossboss.config.BossEntry;
import dev.wulnry.mossboss.config.ConfigManager;
import dev.wulnry.mossboss.hook.MythicMobsHook;
import dev.wulnry.mossboss.hook.ModelEngineHook;
import dev.wulnry.mossboss.util.ColorUtil;
import dev.wulnry.mossboss.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BossManager {

    public enum ForcespawnResult { SUCCESS, ALREADY_ACTIVE, SPAWN_FAILED }

    private final MossBoss plugin;
    private final ConfigManager config;
    private final ModelEngineHook modelEngineHook;

    private final Map<String, UUID> placeholderUuids = new ConcurrentHashMap<>();
    private final Map<String, UUID> realBossUuids    = new ConcurrentHashMap<>();
    private final Map<UUID, String> entityToBossId   = new ConcurrentHashMap<>();
    private final Map<String, Long> respawnAt        = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> respawnTasks = new ConcurrentHashMap<>();

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

    public void despawnAll() {
        respawnTasks.values().forEach(BukkitTask::cancel);
        respawnTasks.clear();

        for (Map.Entry<String, UUID> e : placeholderUuids.entrySet()) {
            removeEntitySilently(e.getValue());
        }
        for (Map.Entry<String, UUID> e : realBossUuids.entrySet()) {
            MythicMobsHook.removeMob(e.getValue());
        }

        synchronized (this) {
            placeholderUuids.clear();
            realBossUuids.clear();
            entityToBossId.clear();
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
        if (entity instanceof Mob mob) {
            mob.setAI(false);
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
        if (phUuid != null) {
            modelEngineHook.removeModel(entity);
            MythicMobsHook.removeMob(phUuid);
        }

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

        String raw = entry.spawnBroadcastMessage() != null && !entry.spawnBroadcastMessage().isBlank()
                ? entry.spawnBroadcastMessage()
                : config.getSpawnBroadcastMessage();
        String message = raw
                .replace("%prefix%", config.getPrefix())
                .replace("%player%", player.getName())
                .replace("%boss_name%", entry.id());
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
            BossEntry placeholderEntry = config.getBosses().get(bossId);
            if (placeholderEntry == null) return;
            BukkitTask oldTask = respawnTasks.remove(bossId);
            if (oldTask != null) oldTask.cancel();
            BukkitTask respawnTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                respawnTasks.remove(bossId);
                if (placeholderUuids.containsKey(bossId) || realBossUuids.containsKey(bossId)) return;
                BossEntry fresh = config.getBosses().get(bossId);
                if (fresh != null) spawnPlaceholder(fresh);
            }, 1L);
            respawnTasks.put(bossId, respawnTask);
            return;
        }
        if (!tracked.equals(deadUuid)) {
            return;
        }

        BossEntry entry = config.getBosses().get(bossId);
        if (entry == null) {
            return;
        }

        long respawnMillis = (long) entry.respawnDelaySeconds() * 1000L;
        respawnAt.put(bossId, System.currentTimeMillis() + respawnMillis);

        long delayTicks = (long) entry.respawnDelaySeconds() * 20L;

        if (!plugin.isEnabled()) return;

        BukkitTask old = respawnTasks.remove(bossId);
        if (old != null) old.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            respawnTasks.remove(bossId);
            if (placeholderUuids.containsKey(bossId) || realBossUuids.containsKey(bossId)) return;
            BossEntry fresh = config.getBosses().get(bossId);
            if (fresh != null) {
                spawnPlaceholder(fresh);
            }
        }, delayTicks);
        respawnTasks.put(bossId, task);
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

        String raw = entry.spawnBroadcastMessage() != null && !entry.spawnBroadcastMessage().isBlank()
                ? entry.spawnBroadcastMessage()
                : config.getSpawnBroadcastMessage();
        String message = raw
                .replace("%prefix%", config.getPrefix())
                .replace("%player%", sender != null ? sender.getName() : "Konsol")
                .replace("%boss_name%", entry.id());
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

    public void clearState() {
        respawnTasks.values().forEach(BukkitTask::cancel);
        respawnTasks.clear();
        synchronized (this) {
            placeholderUuids.clear();
            realBossUuids.clear();
            entityToBossId.clear();
        }
        respawnAt.clear();
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
    }
}
