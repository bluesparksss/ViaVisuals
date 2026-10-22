package dev.bluesparksss.viavisuals.entity.anim;

import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.Map;

public abstract class AnimDriver {

  public static final int EVENT_SWING = 1000;

  protected AnimatedMob mob;
  private AnimCatalog catalog;
  private final Map<String, KeyframeAnim> anims = new HashMap<>();
  private final Map<String, int[]> binds = new HashMap<>();
  private String lastPose = "";

  final void attach(AnimatedMob mob, AnimCatalog catalog) {
    this.mob = mob;
    this.catalog = catalog;
  }

  public final void tickWith(Entity ent, int[] events, int eventCount) {
    String pose = ent == null ? lastPose : Refl.enumName(ent, "getPose");
    boolean changed = !pose.equals(lastPose);
    lastPose = pose;
    tick(ent, pose, changed, events, eventCount);
  }

  protected abstract void tick(Entity ent, String pose, boolean poseChanged, int[] events, int eventCount);

  public void apply(double[] pose) {
  }

  public void visibility(PoseSolver solver) {
  }

  private final Map<String, AnimState> forced = new java.util.concurrent.ConcurrentHashMap<>();

  public final String forcePlay(String name) {
    for (Map.Entry<String, String> ent : mob.rig.keyframes.entrySet()) {
      if (ent.getKey().equalsIgnoreCase(name) || ent.getValue().toLowerCase().endsWith(name.toLowerCase())) {
        AnimState st = new AnimState();
        st.begin(mob.age());
        forced.put(ent.getKey(), st);
        return ent.getKey();
      }
    }
    return null;
  }

  public final void stopForced() {
    forced.clear();
  }

  public final void applyForced(double[] pose) {
    for (Map.Entry<String, AnimState> ent : forced.entrySet()) {
      KeyframeAnim anim = anim(ent.getKey());
      if (anim == null) {
        forced.remove(ent.getKey());
        continue;
      }
      double ms = ent.getValue().elapsedMs(mob.age());
      if (!anim.looping && ms > anim.length * 1000.0 + 500) {
        forced.remove(ent.getKey());
        continue;
      }
      anim.apply(bind(ent.getKey(), anim), ms, 1.0, pose);
    }
  }

  protected final int age() {
    return mob.age();
  }

  protected static boolean has(int[] events, int count, int code) {
    for (int i = 0; i < count; i++) {
      if (events[i] == code) {
        return true;
      }
    }
    return false;
  }

  private KeyframeAnim anim(String field) {
    String id = mob.rig.keyframes.get(field);
    if (id == null || id.equals("?")) {
      return null;
    }
    return anims.computeIfAbsent(id, catalog::animation);
  }

  private int[] bind(String field, KeyframeAnim anim) {
    return binds.computeIfAbsent(field, f -> anim.bind(mob.rig));
  }

  protected final void play(String field, AnimState st, double[] pose) {
    if (!st.running()) {
      return;
    }
    KeyframeAnim anim = anim(field);
    if (anim != null) {
      anim.apply(bind(field, anim), st.elapsedMs(mob.age()), 1.0, pose);
    }
  }

  protected final void walk(String field, double speedFactor, double scaleFactor, double[] pose) {
    KeyframeAnim anim = anim(field);
    if (anim != null) {
      double time = mob.walkPosition() * 50.0 * speedFactor;
      double scale = Math.min(mob.walkSpeed() * scaleFactor, 1.0);
      anim.apply(bind(field, anim), time, scale, pose);
    }
  }

  protected final void at(String field, double millis, double scale, double[] pose) {
    KeyframeAnim anim = anim(field);
    if (anim != null) {
      anim.apply(bind(field, anim), millis, scale, pose);
    }
  }

  protected final void setVisible(PoseSolver solver, String path, boolean visible) {
    int node = mob.rig.indexOfPath(path);
    if (node >= 0) {
      solver.visible[node] = visible;
    }
  }
}
