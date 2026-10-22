package dev.bluesparksss.viavisuals.mechanics.wind;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class WindExplosionEffects {

  private static final Particle POOF = particle("POOF", "EXPLOSION_NORMAL");
  private static final double VIEW_RANGE = 64.0D;

  private static Particle particle(String... names) {
    for (String name : names) {
      try {
        return Particle.valueOf(name);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return Particle.CLOUD;
  }

  private WindExplosionSettings settings = WindExplosionSettings.defaults();

  public void reload(WindExplosionSettings settings) {
    this.settings = settings;
  }

  public boolean isEnabled() {
    return settings.enabled();
  }

  public boolean legacyClientProxy() {
    return settings.legacyClientProxy();
  }

  void playWindChargeBurst(Location center, boolean breeFir) {
    playVisualBurst(center, breeFir ? settings.breezeBurstRadius() : settings.playerBurstRadius());
  }

  void playMaceWindBurst(Location center) {
    playVisualBurst(center, settings.playerBurstRadius());
  }

  private static boolean legacyViewer(Player player) {
    return VersionManager.tryGetProtocolVersion(player)
        .map(protocolVersion -> protocolVersion.olderThan(ProtocolVersion.v1_21))
        .orElse(false);
  }

  private void playVisualBurst(Location center, double radius) {
    World world = center == null ? null : center.getWorld();
    if (!settings.enabled() || world == null) {
      return;
    }

    List<Player> viewers = new ArrayList<>();
    for (Player player : world.getPlayers()) {
      if (player.getLocation().distanceSquared(center) <= VIEW_RANGE * VIEW_RANGE && legacyViewer(player)) {
        viewers.add(player);
      }
    }
    if (viewers.isEmpty()) {
      return;
    }

    Location burst = center.clone().add(0.0D, 0.25D, 0.0D);
    double spread = radius * 0.35D;
    int count = Math.max(8, settings.particleCount());
    Particle gust = resolveGustParticle();
    for (Player player : viewers) {
      player.spawnParticle(Particle.CLOUD, burst, count, spread, 0.35D, spread, 0.02D);
      player.spawnParticle(POOF, burst, count / 2, spread, 0.2D, spread, 0.01D);
      if (gust != null) {
        player.spawnParticle(gust, burst, count / 2, spread, 0.3D, spread, 0.01D);
      }
      player.playSound(burst, settings.burstSound(), settings.burstSoundVolume(), settings.burstSoundPitch());
    }
  }

  private static Particle resolveGustParticle() {
    try {
      return Particle.valueOf("GUST");
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
