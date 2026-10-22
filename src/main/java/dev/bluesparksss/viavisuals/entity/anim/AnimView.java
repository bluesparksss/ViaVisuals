package dev.bluesparksss.viavisuals.entity.anim;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import dev.bluesparksss.viavisuals.display.ProxyEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AnimView extends AnimViewBase {

  private static final float POS_EPS = 0.0015f;
  private static final float ROT_EPS = 0.0012f;

  public final int rootId;
  private final float[] sentT;
  private final float[] sentR;
  private final float[] sentS;
  private final boolean[] sentShown;
  private final boolean bundles;
  private final boolean standRoot;

  public AnimView(List<AnimCatalog.Bone> bones, Map<String, PartLimbs> parts, boolean bundles, boolean standRoot) {
    super(bones, parts);
    this.rootId = ProxyEntities.nextId();
    this.standRoot = standRoot;
    this.sentT = new float[limbs.length * 3];
    this.sentR = new float[limbs.length * 4];
    this.sentS = new float[limbs.length * 3];
    this.sentShown = new boolean[limbs.length];
    this.bundles = bundles;
  }

  @Override
  public int mainId() {
    return rootId;
  }

  @Override
  public int[] moveIds() {
    return new int[] {rootId};
  }

  @Override
  public int[] allIds() {
    int[] out = new int[limbs.length + 1];
    out[0] = rootId;
    for (int i = 0; i < limbs.length; i++) {
      out[i + 1] = limbs[i].id();
    }
    return out;
  }

  @Override
  public List<PacketWrapper<?>> spawn(Vector3d pos, AnimatedMob mob, boolean hurt) {
    this.hurt = hurt;
    List<PacketWrapper<?>> out = new ArrayList<>(ProxyEntities.spawnRoot(rootId, pos, standRoot, mob.rig.shadow));
    int[] ids = new int[limbs.length];
    for (int i = 0; i < limbs.length; i++) {
      Limb limb = limbs[i];
      int s = limb.src();
      remember(mob, i);
      Vector3f scale = mob.shown[s] ? new Vector3f(mob.scale[s * 3], mob.scale[s * 3 + 1], mob.scale[s * 3 + 2])
          : new Vector3f(0, 0, 0);
      out.addAll(ProxyEntities.spawnLimb(limb.id(), pos, limbItem(limb, hurt),
          new Vector3f(mob.translation[s * 3], mob.translation[s * 3 + 1], mob.translation[s * 3 + 2]),
          new Quaternion4f(mob.rotation[s * 4], mob.rotation[s * 4 + 1], mob.rotation[s * 4 + 2], mob.rotation[s * 4 + 3]),
          scale));
      ids[i] = limb.id();
    }
    out.add(ProxyEntities.passengers(rootId, ids));
    return out;
  }

  private void remember(AnimatedMob mob, int i) {
    int s = limbs[i].src();
    System.arraycopy(mob.translation, s * 3, sentT, i * 3, 3);
    System.arraycopy(mob.rotation, s * 4, sentR, i * 4, 4);
    System.arraycopy(mob.scale, s * 3, sentS, i * 3, 3);
    sentShown[i] = mob.shown[s];
  }

  @Override
  public void update(User user, AnimatedMob mob, int interpTicks, Vector3d basePos) {
    List<PacketWrapper<?>> out = null;
    for (int i = 0; i < limbs.length; i++) {
      int s = limbs[i].src();
      boolean shown = mob.shown[s];
      boolean visChange = shown != sentShown[i];
      Vector3f t = null;
      Quaternion4f r = null;
      Vector3f sc = null;
      if (shown || visChange) {
        if (shown) {
          if (visChange || differs(mob.translation, s * 3, sentT, i * 3, 3, POS_EPS)) {
            t = new Vector3f(mob.translation[s * 3], mob.translation[s * 3 + 1], mob.translation[s * 3 + 2]);
          }
          if (visChange || differs(mob.scale, s * 3, sentS, i * 3, 3, POS_EPS)) {
            sc = new Vector3f(mob.scale[s * 3], mob.scale[s * 3 + 1], mob.scale[s * 3 + 2]);
          }
          if (visChange || quatDiffers(mob.rotation, s * 4, sentR, i * 4)) {
            r = new Quaternion4f(mob.rotation[s * 4], mob.rotation[s * 4 + 1], mob.rotation[s * 4 + 2], mob.rotation[s * 4 + 3]);
          }
        } else {
          sc = new Vector3f(0, 0, 0);
        }
      }
      if (t == null && r == null && sc == null) {
        continue;
      }
      if (out == null) {
        out = new ArrayList<>();
      }
      out.add(ProxyEntities.limbUpdate(limbs[i].id(), visChange ? 0 : interpTicks, t, r, sc));
      if (t != null) {
        System.arraycopy(mob.translation, s * 3, sentT, i * 3, 3);
      }
      if (r != null) {
        System.arraycopy(mob.rotation, s * 4, sentR, i * 4, 4);
      }
      if (sc != null) {
        System.arraycopy(mob.scale, s * 3, sentS, i * 3, 3);
      }
      sentShown[i] = shown;
    }
    if (out != null) {
      sendBundle(user, out);
    }
  }

  @Override
  public void setHurt(User user, AnimatedMob mob, boolean hurt) {
    if (this.hurt == hurt) {
      return;
    }
    this.hurt = hurt;
    List<PacketWrapper<?>> out = new ArrayList<>(limbs.length);
    for (Limb limb : limbs) {
      out.add(ProxyEntities.setItem(limb.id(), com.viaversion.viaversion.api.protocol.version.ProtocolVersion.v1_21_4,
          limbItem(limb, hurt)));
    }
    sendBundle(user, out);
  }

  private void sendBundle(User user, List<PacketWrapper<?>> packets) {
    LIMB_PACKETS.addAndGet(packets.size());
    BUNDLES.incrementAndGet();
    if (bundles && packets.size() > 1) {
      ProxyEntities.sendOne(user, new WrapperPlayServerBundle());
      ProxyEntities.send(user, packets);
      ProxyEntities.sendOne(user, new WrapperPlayServerBundle());
    } else {
      ProxyEntities.send(user, packets);
    }
  }

  private static boolean differs(float[] a, int ai, float[] b, int bi, int n, float eps) {
    for (int k = 0; k < n; k++) {
      if (Math.abs(a[ai + k] - b[bi + k]) > eps) {
        return true;
      }
    }
    return false;
  }

  private static boolean quatDiffers(float[] a, int ai, float[] b, int bi) {
    float dot = Math.abs(a[ai] * b[bi] + a[ai + 1] * b[bi + 1] + a[ai + 2] * b[bi + 2] + a[ai + 3] * b[bi + 3]);
    return 1f - dot > ROT_EPS * ROT_EPS * 0.5f;
  }
}
