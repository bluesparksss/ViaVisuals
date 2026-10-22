package dev.bluesparksss.viavisuals.mechanics.pearl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

final class EnderPearlMomentumApplier {

  private static final int PERSISTENCE_TICKS = 3;

  private EnderPearlMomentumApplier() {
  }

  static void apply(JavaPlugin plugin, Player player, Vector momentum) {
    Vector velocity = momentum.clone();

    Runnable sync = () -> {
      if (!player.isOnline()) {
        return;
      }

      player.setVelocity(velocity);
      sendClientVelocity(player, velocity);
    };

    sync.run();
    for (int tick = 1; tick <= PERSISTENCE_TICKS; tick++) {
      Bukkit.getScheduler().runTaskLater(plugin, sync, tick);
    }
  }

  private static void sendClientVelocity(Player player, Vector velocity) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null) {
      return;
    }

    user.sendPacket(new WrapperPlayServerEntityVelocity(
        player.getEntityId(),
        new Vector3d(velocity.getX(), velocity.getY(), velocity.getZ())
    ));
  }
}
