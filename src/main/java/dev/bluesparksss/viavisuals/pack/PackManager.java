package dev.bluesparksss.viavisuals.pack;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.ViaVisualsPlugin;
import dev.bluesparksss.viavisuals.version.ClientProfiles;
import dev.bluesparksss.viavisuals.version.ClientResync;
import dev.bluesparksss.viavisuals.version.VersionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PackManager implements Listener {

  private static final Component PROMPT =
      LegacyComponentSerializer.legacyAmpersand().deserialize("&6ViaVisuals requires a custom resource pack!");
  private static final int JOIN_TICKS = 5;
  private static final int MAX_RETRIES = 2;
  private static final int LINK_WAITS = 15;
  private static final int ANSWER_TIMEOUT = 1200;

  private final ViaVisualsPlugin plugin;
  private final String url;
  private final byte[] hash;
  private final Map<UUID, Integer> retries = new ConcurrentHashMap<>();
  private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
  private final Set<UUID> answered = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Integer> linkWaits = new ConcurrentHashMap<>();
  private ClientResync resync;

  public PackManager(ViaVisualsPlugin plugin) {
    this.plugin = plugin;
    this.url = PackSource.url();
    this.hash = PackSource.hash();
    if (!url.equals(PackSource.URL)) {
      plugin.getLogger().info("Resource pack overridden by JVM property: " + url);
    }
  }

  public boolean isPackLoaded(UUID id) {
    return loaded.contains(id);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    loaded.remove(id);
    answered.remove(id);
    retries.remove(id);
    linkWaits.remove(id);
    ClientProfiles.forget(id);
    if (resync != null) {
      resync.forget(id);
    }
  }

  public void setResync(ClientResync resync) {
    this.resync = resync;
  }

  public void reassign(Player player) {
    retries.remove(player.getUniqueId());
    linkWaits.remove(player.getUniqueId());
    assignPack(player);
  }

  public void forceState(Player player, ClientProfiles.Pack state) {
    answered.add(player.getUniqueId());
    setState(player, state);
  }

  private void setState(Player player, ClientProfiles.Pack state) {
    if (ClientProfiles.setPack(player.getUniqueId(), state) && resync != null) {
      plugin.getLogger().info(player.getName() + " is now shown "
          + (state == ClientProfiles.Pack.FAILED ? "plain ViaBackwards output (the resource pack failed)" : "the ViaVisuals visuals"));
      resync.run(player);
    }
  }

  @EventHandler
  public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
    Player plr = event.getPlayer();
    UUID id = plr.getUniqueId();
    var status = event.getStatus();
    String name = status.name();
    answered.add(id);
    if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
      loaded.add(id);
      retries.remove(id);
      plugin.getLogger().info(plr.getName() + " successfully loaded the ViaVisuals resource pack.");
      setState(plr, ClientProfiles.Pack.LOADED);
      return;
    }
    loaded.remove(id);
    if (status == PlayerResourcePackStatusEvent.Status.ACCEPTED || name.equals("DOWNLOADED")) {
      return;
    }

    if (status == PlayerResourcePackStatusEvent.Status.DECLINED) {
      plugin.getLogger().warning(plr.getName() + " declined the ViaVisuals resource pack. Custom item visuals will not work.");
      setState(plr, ClientProfiles.Pack.FAILED);
      return;
    }

    if (status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
      int tries = retries.merge(id, 1, Integer::sum);
      if (tries <= MAX_RETRIES) {
        plugin.getLogger().info(plr.getName() + " failed to download the ViaVisuals resource pack; retry " + tries);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> assignPack(plr, true), 40L);
        return;
      }
      retries.remove(id);
      plugin.getLogger().warning(
          plr.getName()
              + " failed to download the ViaVisuals resource pack. "
              + "Check that the client can reach " + url + "."
      );
      setState(plr, ClientProfiles.Pack.FAILED);
      return;
    }

    if (status == PlayerResourcePackStatusEvent.Status.DISCARDED) {
      plugin.getLogger().warning(
          plr.getName() + " discarded the ViaVisuals resource pack after download (often hash mismatch or corrupt zip)."
      );
      setState(plr, ClientProfiles.Pack.FAILED);
      return;
    }

    plugin.getLogger().warning(plr.getName() + " resource pack status: " + status);
    if (name.equals("INVALID_URL") || name.equals("FAILED_RELOAD")) {
      setState(plr, ClientProfiles.Pack.FAILED);
    }
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    retries.remove(player.getUniqueId());
    linkWaits.remove(player.getUniqueId());
    plugin.getServer().getScheduler().runTaskLater(
        plugin,
        () -> assignPack(player, false),
        JOIN_TICKS
    );
  }

  private void assignPack(Player player) {
    assignPack(player, false);
  }

  private void assignPack(Player player, boolean force) {
    if (!player.isOnline()) {
      return;
    }

    try {
      java.util.Optional<ProtocolVersion> known = VersionManager.tryGetProtocolVersion(player);
      if (known.isEmpty()) {
        if (linkWaits.merge(player.getUniqueId(), 1, Integer::sum) <= LINK_WAITS) {
          plugin.getServer().getScheduler().runTaskLater(plugin, () -> assignPack(player, force), 20L);
        }
        return;
      }
      if (!VersionManager.isSupported(known.get())) {
        plugin.getLogger().fine(
            "Skipping resource pack for " + player.getName() + " (client below 1.16)"
        );
        return;
      }

      ProtocolVersion proto = known.get();
      if (!proto.olderThan(dev.bluesparksss.viavisuals.compat.RegistrySince.serverVersion())) {
        return;
      }
      ClientProfiles.Pack state = ClientProfiles.pack(player.getUniqueId());
      if (!force && (state == ClientProfiles.Pack.PENDING || state == ClientProfiles.Pack.LOADED)) {
        return;
      }

      sendPack(player, proto);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      plugin.getLogger().log(
          Level.WARNING,
          "Could not assign a resource pack to " + player.getName() + ": " + exception.getMessage(),
          exception
      );
    }
  }

  private void sendPack(Player player, ProtocolVersion proto) {
    UUID id = player.getUniqueId();
    answered.remove(id);
    ClientProfiles.setPack(id, ClientProfiles.Pack.PENDING);
    player.setResourcePack(UUID.nameUUIDFromBytes(hash), url, hash, PROMPT, true);
    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      Player plr = plugin.getServer().getPlayer(id);
      if (plr != null && !answered.contains(id) && ClientProfiles.pack(id) == ClientProfiles.Pack.PENDING) {
        plugin.getLogger().warning(plr.getName() + " never answered the ViaVisuals resource pack prompt.");
        setState(plr, ClientProfiles.Pack.FAILED);
      }
    }, ANSWER_TIMEOUT);
    plugin.getLogger().fine("Sent resource pack to " + player.getName() + " (" + proto.getName() + ")");
  }
}
