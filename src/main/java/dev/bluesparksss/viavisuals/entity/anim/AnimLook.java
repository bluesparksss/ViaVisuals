package dev.bluesparksss.viavisuals.entity.anim;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

import java.util.List;
import java.util.Map;

public interface AnimLook {

  java.util.concurrent.atomic.AtomicLong LIMB_PACKETS = new java.util.concurrent.atomic.AtomicLong();
  java.util.concurrent.atomic.AtomicLong BUNDLES = new java.util.concurrent.atomic.AtomicLong();

  record PartLimbs(List<AnimCatalog.Bone> bones, int rgb) {
  }

  int mainId();

  int[] allIds();

  int[] moveIds();

  Map<String, Integer> partColors();

  List<PacketWrapper<?>> spawn(Vector3d basePos, AnimatedMob mob, boolean hurt);

  void update(User user, AnimatedMob mob, int interpTicks, Vector3d basePos);

  void setHurt(User user, AnimatedMob mob, boolean hurt);
}
