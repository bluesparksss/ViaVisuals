package dev.bluesparksss.viavisuals.mechanics.wind;

import org.bukkit.configuration.ConfigurationSection;

public record WindExplosionSettings(
    boolean enabled,
    boolean legacyClientProxy,
    double playerBurstRadius,
    double breezeBurstRadius,
    int particleCount,
    String burstSound,
    float burstSoundVolume,
    float burstSoundPitch
) {

  public static final double VANILLA_PLAYER_BURST_RADIUS = 2.4D;

  public static final double VANILLA_BREEZE_BURST_RADIUS = 6.0D;

  private static final int PARTICLE_COUNT = 16;

  private static final String BURST_SOUND = "minecraft:entity.wind_charge.wind_burst";

  private static final float BURST_SOUND_VOLUME = 4.0F;

  private static final float BURST_SOUND_PITCH = 0.875F;

  public static WindExplosionSettings from(ConfigurationSection section) {
    return of(section == null || section.getBoolean("enabled", true));
  }

  public static WindExplosionSettings defaults() {
    return of(true);
  }

  private static WindExplosionSettings of(boolean enabled) {
    return new WindExplosionSettings(
        enabled,
        true,
        VANILLA_PLAYER_BURST_RADIUS,
        VANILLA_BREEZE_BURST_RADIUS,
        PARTICLE_COUNT,
        BURST_SOUND,
        BURST_SOUND_VOLUME,
        BURST_SOUND_PITCH
    );
  }
}
