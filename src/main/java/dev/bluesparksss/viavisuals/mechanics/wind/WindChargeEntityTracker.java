package dev.bluesparksss.viavisuals.mechanics.wind;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WindChargeEntityTracker {

  private record Tracked(UUID entity, Set<UUID> viewers) {
  }

  private final Map<Integer, Tracked> tracked = new ConcurrentHashMap<>();

  public void track(int entId, UUID entity, UUID viewer) {
    if (entId >= 0 && entity != null && viewer != null) {
      tracked.computeIfAbsent(entId, k -> new Tracked(entity, ConcurrentHashMap.newKeySet())).viewers().add(viewer);
    }
  }

  public void start(JavaPlugin plugin) {
    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
  }

  private void tick() {
    Iterator<Map.Entry<Integer, Tracked>> it = tracked.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Integer, Tracked> ent = it.next();
      Entity wind = Bukkit.getEntity(ent.getValue().entity());
      if (wind == null || !wind.isValid()) {
        it.remove();
        continue;
      }
      Vector vel = wind.getVelocity();
      WrapperPlayServerEntityVelocity pkt = new WrapperPlayServerEntityVelocity(ent.getKey(),
          new Vector3d(vel.getX(), vel.getY(), vel.getZ()));
      ent.getValue().viewers().removeIf(id -> {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) {
          return true;
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user != null) {
          user.sendPacket(pkt);
        }
        return false;
      });
      if (ent.getValue().viewers().isEmpty()) {
        it.remove();
      }
    }
  }
}
