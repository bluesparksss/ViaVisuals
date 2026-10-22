package dev.bluesparksss.viavisuals.entity.anim;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.display.ProxyEntities;
import dev.bluesparksss.viavisuals.entity.StandPose;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AnimStandView extends AnimViewBase {

  private static final ProtocolVersion STAND = ProtocolVersion.v1_19_3;
  private static final double POS_EPS = 0.004;
  private static final float ROT_EPS_DEG = 0.35f;

  private final double[] sentPos;
  private final float[] sentRot;
  private final boolean[] sentShown;

  public AnimStandView(List<AnimCatalog.Bone> bones, Map<String, PartLimbs> parts) {
    super(bones, parts);
    this.sentPos = new double[limbs.length * 3];
    this.sentRot = new float[limbs.length * 3];
    this.sentShown = new boolean[limbs.length];
  }

  @Override
  public int mainId() {
    return limbs.length == 0 ? 0 : limbs[0].id();
  }

  @Override
  public int[] moveIds() {
    return new int[0];
  }

  @Override
  public int[] allIds() {
    int[] out = new int[limbs.length];
    for (int i = 0; i < limbs.length; i++) {
      out[i] = limbs[i].id();
    }
    return out;
  }

  private StandPose.Pose pose(AnimatedMob mob, int src) {
    return StandPose.solve(
        new Quaternion4f(mob.rotation[src * 4], mob.rotation[src * 4 + 1], mob.rotation[src * 4 + 2], mob.rotation[src * 4 + 3]),
        new Vector3f(mob.translation[src * 3], mob.translation[src * 3 + 1], mob.translation[src * 3 + 2]), 0f,
        -StandPose.NECK);
  }

  @Override
  public List<PacketWrapper<?>> spawn(Vector3d base, AnimatedMob mob, boolean hurt) {
    this.hurt = hurt;
    List<PacketWrapper<?>> out = new ArrayList<>();
    for (int i = 0; i < limbs.length; i++) {
      Limb limb = limbs[i];
      int s = limb.src();
      StandPose.Pose p = pose(mob, s);
      Vector3d at = new Vector3d(base.getX() + p.dx(), base.getY() + p.dy(), base.getZ() + p.dz());
      boolean shown = mob.shown[s];
      out.addAll(ProxyEntities.spawn(limb.id(), STAND, at, 0f, shown ? limbItem(limb, hurt) : ItemStack.EMPTY, 1f,
          new Vector3f(0, 0, 0), 0.0));
      out.add(ProxyEntities.headPose(limb.id(), p.headX(), p.headY(), p.headZ()));
      sentPos[i * 3] = at.getX();
      sentPos[i * 3 + 1] = at.getY();
      sentPos[i * 3 + 2] = at.getZ();
      sentRot[i * 3] = p.headX();
      sentRot[i * 3 + 1] = p.headY();
      sentRot[i * 3 + 2] = p.headZ();
      sentShown[i] = shown;
    }
    return out;
  }

  @Override
  public void update(User user, AnimatedMob mob, int interpTicks, Vector3d base) {
    int sent = 0;
    for (int i = 0; i < limbs.length; i++) {
      Limb limb = limbs[i];
      int s = limb.src();
      boolean shown = mob.shown[s];
      if (shown != sentShown[i]) {
        sentShown[i] = shown;
        ProxyEntities.sendOne(user, ProxyEntities.setItem(limb.id(), STAND, shown ? limbItem(limb, hurt) : ItemStack.EMPTY));
        sent++;
      }
      if (!shown) {
        continue;
      }
      StandPose.Pose p = pose(mob, s);
      double x = base.getX() + p.dx();
      double y = base.getY() + p.dy();
      double z = base.getZ() + p.dz();
      if (Math.abs(x - sentPos[i * 3]) > POS_EPS || Math.abs(y - sentPos[i * 3 + 1]) > POS_EPS
          || Math.abs(z - sentPos[i * 3 + 2]) > POS_EPS) {
        sentPos[i * 3] = x;
        sentPos[i * 3 + 1] = y;
        sentPos[i * 3 + 2] = z;
        ProxyEntities.sendOne(user, new WrapperPlayServerEntityTeleport(limb.id(), new Vector3d(x, y, z), 0f, 0f, false));
        sent++;
      }
      if (Math.abs(angle(p.headX() - sentRot[i * 3])) > ROT_EPS_DEG || Math.abs(angle(p.headY() - sentRot[i * 3 + 1])) > ROT_EPS_DEG
          || Math.abs(angle(p.headZ() - sentRot[i * 3 + 2])) > ROT_EPS_DEG) {
        sentRot[i * 3] = p.headX();
        sentRot[i * 3 + 1] = p.headY();
        sentRot[i * 3 + 2] = p.headZ();
        ProxyEntities.sendOne(user, ProxyEntities.headPose(limb.id(), p.headX(), p.headY(), p.headZ()));
        sent++;
      }
    }
    if (sent > 0) {
      LIMB_PACKETS.addAndGet(sent);
      BUNDLES.incrementAndGet();
    }
  }

  private static float angle(float deg) {
    float d = deg % 360f;
    return d > 180f ? d - 360f : d < -180f ? d + 360f : d;
  }

  @Override
  public void setHurt(User user, AnimatedMob mob, boolean hurt) {
    if (this.hurt == hurt) {
      return;
    }
    this.hurt = hurt;
    for (int i = 0; i < limbs.length; i++) {
      if (sentShown[i]) {
        ProxyEntities.sendOne(user, ProxyEntities.setItem(limbs[i].id(), STAND, limbItem(limbs[i], hurt)));
      }
    }
  }
}
