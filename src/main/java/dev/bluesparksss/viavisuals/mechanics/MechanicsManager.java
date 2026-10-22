package dev.bluesparksss.viavisuals.mechanics;

import dev.bluesparksss.viavisuals.item.ItemTranslator;
import dev.bluesparksss.viavisuals.mechanics.pearl.EnderPearlMomentumListener;
import dev.bluesparksss.viavisuals.mechanics.pearl.EnderPearlMomentumSettings;
import dev.bluesparksss.viavisuals.mechanics.pearl.EnderPearlMomentumTracker;
import dev.bluesparksss.viavisuals.mechanics.spear.SpearSupport;
import dev.bluesparksss.viavisuals.mechanics.wind.WindChargeEntityTracker;
import dev.bluesparksss.viavisuals.mechanics.wind.WindChargeLegacyPacketListener;
import dev.bluesparksss.viavisuals.mechanics.wind.WindExplosionEffects;
import dev.bluesparksss.viavisuals.mechanics.wind.WindExplosionListener;
import dev.bluesparksss.viavisuals.mechanics.wind.WindExplosionSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MechanicsManager {

  private final SpearSupport spears;
  private final UseHoldSupport useHold;
  private final WindExplosionEffects windEff;
  private final WindExplosionListener windListener;
  private final WindChargeLegacyPacketListener windPackets;
  private final EnderPearlMomentumListener pearlListener;

  public MechanicsManager(JavaPlugin plugin, ItemTranslator xlat) {
    this.useHold = new UseHoldSupport(plugin, xlat);
    this.spears = new SpearSupport(plugin, xlat, useHold);
    this.windEff = new WindExplosionEffects();
    this.windListener = new WindExplosionListener(windEff);
    WindChargeEntityTracker windTracker = new WindChargeEntityTracker();
    windTracker.start(plugin);
    this.windPackets = new WindChargeLegacyPacketListener(windTracker, windEff, xlat);
    this.pearlListener = new EnderPearlMomentumListener(plugin, new EnderPearlMomentumTracker());
  }

  public SpearSupport spears() {
    return spears;
  }

  public UseHoldSupport useHold() {
    return useHold;
  }

  public WindExplosionListener windExplosionListener() {
    return windListener;
  }

  public WindChargeLegacyPacketListener windChargeLegacyPacketListener() {
    return windPackets;
  }

  public EnderPearlMomentumListener enderPearlMomentumListener() {
    return pearlListener;
  }

  public void reload(FileConfiguration config) {
    var conf = config.getConfigurationSection("mechanics");
    windEff.reload(WindExplosionSettings.from(section(conf, "wind-charge", "wind_explosion")));
    pearlListener.reload(EnderPearlMomentumSettings.from(section(conf, "ender-pearl-momentum", "ender_pearl_momentum")));
  }

  private static ConfigurationSection section(ConfigurationSection root, String key, String oldKey) {
    if (root == null) {
      return null;
    }
    return root.getConfigurationSection(!root.contains(key, true) && root.contains(oldKey, true) ? oldKey : key);
  }
}
