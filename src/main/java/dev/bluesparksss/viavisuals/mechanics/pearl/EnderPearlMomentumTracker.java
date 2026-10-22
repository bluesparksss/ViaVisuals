package dev.bluesparksss.viavisuals.mechanics.pearl;

import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EnderPearlMomentumTracker {

  public record Snapshot(Vector velocity, float yaw, float pitch) {
    public Snapshot {
      velocity = velocity.clone();
    }
  }

  private final Map<UUID, Snapshot> pending = new ConcurrentHashMap<>();

  public void track(UUID plrId, Snapshot snapshot) {
    pending.put(plrId, snapshot);
  }

  public Snapshot poll(UUID plrId) {
    return pending.remove(plrId);
  }

  public void clear(UUID plrId) {
    pending.remove(plrId);
  }
}
