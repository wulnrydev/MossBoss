package dev.wulnry.mossboss.listener;

import dev.wulnry.mossboss.manager.BossManager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class BossActiveListener implements Listener {

    private final BossManager manager;

    public BossActiveListener(BossManager manager) {
        this.manager = manager;
    }

    // Hasar kaynağını oyuncuya bağla ve damage tracker'a ekle
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null) return;

        manager.handleBossDamage(
                event.getEntity().getUniqueId(),
                attacker.getUniqueId(),
                event.getFinalDamage());
    }

    // Herhangi bir hasar sonrası bossbar sağlık yüzdesini güncelle
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        manager.handleBossHealthUpdate(event.getEntity().getUniqueId());
    }

    // Sunucuya yeni katılan oyuncuya aktif bossbarleri göster
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.onPlayerJoin(event.getPlayer());
    }
}
