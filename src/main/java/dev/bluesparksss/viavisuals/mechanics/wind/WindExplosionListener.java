package dev.bluesparksss.viavisuals.mechanics.wind;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.version.VersionManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WindExplosionListener implements Listener {

  private static final Material MACE = Material.matchMaterial("mace");

  private final WindExplosionEffects effects;
  private final Map<Long, Long> receTic = new ConcurrentHashMap<>();

  public WindExplosionListener(WindExplosionEffects effects) {
    this.effects = effects;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onWindChargeExplode(EntityExplodeEvent event) {
    if (!effects.isEnabled()) {
      return;
    }

    Entity entity = event.getEntity();
    if (!(entity instanceof WindCharge windCha)) {
      return;
    }

    if (shouldSkipDuplicate(event.getLocation())) {
      return;
    }

    effects.playWindChargeBurst(event.getLocation(), isBreezeWindCharge(windCha));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onWindChargeBlockExplode(BlockExplodeEvent event) {
    if (!effects.isEnabled()) {
      return;
    }

    if (!isLikelyWindChargeExplosion(event)) {
      return;
    }

    if (shouldSkipDuplicate(event.getBlock().getLocation())) {
      return;
    }

    effects.playWindChargeBurst(event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D), false);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMaceWindBurst(EntityDamageByEntityEvent event) {
    if (!effects.isEnabled()) {
      return;
    }

    if (!(event.getDamager() instanceof Player attacker)) {
      return;
    }

    DamageType damaTyp = event.getDamageSource().getDamageType();
    if (!NamespacedKey.minecraft("mace_smash").equals(damaTyp.getKey())) {
      return;
    }

    ItemStack weapon = attacker.getInventory().getItemInMainHand();
    if (weapon.getType() != MACE) {
      return;
    }

    int windLev = weapon.getEnchantmentLevel(Enchantment.WIND_BURST);
    if (windLev <= 0) {
      return;
    }

    Location center = attacker.getLocation();
    if (shouldSkipDuplicate(center)) {
      return;
    }

    effects.playMaceWindBurst(center);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onLaunch(ProjectileLaunchEvent event) {
    if (!effects.isEnabled() || !(event.getEntity() instanceof WindCharge windCha)
        || !(windCha.getShooter() instanceof Player thrower) || isBreezeWindCharge(windCha)) {
      return;
    }
    boolean noItem = VersionManager.tryGetProtocolVersion(thrower)
        .map(proto -> proto.olderThan(ProtocolVersion.v1_20_5)).orElse(false);
    User user = noItem ? PacketEvents.getAPI().getPlayerManager().getUser(thrower) : null;
    if (user != null) {
      if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_26_3)) {
        user.sendPacket(new WrapperPlayServerSwingAnimation(thrower.getEntityId(), InteractionHand.MAIN_HAND,
            new ItemSwingAnimation(ItemSwingAnimation.Type.WHACK, 6)));
      } else {
        user.sendPacket(new WrapperPlayServerEntityAnimation(thrower.getEntityId(),
            WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
      }
    }
  }

  private static boolean isLikelyWindChargeExplosion(BlockExplodeEvent event) {
    if (event.getYield() > 0.0F) {
      return false;
    }

    if (event.blockList().size() > 2) {
      return false;
    }

    Block block = event.getBlock();
    World world = block.getWorld();
    Location center = block.getLocation().add(0.5D, 0.5D, 0.5D);

    for (Entity entity : world.getNearbyEntities(center, 1.5D, 1.5D, 1.5D)) {
      if (entity instanceof WindCharge) {
        return true;
      }
    }

    return false;
  }

  private static boolean isBreezeWindCharge(WindCharge windCha) {
    return "BREEZE_WIND_CHARGE".equals(windCha.getType().name());
  }

  private boolean shouldSkipDuplicate(Location location) {
    if (location == null || location.getWorld() == null) {
      return true;
    }

    long keyStr = burstKey(location);
    long tick = location.getWorld().getFullTime();
    Long prevTic = receTic.put(keyStr, tick);
    return prevTic != null && prevTic == tick;
  }

  private static long burstKey(Location location) {
    World world = location.getWorld();
    long worlBit = (world == null) ? 0L : world.getUID().getLeastSignificantBits();
    return worlBit
        ^ ((long) location.getBlockX() << 32)
        ^ ((long) location.getBlockZ() << 16)
        ^ location.getBlockY();
  }
}
