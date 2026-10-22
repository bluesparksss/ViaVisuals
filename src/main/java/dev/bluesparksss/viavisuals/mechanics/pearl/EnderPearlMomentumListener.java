package dev.bluesparksss.viavisuals.mechanics.pearl;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class EnderPearlMomentumListener implements Listener {

  private final JavaPlugin plugin;
  private final EnderPearlMomentumTracker tracker;
  private EnderPearlMomentumSettings settings = EnderPearlMomentumSettings.defaults();

  public EnderPearlMomentumListener(JavaPlugin plugin, EnderPearlMomentumTracker tracker) {
    this.plugin = plugin;
    this.tracker = tracker;
  }

  public void reload(EnderPearlMomentumSettings settings) {
    this.settings = settings;
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onPearlHit(ProjectileHitEvent event) {
    if (!settings.enabled()) {
      return;
    }

    if (!(event.getEntity() instanceof EnderPearl)) {
      return;
    }

    if (!(event.getEntity().getShooter() instanceof Player player)) {
      return;
    }

    if (!shouldApplyFor(player)) {
      return;
    }

    tracker.track(
        player.getUniqueId(),
        new EnderPearlMomentumTracker.Snapshot(
            player.getVelocity(),
            player.getLocation().getYaw(),
            player.getLocation().getPitch()
        )
    );
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPearlTeleport(PlayerTeleportEvent event) {
    if (!settings.enabled()) {
      return;
    }

    if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
      return;
    }

    Player player = event.getPlayer();
    if (event.isCancelled()) {
      tracker.clear(player.getUniqueId());
      return;
    }

    if (!shouldApplyFor(player)) {
      tracker.clear(player.getUniqueId());
      return;
    }

    EnderPearlMomentumTracker.Snapshot snapshot = tracker.poll(player.getUniqueId());
    if (snapshot == null) {
      return;
    }

    EnderPearlMomentumApplier.apply(plugin, player, snapshot.velocity());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    tracker.clear(event.getPlayer().getUniqueId());
  }

  private boolean shouldApplyFor(Player player) {
    if (!settings.legacyClientsOnly()) {
      return true;
    }

    return VersionManager.tryGetProtocolVersion(player)
        .map(protocolVersion -> protocolVersion.olderThan(ProtocolVersion.v1_21))
        .orElse(true);
  }
}
