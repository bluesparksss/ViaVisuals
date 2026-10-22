package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleItemStackData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.item.ItemTranslator;
import dev.bluesparksss.viavisuals.version.VersionManager;
import dev.bluesparksss.viavisuals.world.HeightWindow;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BlockEffects extends PacketListenerAbstract implements Listener {

  private static final int WORLD_EVENT_BREAK = 2001;
  private static final int BURST = 28;

  private final JavaPlugin plugin;
  private final BlockCatalog catalog;
  private final ItemTranslator xlat;
  private final boolean debug;
  private final Set<UUID> serverBreaks = ConcurrentHashMap.newKeySet();

  public BlockEffects(JavaPlugin plugin, BlockCatalog catalog, ItemTranslator xlat) {
    super(PacketListenerPriority.HIGH);
    this.plugin = plugin;
    this.catalog = catalog;
    this.xlat = xlat;
    this.debug = plugin.getConfig().getBoolean("debug.block-effects", false);
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    if (event.getPacketType() != PacketType.Play.Server.EFFECT) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !BlockCatalog.clientNeedsCarriers(proto.get())) {
      return;
    }
    WrapperPlayServerEffect pkt = new WrapperPlayServerEffect(event);
    if (pkt.getType() != WORLD_EVENT_BREAK) {
      return;
    }
    BlockCatalog.Mapping map = catalog.mappingFor(pkt.getData(), proto.get());
    if (map == null) {
      return;
    }
    event.setCancelled(true);
    var pos = pkt.getPosition();
    Location at = new Location(player.getWorld(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    User user = event.getUser();
    ProtocolVersion client = proto.get();
    event.getTasksAfterSend().add(() -> burst(user, map, at, client));
    Bukkit.getScheduler().runTask(plugin, () -> sound(player, map, at, "break"));
  }

  public void breakFeedback(Player player, int serverState, Location block) {
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty()) {
      return;
    }
    BlockCatalog.Mapping map = catalog.mappingFor(serverState, proto.get());
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (map == null || user == null) {
      return;
    }
    Location at = block.clone().add(0.5, 0.5, 0.5);
    burst(user, map, at, proto.get());
    sound(player, map, at, "break");
  }

  public void serverBreak(UUID id, boolean on) {
    if (on) {
      serverBreaks.add(id);
    } else {
      serverBreaks.remove(id);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    Player player = event.getPlayer();
    if (serverBreaks.contains(player.getUniqueId())) {
      return;
    }
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !BlockCatalog.clientNeedsCarriers(proto.get())) {
      return;
    }
    Block block = event.getBlock();
    BlockCatalog.Mapping map = catalog.mappingFor(SpigotConversionUtil.fromBukkitBlockData(block.getBlockData()).getGlobalId(), proto.get());
    if (map == null) {
      return;
    }
    stopCarrier(player, map, "break");
    sound(player, map, block.getLocation().add(0.5, 0.5, 0.5), "break");
  }

  public void stopCarrier(Player player, BlockCatalog.Mapping map, String kind) {
    if (map.carrierSound() == null) {
      return;
    }
    String key = "minecraft:" + map.carrierSound() + "." + kind;
    if (debug) {
      plugin.getLogger().info("stop " + key + " for " + player.getName());
    }
    try {
      player.stopSound(key, SoundCategory.BLOCKS);
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.FINE, "stop sound failed", ex);
    }
  }

  public void hitFeedback(Player player, BlockCatalog.Mapping map, Location block) {
    stopCarrier(player, map, "hit");
    if (map.sound() == null) {
      return;
    }
    try {
      player.playSound(block.clone().add(0.5, 0.5, 0.5), "minecraft:" + map.sound().replaceAll("\\.break$", ".hit"),
          SoundCategory.BLOCKS, 0.25f, 0.5f);
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.FINE, "hit sound failed", ex);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    Player player = event.getPlayer();
    Optional<ProtocolVersion> proto = VersionManager.tryGetVisualVersion(player);
    if (proto.isEmpty() || !BlockCatalog.clientNeedsCarriers(proto.get())) {
      return;
    }
    Block placed = event.getBlockPlaced();
    int state = SpigotConversionUtil.fromBukkitBlockData(placed.getBlockData()).getGlobalId();
    BlockCatalog.Mapping map = catalog.mappingFor(state, proto.get());
    if (map != null) {
      sound(player, map, placed.getLocation().add(0.5, 0.5, 0.5), "place");
    }
  }

  private void sound(Player player, BlockCatalog.Mapping map, Location at, String kind) {
    if (map.sound() == null) {
      return;
    }
    String key = "minecraft:" + map.sound().replaceAll("\\.break$", "." + kind);
    if (debug) {
      plugin.getLogger().info("block " + kind + " sound " + key + " for " + player.getName());
    }
    try {
      player.playSound(at, key, SoundCategory.BLOCKS, 1.0f, 0.8f);
    } catch (RuntimeException ex) {
      plugin.getLogger().log(Level.FINE, "block sound failed", ex);
    }
  }

  private org.bukkit.inventory.ItemStack blockItem(BlockCatalog.Mapping map, ProtocolVersion client) {
    org.bukkit.Material mat = org.bukkit.Material.matchMaterial(map.blockId());
    return mat == null || client == null ? null : xlat.toClient(new org.bukkit.inventory.ItemStack(mat), client);
  }

  private void burst(User user, BlockCatalog.Mapping map, Location at, ProtocolVersion client) {
    Particle<?> particle;
    org.bukkit.inventory.ItemStack plain = map.untintedParticle() ? blockItem(map, client) : null;
    if (plain != null) {
      particle = new Particle<>(ParticleTypes.ITEM, new ParticleItemStackData(SpigotConversionUtil.fromBukkitItemStack(plain)));
    } else if (map.hasDisplay() && map.displayProxy() != null) {
      org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(map.displayProxy());
      ItemMeta meta = stack.getItemMeta();
      meta.setCustomModelData(map.displayCmd());
      stack.setItemMeta(meta);
      particle = new Particle<>(ParticleTypes.ITEM, new ParticleItemStackData(SpigotConversionUtil.fromBukkitItemStack(stack)));
    } else {
      WrappedBlockState carrier = WrappedBlockState.getByGlobalId(catalog.serverVersion(), map.clientState(), false);
      particle = new Particle<>(ParticleTypes.BLOCK, new ParticleBlockStateData(carrier));
    }
    int lo = HeightWindow.low(user);
    user.sendPacketSilently(new WrapperPlayServerParticle(particle, false, new Vector3d(at.getX(), at.getY() - lo, at.getZ()),
        new Vector3f(0.25f, 0.25f, 0.25f), 0.05f, BURST));
  }
}
