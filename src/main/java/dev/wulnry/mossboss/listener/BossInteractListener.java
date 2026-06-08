package dev.wulnry.mossboss.listener;

import dev.wulnry.mossboss.manager.BossManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Oyuncunun bir varlığa sağ tıklamasını dinler.
 *
 * <p>Tüm mantık {@link BossManager}'a delege edilir; bu sınıf yalnızca
 * olayı filtreler ve iletir. Çift ateşlemeyi (ana el + yardımcı el)
 * {@code EquipmentSlot.HAND} kontrolüyle engeller.</p>
 */
public final class BossInteractListener implements Listener {

    private final BossManager manager;

    public BossInteractListener(BossManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        // Yalnızca ana el tıklamalarını işle; çift tetiklenmeyi önler.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        boolean handled = manager.handleInteract(event.getPlayer(), event.getRightClicked());

        // Plugin bu tıklamayı işlediyse olası varsayılan davranışı iptal et.
        if (handled) {
            event.setCancelled(true);
        }
    }
}
