package dev.wulnry.mossboss.listener;

import dev.wulnry.mossboss.manager.BossManager;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;


public final class BossDeathListener implements Listener {

    private final BossManager manager;

    public BossDeathListener(BossManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMythicMobDeath(MythicMobDeathEvent event) {
        manager.handleBossDeath(event.getMob().getUniqueId());
    }
}
