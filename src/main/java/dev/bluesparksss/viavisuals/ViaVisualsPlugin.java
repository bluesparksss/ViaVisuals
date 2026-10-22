package dev.bluesparksss.viavisuals;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import dev.bluesparksss.viavisuals.block.BlockCatalog;
import dev.bluesparksss.viavisuals.block.BlockDisplays;
import dev.bluesparksss.viavisuals.block.BlockEffects;
import dev.bluesparksss.viavisuals.block.BlockMining;
import dev.bluesparksss.viavisuals.block.BlockPacketListener;
import dev.bluesparksss.viavisuals.block.CarrierResync;
import dev.bluesparksss.viavisuals.block.DisplayOcclusion;
import dev.bluesparksss.viavisuals.world.HeightShiftListener;
import dev.bluesparksss.viavisuals.world.HeightWindow;
import dev.bluesparksss.viavisuals.command.CommandTreeRewriter;
import dev.bluesparksss.viavisuals.command.ViaVisualsCommand;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import dev.bluesparksss.viavisuals.entity.EntityCatalog;
import dev.bluesparksss.viavisuals.entity.EntityProxies;
import dev.bluesparksss.viavisuals.entity.anim.AnimCatalog;
import dev.bluesparksss.viavisuals.item.ItemCatalog;
import dev.bluesparksss.viavisuals.item.ItemTranslator;
import dev.bluesparksss.viavisuals.listener.ItemPacketListener;
import dev.bluesparksss.viavisuals.mechanics.MechanicsManager;
import dev.bluesparksss.viavisuals.pack.PackManager;
import dev.bluesparksss.viavisuals.sound.SoundPacketListener;
import dev.bluesparksss.viavisuals.version.ClientResync;
import dev.bluesparksss.viavisuals.version.ConnectionDetailsListener;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class ViaVisualsPlugin extends JavaPlugin implements Listener {

    private PackManager packMgr;
    private MechanicsManager mechMgr;
    private BlockDisplays blockDisplays;
    private BlockCatalog blockCat;
    private EntityProxies entityProxies;
    private HeightWindow heightWindow;
    private BlockMining blockMining;
    private CarrierResync carrierResync;
    private ClientResync clientResync;
    private dev.bluesparksss.viavisuals.item.ArmorTrims armorTrims;
    private dev.bluesparksss.viavisuals.block.LightResync lightResync;
    private dev.bluesparksss.viavisuals.hud.LocatorBar locatorBar;
    private dev.bluesparksss.viavisuals.entity.MobUse mobUse;
    private ConnectionDetailsListener detailsListener;
    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null || PacketEvents.getAPI() == null) {
            getLogger().severe("packetevents is required but was not found or not initialized. Disabling ViaVisuals.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) {
            getLogger().severe("ViaVersion is required but was not found. Disabling ViaVisuals.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (!serverVersionsAgree()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("ViaBackwards") == null) {
            getLogger().warning("ViaBackwards was not found; clients older than the server cannot join at all.");
        }

        saveDefaultConfig();
        addMissingAnimationConfig();
        dropPackConfig();

        ItemCatalog items;
        BlockCatalog blocks;
        EntityCatalog entities;
        AnimCatalog animCatalog;
        try {
            try (InputStream in = resource("registry-since.json")) {
                RegistrySince.load(in);
            }
            try (InputStream in = resource("custom-items.json")) {
                items = ItemCatalog.load(in, getLogger());
            }
            try (InputStream in = resource("block-catalog.json")) {
                blocks = BlockCatalog.load(in, getLogger());
            }
            try (InputStream in = resource("entity-catalog.json")) {
                entities = EntityCatalog.load(in);
            }
            animCatalog = AnimCatalog.load(name -> {
                try {
                    return resource(name);
                } catch (IOException ex) {
                    return null;
                }
            });
        } catch (IOException | RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Failed to load ViaVisuals catalogs; disabling.", ex);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        blockCat = blocks;
        getLogger().info("Entity catalog: " + entities.size() + " entity type(s).");

        packMgr = new PackManager(this);
        getServer().getPluginManager().registerEvents(packMgr, this);
        getServer().getPluginManager().registerEvents(this, this);

        ItemTranslator xlat = new ItemTranslator(this, items);
        try (InputStream in = resource("armor-trims.json")) {
            armorTrims = dev.bluesparksss.viavisuals.item.ArmorTrims.load(in);
            armorTrims.configure(getConfig());
            xlat.setArmorTrims(armorTrims);
        } catch (IOException | RuntimeException ex) {
            getLogger().log(Level.WARNING, "Armor trims disabled: could not load armor-trims.json", ex);
        }
        ItemPacketListener itemPackets = new ItemPacketListener(this, xlat);
        mechMgr = new MechanicsManager(this, xlat);
        itemPackets.setHandContext(mechMgr.spears());
        mechMgr.reload(getConfig());
        getServer().getPluginManager().registerEvents(mechMgr.spears(), this);
        getServer().getPluginManager().registerEvents(mechMgr.windExplosionListener(), this);
        getServer().getPluginManager().registerEvents(mechMgr.enderPearlMomentumListener(), this);

        blockDisplays = new BlockDisplays();
        blockDisplays.setRange(getConfig().getDouble("blocks.display-view-distance", 48));
        blockDisplays.start(this);
        dev.bluesparksss.viavisuals.block.ShelfDisplays shelves = new dev.bluesparksss.viavisuals.block.ShelfDisplays(this, xlat);
        shelves.start();
        blockDisplays.setShelves(shelves);
        DisplayOcclusion occlusion = new DisplayOcclusion(blocks.serverVersion());
        blockDisplays.setOcclusion(occlusion, getConfig().getBoolean("blocks.occlusion-culling", true));
        blockMining = new BlockMining(this, blocks);
        getServer().getPluginManager().registerEvents(new dev.bluesparksss.viavisuals.block.MiningDebug(this), this);
        EntityProxies proxies = new EntityProxies(this, entities, animCatalog);
        entityProxies = proxies;
        getServer().getPluginManager().registerEvents(proxies, this);

        register(itemPackets);
        lightResync = new dev.bluesparksss.viavisuals.block.LightResync(this);
        register(new BlockPacketListener(this, blocks, blockDisplays, occlusion, lightResync));
        heightWindow = new HeightWindow(this, blockDisplays);
        heightWindow.start(getConfig());
        getServer().getPluginManager().registerEvents(heightWindow, this);
        register(new HeightShiftListener(this, true));
        register(new HeightShiftListener(this, false));
        carrierResync = new CarrierResync(this, blocks);
        BlockEffects blockEffects = new BlockEffects(this, blocks, xlat);
        blockMining.setEffects(blockEffects);
        getServer().getPluginManager().registerEvents(blockEffects, this);
        register(blockEffects);
        register(blockMining);
        register(carrierResync);
        register(new dev.bluesparksss.viavisuals.block.CreativePick(this, blocks));
        dev.bluesparksss.viavisuals.block.CarrierUse carrierUse = new dev.bluesparksss.viavisuals.block.CarrierUse(this, blocks);
        getServer().getPluginManager().registerEvents(carrierUse, this);
        register(carrierUse);
        register(proxies);
        mobUse = new dev.bluesparksss.viavisuals.entity.MobUse(this, proxies.boxes());
        mobUse.configure(getConfig());
        getServer().getPluginManager().registerEvents(mobUse, this);
        register(mobUse);
        register(mechMgr.spears());
        register(mechMgr.useHold());
        packetListeners.add(PacketEvents.getAPI().getEventManager().registerListener(
            mechMgr.windChargeLegacyPacketListener(), com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL));
        register(new SoundPacketListener(packMgr::isPackLoaded));
        register(new CommandTreeRewriter());

        try (InputStream in = resource("locator-font.json")) {
            locatorBar = new dev.bluesparksss.viavisuals.hud.LocatorBar(this, dev.bluesparksss.viavisuals.hud.LocatorFont.load(in));
            locatorBar.configure(getConfig());
            getServer().getPluginManager().registerEvents(locatorBar, this);
            register(locatorBar);
        } catch (IOException | RuntimeException ex) {
            getLogger().log(Level.WARNING, "Locator bar disabled: could not load locator-font.json", ex);
        }
        clientResync = new ClientResync(this);
        clientResync.configure(getConfig());
        packMgr.setResync(clientResync);
        detailsListener = new ConnectionDetailsListener(this, packMgr, clientResync);
        detailsListener.configure(getConfig());
        detailsListener.register();

        ViaVisualsCommand command = new ViaVisualsCommand(this, items);
        var vv = getCommand("viavisuals");
        if (vv != null) {
            vv.setExecutor(command);
            vv.setTabCompleter(command);
        }
        getLogger().info("ViaVisuals enabled for clients 1.16 - " + RegistrySince.serverVersion().getName() + ".");
    }

    private boolean serverVersionsAgree() {
        var via = RegistrySince.serverVersion();
        var pe = PacketEvents.getAPI().getServerManager().getVersion();
        if (pe == null || pe.getProtocolVersion() < via.getVersion()) {
            getLogger().severe("The installed packetevents (" + (pe == null ? "unknown" : pe.getReleaseName())
                + ") is older than this server (" + via.getName() + "). Update packetevents (2.14.0+ for 26.3). Disabling ViaVisuals.");
            return false;
        }
        return true;
    }

    private void register(PacketListenerCommon listener) {
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        packetListeners.add(listener);
    }

    private void addMissingAnimationConfig() {
        boolean hasMobs = getConfig().contains("animated-mobs", true);
        boolean hasLegacy = getConfig().contains("animated-mobs-legacy", true);
        if (hasMobs && hasLegacy) {
            return;
        }
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                return;
            }
            String bundled = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
            java.nio.file.Path file = getDataFolder().toPath().resolve("config.yml");
            String cur = java.nio.file.Files.readString(file).replace("\r\n", "\n");
            String out;
            if (!hasMobs) {
                int from = bundled.indexOf("# Animated mobs:");
                int key = bundled.indexOf("  range:");
                int to = key < 0 ? -1 : bundled.indexOf('\n', key);
                if (from < 0 || to < 0) {
                    return;
                }
                out = cur + (cur.endsWith("\n") ? "\n" : "\n\n") + bundled.substring(from, to + 1);
            } else {
                int from = bundled.indexOf("# Also animate them for clients below 1.19.4");
                int key = bundled.indexOf("animated-mobs-legacy:");
                int to = key < 0 ? -1 : bundled.indexOf('\n', key);
                int at = cur.indexOf("\nanimated-mobs:");
                int atEnd = at < 0 ? -1 : cur.indexOf('\n', at + 1);
                if (from < 0 || to < 0 || atEnd < 0) {
                    return;
                }
                out = cur.substring(0, atEnd + 1) + "\n" + bundled.substring(from, to + 1) + cur.substring(atEnd + 1);
            }
            java.nio.file.Files.writeString(file, out);
            reloadConfig();
            getLogger().info("Added the missing animated-mobs options to config.yml.");
        } catch (IOException ex) {
            getLogger().warning("Could not add the animated-mobs options to config.yml: " + ex.getMessage());
        }
    }

    private InputStream resource(String name) throws IOException {
        InputStream in = getResource(name);
        if (in == null) {
            throw new IOException(name + " missing from the plugin jar");
        }
        return in;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        blockDisplays.forget(event.getPlayer().getUniqueId());
        blockMining.forget(event.getPlayer().getUniqueId());
        carrierResync.forget(event.getPlayer().getUniqueId());
        lightResync.forget(event.getPlayer().getUniqueId());
        mechMgr.useHold().forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        blockDisplays.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        blockDisplays.forget(event.getPlayer().getUniqueId());
    }

    public BlockDisplays blockDisplays() {
        return blockDisplays;
    }

    public BlockCatalog blockCatalog() {
        return blockCat;
    }

    public EntityProxies entityProxies() {
        return entityProxies;
    }

    public void reloadViaVisualsConfig() {
        reloadConfig();
        if (blockDisplays != null) {
            blockDisplays.setRange(getConfig().getDouble("blocks.display-view-distance", 48));
        }
        if (heightWindow != null) {
            heightWindow.configure(getConfig());
        }
        if (blockDisplays != null && blockDisplays.occlusion() != null) {
            blockDisplays.setOcclusion(blockDisplays.occlusion(), getConfig().getBoolean("blocks.occlusion-culling", true));
        }
        if (mechMgr != null) {
            mechMgr.reload(getConfig());
        }
        if (clientResync != null) {
            clientResync.configure(getConfig());
        }
        if (entityProxies != null) {
            entityProxies.configure(getConfig());
        }
        if (mobUse != null) {
            mobUse.configure(getConfig());
        }
        if (locatorBar != null) {
            locatorBar.configure(getConfig());
        }
        if (armorTrims != null) {
            armorTrims.configure(getConfig());
        }
        if (detailsListener != null) {
            detailsListener.configure(getConfig());
        }
    }

    public dev.bluesparksss.viavisuals.hud.LocatorBar locatorBar() {
        return locatorBar;
    }

    public ClientResync clientResync() {
        return clientResync;
    }

    public PackManager packManager() {
        return packMgr;
    }

    private void dropPackConfig() {
        if (getConfig().isSet("resource-packs") || getConfig().isSet("items.new-spawn-egg-textures")) {
            getConfig().set("resource-packs", null);
            getConfig().set("items.new-spawn-egg-textures", null);
            if (getConfig().isConfigurationSection("items") && getConfig().getConfigurationSection("items").getKeys(false).isEmpty()) {
                getConfig().set("items", null);
            }
            saveConfig();
            getLogger().info("Removed the old resource-packs settings from config.yml: the pack is no longer configurable.");
        }
        java.io.File cache = new java.io.File(getDataFolder(), "cache");
        if (new java.io.File(cache, "viavisuals.zip").delete()) {
            cache.delete();
        }
    }

    @Override
    public void onDisable() {
        if (detailsListener != null) {
            detailsListener.unregister();
        }
        for (PacketListenerCommon listener : packetListeners) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        packetListeners.clear();
    }
}
