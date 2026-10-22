package dev.bluesparksss.viavisuals.block;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.bluesparksss.viavisuals.compat.RegistrySince;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class BlockCatalog {

  public record Mapping(ProtocolVersion since, int clientState, int displayCmd, Material displayProxy, float yaw,
                        boolean cullable, String sound, String carrierSound, String blockId, boolean untintedParticle,
                        int fallingState, int lightState, boolean emits) {

    public boolean hasDisplay() {
      return displayCmd > 0;
    }
  }

  private final ClientVersion serverVer;
  private final Map<Integer, Mapping> byState = new HashMap<>();
  private final Map<Integer, String> blockOfCmd = new HashMap<>();
  private final Map<Integer, Integer> canonical = new HashMap<>();
  private int structureVoid;

  private BlockCatalog(ClientVersion serverVer) {
    this.serverVer = serverVer;
  }

  public static BlockCatalog load(InputStream input, Logger logger) throws IOException {
    JsonObject root;
    try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      root = JsonParser.parseReader(reader).getAsJsonObject();
    }
    ClientVersion serverVer = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
    BlockCatalog cat = new BlockCatalog(serverVer);
    cat.structureVoid = cat.stateId("structure_void");
    int mapped = 0;
    int missing = 0;
    java.util.List<String> unresolved = new java.util.ArrayList<>();
    for (Map.Entry<String, JsonElement> blk : root.getAsJsonObject("blocks").entrySet()) {
      String id = blk.getKey();
      JsonObject obj = blk.getValue().getAsJsonObject();
      ProtocolVersion since = RegistrySince.protocolFor(obj.get("since").getAsString());
      if (since == null || Material.matchMaterial(id) == null) {
        continue;
      }
      for (Map.Entry<String, JsonElement> st : obj.getAsJsonObject("states").entrySet()) {
        JsonObject ent = st.getValue().getAsJsonObject();
        int server = cat.stateId(st.getKey().isEmpty() ? id : id + "[" + st.getKey() + "]");
        String client = ent.get("client").getAsString();
        boolean display = ent.has("display");
        int clientState = "air".equals(client) && display ? cat.structureVoid : cat.stateId(client);
        if (server < 0 || clientState < 0) {
          missing++;
          if (unresolved.size() < 6) {
            unresolved.add((server < 0 ? id + "[" + st.getKey() + "]" : "client " + client));
          }
          continue;
        }
        Material proxy = display ? Material.matchMaterial(ent.get("proxy").getAsString()) : null;
        boolean cullable = display && ("air".equals(client) || "barrier".equals(client) || ent.has("invisible"));
        if (display) {
          cat.blockOfCmd.putIfAbsent(ent.get("display").getAsInt(), id);
        }
        cat.byState.put(server, new Mapping(since, clientState, display ? ent.get("display").getAsInt() : 0, proxy,
            ent.has("yaw") ? ent.get("yaw").getAsFloat() : 0f, cullable,
            obj.has("sound") ? obj.get("sound").getAsString() : null, carrierSound(client), id,
            client.contains("_leaves") && !id.equals("mangrove_leaves"),
            ent.has("falling") ? cat.stateId(ent.get("falling").getAsString()) : -1,
            lightCarrier(cat, obj, client, display), obj.has("light") && obj.get("light").getAsInt() > 0));
        mapped++;
      }
    }
    cat.buildCanonical(root);
    logger.info("Block catalog: " + mapped + " backported block state(s)"
        + (missing > 0 ? ", " + missing + " unresolved (e.g. " + String.join(", ", unresolved) + ")" : ""));
    return cat;
  }

  private static final ProtocolVersion LIGHT_BLOCK = ProtocolVersion.v1_17;

  private static int lightCarrier(BlockCatalog cat, JsonObject obj, String client, boolean display) {
    int level = obj.has("light") ? obj.get("light").getAsInt() : 0;
    if (!display || !"air".equals(client) || level <= 0) {
      return -1;
    }
    return cat.stateId("light[level=" + Math.min(level, 15) + ",waterlogged=false]");
  }

  public int carrierFor(Mapping map, ProtocolVersion client) {
    return map.lightState() >= 0 && client.newerThanOrEqualTo(LIGHT_BLOCK) ? map.lightState() : map.clientState();
  }

  static String carrierSound(String client) {
    int bracket = client.indexOf('[');
    String block = bracket < 0 ? client : client.substring(0, bracket);
    if (block.equals("note_block")) {
      return "block.wood";
    }
    if (block.endsWith("_leaves")) {
      return "block.grass";
    }
    if (block.contains("iron") || block.contains("cauldron") || block.contains("chain") || block.contains("lantern")
        || block.contains("bell") || block.contains("anvil")) {
      return "block.metal";
    }
    if (block.contains("carpet") || block.contains("wool")) {
      return "block.wool";
    }
    if (block.contains("glass")) {
      return "block.glass";
    }
    for (String wood : new String[] {"oak", "spruce", "birch", "jungle", "acacia", "crimson", "warped", "bookshelf",
        "barrel", "chest", "beehive", "bee_nest", "target"}) {
      if (block.contains(wood)) {
        return "block.wood";
      }
    }
    return "block.stone";
  }

  private void buildCanonical(JsonObject root) {
    int noteReserved = stateId("note_block[" + root.get("noteReserved").getAsString() + "]");
    for (WrappedBlockState st : allStates("note_block")) {
      int sid = st.getGlobalId();
      if (sid != noteReserved) {
        canonical.put(sid, noteReserved);
      }
    }
    String leavesCanon = root.get("leavesCanonical").getAsString();
    for (JsonElement leaf : root.getAsJsonArray("leavesCarriers")) {
      String block = leaf.getAsString();
      for (WrappedBlockState st : allStates(block)) {
        boolean water = st.toString().contains("waterlogged=true");
        int target = stateId(block + "[" + leavesCanon + (water ? ",waterlogged=true" : ",waterlogged=false") + "]");
        if (target < 0) {
          target = stateId(block + "[" + leavesCanon + "]");
        }
        if (target >= 0 && st.getGlobalId() != target) {
          canonical.put(st.getGlobalId(), target);
        }
      }
    }
    if (root.has("hidden")) {
      for (Map.Entry<String, JsonElement> hid : root.getAsJsonObject("hidden").entrySet()) {
        int carrier = stateId(hid.getKey());
        int twin = stateId(hid.getValue().getAsString());
        if (carrier >= 0 && twin >= 0 && carrier != twin) {
          canonical.put(carrier, twin);
        }
      }
    }
    if (root.has("spareCarriers")) {
      String prop = root.get("spareProp").getAsString();
      for (JsonElement el : root.getAsJsonArray("spareCarriers")) {
        String block = el.getAsString();
        for (WrappedBlockState st : allStates(block)) {
          String desc = props(st.toString());
          if (!desc.contains(prop + "=true")) {
            continue;
          }
          int target = stateId(block + "[" + desc.replace(prop + "=true", prop + "=false") + "]");
          if (target >= 0) {
            canonical.put(st.getGlobalId(), target);
          }
        }
      }
    }
    for (WrappedBlockState st : allStates("tripwire")) {
      String desc = st.toString();
      if (desc.contains("powered=true") || desc.contains("disarmed=true")) {
        int target = stateId("tripwire[" + props(desc).replace("powered=true", "powered=false")
            .replace("disarmed=true", "disarmed=false") + "]");
        if (target >= 0) {
          canonical.put(st.getGlobalId(), target);
        }
      }
    }
  }

  private final Map<String, List<WrappedBlockState>> statesOf = new HashMap<>();

  private List<WrappedBlockState> allStates(String block) {
    java.util.List<WrappedBlockState> out = new java.util.ArrayList<>();
    WrappedBlockState def = WrappedBlockState.getDefaultState(serverVer,
        com.github.retrooper.packetevents.protocol.world.states.type.StateTypes.getByName("minecraft:" + block));
    if (def == null) {
      return out;
    }
    int base = def.getGlobalId();
    int lo = base;
    while (lo > 0 && sameType(lo - 1, def)) {
      lo--;
    }
    for (int id = lo; sameType(id, def); id++) {
      out.add(WrappedBlockState.getByGlobalId(serverVer, id, false));
    }
    return out;
  }

  private boolean sameType(int id, WrappedBlockState def) {
    try {
      WrappedBlockState st = WrappedBlockState.getByGlobalId(serverVer, id, false);
      return st != null && st.getType() == def.getType();
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private static String props(String desc) {
    int open = desc.indexOf('[');
    int close = desc.lastIndexOf(']');
    return open < 0 || close < open ? "" : desc.substring(open + 1, close);
  }

  private int stateId(String desc) {
    int open = desc.indexOf('[');
    String block = open < 0 ? desc : desc.substring(0, open);
    try {
      WrappedBlockState fast = WrappedBlockState.getByString(serverVer, "minecraft:" + desc);
      if (fast != null && (fast.getGlobalId() != 0 || "air".equals(block))) {
        return fast.getGlobalId();
      }
    } catch (RuntimeException ignored) {
    }
    List<String> want = open < 0 || props(desc).isEmpty() ? List.of() : List.of(props(desc).split(","));
    WrappedBlockState best = null;
    String defProps = null;
    for (WrappedBlockState st : statesOf.computeIfAbsent(block, this::allStates)) {
      if (defProps == null) {
        WrappedBlockState def = WrappedBlockState.getDefaultState(serverVer,
            com.github.retrooper.packetevents.protocol.world.states.type.StateTypes.getByName("minecraft:" + block));
        defProps = props(def.toString());
      }
      List<String> have = List.of(props(st.toString()).split(","));
      if (!have.containsAll(want)) {
        continue;
      }
      if (best == null || defaultsMatched(have, defProps) > defaultsMatched(List.of(props(best.toString()).split(",")), defProps)) {
        best = st;
      }
    }
    return best == null ? -1 : best.getGlobalId();
  }

  private static int defaultsMatched(List<String> have, String defProps) {
    int n = 0;
    for (String kv : defProps.split(",")) {
      if (have.contains(kv)) {
        n++;
      }
    }
    return n;
  }

  public String blockOfCmd(int cmd) {
    return blockOfCmd.getOrDefault(cmd, "?");
  }

  public ClientVersion serverVersion() {
    return serverVer;
  }

  public Mapping mappingFor(int serverState, ProtocolVersion client) {
    Mapping map = byState.get(serverState);
    if (map == null || client.newerThanOrEqualTo(map.since())) {
      return null;
    }
    return map;
  }

  public int canonicalFor(int serverState) {
    Integer val = canonical.get(serverState);
    return val == null ? -1 : val;
  }

  public boolean isRelevant(int serverState) {
    return byState.containsKey(serverState) || canonical.containsKey(serverState);
  }

  public static boolean clientNeedsCarriers(ProtocolVersion client) {
    return client.olderThan(RegistrySince.serverVersion());
  }
}
