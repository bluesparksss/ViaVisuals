package dev.bluesparksss.viavisuals.mechanics.pearl;

import org.bukkit.configuration.ConfigurationSection;

public record EnderPearlMomentumSettings(
    boolean enabled,
    boolean legacyClientsOnly
) {

  public static EnderPearlMomentumSettings from(ConfigurationSection section) {
    return new EnderPearlMomentumSettings(section == null || section.getBoolean("enabled", true), true);
  }

  public static EnderPearlMomentumSettings defaults() {
    return new EnderPearlMomentumSettings(true, true);
  }
}
