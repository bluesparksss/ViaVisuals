package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.display.ProxyEntities;
import dev.bluesparksss.viavisuals.version.VersionManager;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockMining extends PacketListenerAbstract {

  private static final PotionEffectType FATIGUE = PotionEffectType.getByKey(org.bukkit.NamespacedKey.minecraft("mining_fatigue"));

  private record Session(Block block, int crackId, double progress, int stage, BlockCatalog.Mapping map, int ticks,
                         int notBefore, long started) {
  }

  private static final int NEXT_BREAK_TICKS = 7;
  private int counter;

  private final JavaPlugin plugin;
  private final BlockCatalog catalog;
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
  private final Set<UUID> slowed = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Object[]> realBreakSpeed = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> lastBreak = new ConcurrentHashMap<>();
  private BlockEffects effects;

  public BlockMining(JavaPlugin plugin, BlockCatalog catalog) {
    super(PacketListenerPriority.HIGH);
    this.plugin = plugin;
    this.catalog = catalog;
    Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
  }

  public void setEffects(BlockEffects effects) {
    this.effects = effects;
  }

  @Override
  public void onPacketReceive(PacketReceiveEvent event) {
    if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null || player.getGameMode() == GameMode.CREATIVE
        || player.getInventory().getItemInMainHand().getType().name().endsWith("_SPEAR")) {
      return;
    }
    WrapperPlayClientPlayerDigging pkt = new WrapperPlayClientPlayerDigging(event);
    DiggingAction action = pkt.getAction();
    Vector3i pos = pkt.getBlockPosition();
    Session cur = sessions.get(player.getUniqueId());
    boolean samePos = cur != null && cur.block().getX() == pos.getX() && cur.block().getY() == pos.getY()
        && cur.block().getZ() == pos.getZ();
    switch (action) {
      case START_DIGGING -> {
        int seq = pkt.getSequence();
        BlockFace face = bukkitFace(pkt.getBlockFace());
        if (samePos) {
          event.setCancelled(true);
          return;
        }
        if (cur != null) {
          Bukkit.getScheduler().runTask(plugin, () -> abort(player));
        }
        Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
        if (proto.isEmpty() || !BlockCatalog.clientNeedsCarriers(proto.get())) {
          return;
        }
        Block block = loadedBlock(player.getWorld(), pos);
        if (block == null) {
          return;
        }
        int state = SpigotConversionUtil.fromBukkitBlockData(block.getBlockData()).getGlobalId();
        if (catalog.mappingFor(state, proto.get()) == null || block.getBreakSpeed(player) >= 1.0f
            || vanillaRejects(player, block)) {
          return;
        }
        event.setCancelled(true);
        slowDown(player, proto.get());
        Bukkit.getScheduler().runTask(plugin, () -> begin(player, block, face, seq));
      }
      case CANCELLED_DIGGING -> {
        if (samePos) {
          event.setCancelled(true);
          Bukkit.getScheduler().runTask(plugin, () -> abort(player));
        }
      }
      case FINISHED_DIGGING -> {
        if (samePos) {
          event.setCancelled(true);
        }
      }
      default -> {
      }
    }
  }

  private static boolean vanillaRejects(Player player, Block block) {
    if (player.getGameMode() == GameMode.ADVENTURE || !block.getWorld().getWorldBorder().isInside(block.getLocation())) {
      return true;
    }
    int radius = Bukkit.getSpawnRadius();
    World main = Bukkit.getWorlds().get(0);
    if (radius <= 0 || player.isOp() || Bukkit.getOperators().isEmpty() || !block.getWorld().equals(main)) {
      return false;
    }
    org.bukkit.Location spawn = main.getSpawnLocation();
    return Math.max(Math.abs(block.getX() - spawn.getBlockX()), Math.abs(block.getZ() - spawn.getBlockZ())) <= radius;
  }

  private static Block loadedBlock(World world, Vector3i pos) {
    if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
      return null;
    }
    try {
      return world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static BlockFace bukkitFace(com.github.retrooper.packetevents.protocol.world.BlockFace face) {
    try {
      return BlockFace.valueOf(face.name());
    } catch (IllegalArgumentException ex) {
      return BlockFace.SELF;
    }
  }

  private static boolean interactAllowed(Player player, Block block, BlockFace face) {
    PlayerInteractEvent ev = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
        player.getInventory().getItemInMainHand(), block, face, EquipmentSlot.HAND);
    Bukkit.getPluginManager().callEvent(ev);
    return ev.useInteractedBlock() != Event.Result.DENY;
  }

  private static void refuse(Player player, Block block, int seq) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user != null && seq > 0) {
      user.sendPacketSilently(new WrapperPlayServerAcknowledgeBlockChanges(seq));
    }
    player.sendBlockChange(block.getLocation(), block.getBlockData());
  }

  private void begin(Player player, Block block, BlockFace face, int seq) {
    if (!player.isOnline()) {
      return;
    }
    if (!interactAllowed(player, block, face)) {
      refuse(player, block, seq);
      speedUp(player);
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    BlockCatalog.Mapping map = proto.isEmpty() ? null
        : catalog.mappingFor(SpigotConversionUtil.fromBukkitBlockData(block.getBlockData()).getGlobalId(), proto.get());
    proto.ifPresent(client -> slowDown(player, client));
    Integer last = lastBreak.get(player.getUniqueId());
    int notBefore = Math.max(counter + 1, last == null ? 0 : last + NEXT_BREAK_TICKS);
    sessions.put(player.getUniqueId(), new Session(block, ProxyEntities.nextId(), 0, -1, map, 0, notBefore, counter));
    player.sendBlockChange(block.getLocation(), block.getBlockData());
  }

  private static boolean hasBreakAttribute(ProtocolVersion client) {
    return client.newerThanOrEqualTo(ProtocolVersion.v1_20_5);
  }

  private void slowDown(Player player, ProtocolVersion client) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null || !slowed.add(player.getUniqueId())) {
      return;
    }
    if (hasBreakAttribute(client)) {
      user.sendPacketSilently(new WrapperPlayServerUpdateAttributes(player.getEntityId(),
          List.of(new WrapperPlayServerUpdateAttributes.Property(Attributes.BLOCK_BREAK_SPEED, 0.0, new ArrayList<>()))));
    } else {
      user.sendPacketSilently(new WrapperPlayServerEntityEffect(player.getEntityId(), PotionTypes.MINING_FATIGUE,
          -1, 1_000_000, (byte) 0));
    }
  }

  private void speedUp(Player player) {
    UUID id = player.getUniqueId();
    if (!slowed.remove(id) || !player.isOnline()) {
      return;
    }
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (user == null || proto.isEmpty()) {
      return;
    }
    if (hasBreakAttribute(proto.get())) {
      Object[] real = realBreakSpeed.get(id);
      double value = real == null ? 1.0 : (Double) real[0];
      @SuppressWarnings("unchecked")
      List<WrapperPlayServerUpdateAttributes.PropertyModifier> mods = real == null ? new ArrayList<>()
          : new ArrayList<>((List<WrapperPlayServerUpdateAttributes.PropertyModifier>) real[1]);
      user.sendPacketSilently(new WrapperPlayServerUpdateAttributes(player.getEntityId(),
          List.of(new WrapperPlayServerUpdateAttributes.Property(Attributes.BLOCK_BREAK_SPEED, value, mods))));
      return;
    }
    user.sendPacketSilently(new WrapperPlayServerRemoveEntityEffect(player.getEntityId(), PotionTypes.MINING_FATIGUE));
    PotionEffect real = FATIGUE == null ? null : player.getPotionEffect(FATIGUE);
    if (real != null) {
      int flags = (real.isAmbient() ? 1 : 0) | (real.hasParticles() ? 2 : 0) | (real.hasIcon() ? 4 : 0);
      user.sendPacketSilently(new WrapperPlayServerEntityEffect(player.getEntityId(), PotionTypes.MINING_FATIGUE,
          real.getAmplifier(), real.getDuration(), (byte) flags));
    }
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    if (event.getPacketType() != PacketType.Play.Server.UPDATE_ATTRIBUTES) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !hasBreakAttribute(proto.get()) || !BlockCatalog.clientNeedsCarriers(proto.get())) {
      return;
    }
    WrapperPlayServerUpdateAttributes pkt = new WrapperPlayServerUpdateAttributes(event);
    if (pkt.getEntityId() != player.getEntityId()) {
      return;
    }
    boolean dirty = false;
    for (WrapperPlayServerUpdateAttributes.Property prop : pkt.getProperties()) {
      if (prop.getAttribute() != Attributes.BLOCK_BREAK_SPEED) {
        continue;
      }
      realBreakSpeed.put(player.getUniqueId(), new Object[] {prop.getValue(), new ArrayList<>(prop.getModifiers())});
      if (slowed.contains(player.getUniqueId())) {
        prop.setValue(0.0);
        prop.setModifiers(new ArrayList<>());
        dirty = true;
      }
    }
    if (dirty) {
      event.markForReEncode(true);
    }
  }

  private void tick() {
    counter++;
    for (Map.Entry<UUID, Session> ent : sessions.entrySet()) {
      Player player = Bukkit.getPlayer(ent.getKey());
      Session ses = ent.getValue();
      if (player == null || !player.isOnline() || ses.block().getType().isAir()
          || player.getLocation().distanceSquared(ses.block().getLocation().add(0.5, 0.5, 0.5)) > 64) {
        abort(player, ent.getKey());
        continue;
      }
      if (counter < ses.notBefore()) {
        continue;
      }
      double progress = ses.progress() + ses.block().getBreakSpeed(player);
      if (progress >= 1.0) {
        Block block = ses.block();
        int before = SpigotConversionUtil.fromBukkitBlockData(block.getBlockData()).getGlobalId();
        if (plugin.getConfig().getBoolean("debug.mining", false)) {
          plugin.getLogger().info("mining " + player.getName() + " " + block.getType().name().toLowerCase(java.util.Locale.ROOT)
              + ": " + (counter - ses.started()) + " ticks since start (" + (ses.ticks() + 1) + " accruing), speed/tick "
              + String.format("%.4f", block.getBreakSpeed(player)));
        }
        lastBreak.put(ent.getKey(), counter);
        abort(player, ent.getKey());
        if (effects != null) {
          effects.serverBreak(player.getUniqueId(), true);
        }
        boolean broken;
        try {
          broken = player.breakBlock(block);
        } finally {
          if (effects != null) {
            effects.serverBreak(player.getUniqueId(), false);
          }
        }
        if (broken) {
          if (effects != null) {
            effects.breakFeedback(player, before, block.getLocation());
          }
        } else {
          refuse(player, block, 0);
        }
        continue;
      }
      int stage = (int) (progress * 10.0);
      if (stage != ses.stage()) {
        broadcastCrack(ses.block(), ses.crackId(), stage);
      }
      if (effects != null && ses.map() != null && ses.ticks() % 4 == 0) {
        effects.hitFeedback(player, ses.map(), ses.block().getLocation());
      }
      sessions.put(ent.getKey(), new Session(ses.block(), ses.crackId(), progress, stage, ses.map(), ses.ticks() + 1, ses.notBefore(), ses.started()));
    }
  }

  private void abort(Player player) {
    if (player != null) {
      abort(player, player.getUniqueId());
    }
  }

  private void abort(Player player, UUID id) {
    Session ses = sessions.remove(id);
    if (ses != null) {
      broadcastCrack(ses.block(), ses.crackId(), -1);
    }
    if (player != null) {
      speedUp(player);
    } else {
      slowed.remove(id);
    }
  }

  private static void broadcastCrack(Block block, int crackId, int stage) {
    Vector3i pos = new Vector3i(block.getX(), block.getY(), block.getZ());
    for (Player viewer : block.getWorld().getPlayers()) {
      if (viewer.getLocation().distanceSquared(block.getLocation()) > 32 * 32) {
        continue;
      }
      User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
      if (user != null) {
        ProxyEntities.sendOne(user, new WrapperPlayServerBlockBreakAnimation(crackId, pos, (byte) stage));
      }
    }
  }

  public void forget(UUID viewer) {
    sessions.remove(viewer);
    slowed.remove(viewer);
    realBreakSpeed.remove(viewer);
    lastBreak.remove(viewer);
  }
}
