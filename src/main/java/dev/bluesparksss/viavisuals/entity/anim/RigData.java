package dev.bluesparksss.viavisuals.entity.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RigData {

  public static final int CH = 9;

  public final String id;
  public final String[] paths;
  public final String[] names;
  public final int[] parent;
  public final double[] rest;
  public final double[] pose0;
  public final boolean[] visible0;
  public final boolean[] skip0;
  public final Dynamics top;
  public final Map<String, Flag> flags;
  public final Map<String, List<Pair>> pairs = new HashMap<>();
  public final Map<String, String> keyframes;
  public final float shadow;
  private final Map<String, Integer> byPath = new HashMap<>();
  private final Map<String, Integer> byName = new HashMap<>();

  public static final class Dynamics {
    final Series walk;
    final Series[] walkLevels;
    final Series idle;
    final int[] lookChan;
    final double[] lookYaw;
    final double[] lookPitch;

    Dynamics(Series walk, Series[] walkLevels, Series idle, int[] lookChan, double[] lookYaw, double[] lookPitch) {
      this.walk = walk;
      this.walkLevels = walkLevels;
      this.idle = idle;
      this.lookChan = lookChan;
      this.lookYaw = lookYaw;
      this.lookPitch = lookPitch;
    }
  }

  public static final class Series {
    final double speed;
    final double[] omega;
    final int[] chan;
    final double[][] coef;

    Series(double speed, double[] omega, int[] chan, double[][] coef) {
      this.speed = speed;
      this.omega = omega;
      this.chan = chan;
      this.coef = coef;
    }

    void add(double t, double scale, double[] out, double[] cs, double[] sn) {
      for (int k = 0; k < omega.length; k++) {
        cs[k] = Math.cos(omega[k] * t);
        sn[k] = Math.sin(omega[k] * t);
      }
      for (int i = 0; i < chan.length; i++) {
        double[] cf = coef[i];
        double v = cf[0] + cf[1] * t;
        for (int k = 0; k < omega.length; k++) {
          v += cf[2 + 2 * k] * cs[k] + cf[3 + 2 * k] * sn[k];
        }
        out[chan[i]] += scale * v;
      }
    }
  }

  public static final class Pair {
    final String a;
    final String b;
    final double aBase;
    final double bBase;
    final int[] chan;
    final double[] delta;

    Pair(String a, String b, double aBase, double bBase, int[] chan, double[] delta) {
      this.a = a;
      this.b = b;
      this.aBase = aBase;
      this.bBase = bBase;
      this.chan = chan;
      this.delta = delta;
    }
  }

  public enum FlagType { BOOLEAN, FLOAT, ENUM }

  public static final class Flag {
    public final String name;
    public final FlagType type;
    public final String base;
    public final Map<String, Value> values = new LinkedHashMap<>();

    Flag(String name, FlagType type, String base) {
      this.name = name;
      this.type = type;
      this.base = base;
    }
  }

  public static final class Value {
    final int[] chan;
    final double[] delta;
    final int[] visNode;
    final boolean[] visVisible;
    final boolean[] visSkip;
    final Dynamics mode;

    Value(int[] chan, double[] delta, int[] visNode, boolean[] visVisible, boolean[] visSkip, Dynamics mode) {
      this.chan = chan;
      this.delta = delta;
      this.visNode = visNode;
      this.visVisible = visVisible;
      this.visSkip = visSkip;
      this.mode = mode;
    }
  }

  private RigData(String id, List<String> paths, List<String> names, List<Integer> parent, List<double[]> rest,
                  List<double[]> pose, List<boolean[]> vis, JsonObject doc) {
    this.id = id;
    int n = paths.size();
    this.paths = paths.toArray(new String[0]);
    this.names = names.toArray(new String[0]);
    this.parent = parent.stream().mapToInt(Integer::intValue).toArray();
    this.rest = new double[n * CH];
    this.pose0 = new double[n * CH];
    this.visible0 = new boolean[n];
    this.skip0 = new boolean[n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(rest.get(i), 0, this.rest, i * CH, CH);
      System.arraycopy(pose.get(i), 0, this.pose0, i * CH, CH);
      visible0[i] = vis.get(i)[0];
      skip0[i] = vis.get(i)[1];
      byPath.put(this.paths[i], i);
    }
    fillNameIndex();
    this.top = dynamics(doc);
    this.flags = new LinkedHashMap<>();
    if (doc.has("flags") && doc.get("flags").isJsonObject()) {
      for (Map.Entry<String, JsonElement> fe : doc.getAsJsonObject("flags").entrySet()) {
        flags.put(fe.getKey(), parseFlag(fe.getKey(), fe.getValue().getAsJsonObject()));
      }
    }
    this.shadow = doc.has("shadow") ? doc.get("shadow").getAsFloat() : 0f;
    if (doc.has("pairs") && doc.get("pairs").isJsonArray()) {
      for (JsonElement pe : doc.getAsJsonArray("pairs")) {
        JsonObject po = pe.getAsJsonObject();
        List<Integer> chans = new ArrayList<>();
        List<Double> deltas = new ArrayList<>();
        for (Map.Entry<String, JsonElement> part : po.getAsJsonObject("d").entrySet()) {
          for (Map.Entry<String, JsonElement> ce : part.getValue().getAsJsonObject().entrySet()) {
            chans.add(chan(part.getKey(), ce.getKey()));
            deltas.add(ce.getValue().getAsDouble());
          }
        }
        Pair pair = new Pair(po.get("a").getAsString(), po.get("b").getAsString(), po.get("aBase").getAsDouble(),
            po.get("bBase").getAsDouble(), chans.stream().mapToInt(Integer::intValue).toArray(),
            deltas.stream().mapToDouble(Double::doubleValue).toArray());
        pairs.computeIfAbsent(pair.a, k -> new ArrayList<>()).add(pair);
      }
    }
    this.keyframes = new LinkedHashMap<>();
    if (doc.has("keyframes") && doc.get("keyframes").isJsonObject()) {
      for (Map.Entry<String, JsonElement> ke : doc.getAsJsonObject("keyframes").entrySet()) {
        keyframes.put(ke.getKey(), ke.getValue().getAsString());
      }
    }
  }

  private void fillNameIndex() {
    byName.put("root", 0);
    addChildNames(0);
  }

  private void addChildNames(int node) {
    List<Integer> kids = new ArrayList<>();
    for (int i = 0; i < paths.length; i++) {
      if (parent[i] == node) {
        kids.add(i);
        byName.putIfAbsent(names[i], i);
      }
    }
    for (int k : kids) {
      addChildNames(k);
    }
  }

  public int nodeCount() {
    return paths.length;
  }

  public int indexOfPath(String path) {
    Integer idx = byPath.get(path);
    return idx == null ? -1 : idx;
  }

  public int indexOfName(String name) {
    Integer idx = byName.get(name);
    return idx == null ? -1 : idx;
  }

  public static RigData parse(String id, JsonObject doc) {
    List<String> paths = new ArrayList<>();
    List<String> names = new ArrayList<>();
    List<Integer> parent = new ArrayList<>();
    List<double[]> rest = new ArrayList<>();
    List<double[]> pose = new ArrayList<>();
    List<boolean[]> vis = new ArrayList<>();
    walk(doc.getAsJsonObject("root"), "", -1, paths, names, parent, rest, pose, vis);
    return new RigData(id, paths, names, parent, rest, pose, vis, doc);
  }

  private static void walk(JsonObject node, String path, int parent, List<String> paths, List<String> names,
                           List<Integer> parents, List<double[]> rest, List<double[]> pose, List<boolean[]> vis) {
    int self = paths.size();
    paths.add(path);
    names.add(node.get("name").getAsString());
    parents.add(parent);
    rest.add(doubles(node.getAsJsonArray("rest"), CH));
    pose.add(node.has("pose") ? doubles(node.getAsJsonArray("pose"), CH) : doubles(node.getAsJsonArray("rest"), CH));
    vis.add(new boolean[] {node.get("visible").getAsBoolean(), node.get("skipDraw").getAsBoolean()});
    for (JsonElement kid : node.getAsJsonArray("children")) {
      JsonObject kidObj = kid.getAsJsonObject();
      walk(kidObj, path + "/" + kidObj.get("name").getAsString(), self, paths, names, parents, rest, pose, vis);
    }
  }

  private static double[] doubles(JsonArray arr, int len) {
    double[] out = new double[len];
    for (int i = 0; i < len && i < arr.size(); i++) {
      out[i] = arr.get(i).getAsDouble();
    }
    return out;
  }

  private int chan(String path, String channel) {
    int node = indexOfPath(path);
    if (node < 0) {
      throw new IllegalStateException("rig " + id + ": unknown part '" + path + "'");
    }
    return node * CH + Integer.parseInt(channel);
  }

  private Series series(JsonObject o, double speed) {
    if (o == null || o.isJsonNull() || !o.has("terms")) {
      return null;
    }
    JsonArray om = o.getAsJsonArray("omega");
    double[] omega = new double[om.size()];
    for (int i = 0; i < omega.length; i++) {
      omega[i] = om.get(i).getAsDouble();
    }
    List<Integer> chans = new ArrayList<>();
    List<double[]> coefs = new ArrayList<>();
    int width = 2 + 2 * omega.length;
    for (Map.Entry<String, JsonElement> pe : o.getAsJsonObject("terms").entrySet()) {
      for (Map.Entry<String, JsonElement> ce : pe.getValue().getAsJsonObject().entrySet()) {
        chans.add(chan(pe.getKey(), ce.getKey()));
        coefs.add(doubles(ce.getValue().getAsJsonArray(), width));
      }
    }
    if (chans.isEmpty()) {
      return null;
    }
    return new Series(speed, omega, chans.stream().mapToInt(Integer::intValue).toArray(),
        coefs.toArray(new double[0][]));
  }

  private Dynamics dynamics(JsonObject doc) {
    Series walk = series(obj(doc, "walk"), 1.0);
    Series[] levels = null;
    if (doc.has("walkLevels") && doc.get("walkLevels").isJsonArray()) {
      JsonArray lv = doc.getAsJsonArray("walkLevels");
      levels = new Series[lv.size()];
      for (int i = 0; i < levels.length; i++) {
        JsonObject lo = lv.get(i).getAsJsonObject();
        levels[i] = series(lo, lo.get("speed").getAsDouble());
      }
    }
    Series idle = series(obj(doc, "idle"), 1.0);
    List<Integer> lc = new ArrayList<>();
    List<double[]> lk = new ArrayList<>();
    JsonObject look = obj(doc, "look");
    if (look != null) {
      for (Map.Entry<String, JsonElement> pe : look.entrySet()) {
        for (Map.Entry<String, JsonElement> ce : pe.getValue().getAsJsonObject().entrySet()) {
          lc.add(chan(pe.getKey(), ce.getKey()));
          lk.add(doubles(ce.getValue().getAsJsonArray(), 2));
        }
      }
    }
    int[] lookChan = lc.stream().mapToInt(Integer::intValue).toArray();
    double[] yaw = new double[lookChan.length];
    double[] pitch = new double[lookChan.length];
    for (int i = 0; i < yaw.length; i++) {
      yaw[i] = lk.get(i)[0];
      pitch[i] = lk.get(i)[1];
    }
    return new Dynamics(walk, levels, idle, lookChan, yaw, pitch);
  }

  private static JsonObject obj(JsonObject doc, String key) {
    return doc.has(key) && doc.get(key).isJsonObject() ? doc.getAsJsonObject(key) : null;
  }

  private Flag parseFlag(String name, JsonObject fe) {
    FlagType type = FlagType.valueOf(fe.get("type").getAsString().toUpperCase(java.util.Locale.ROOT));
    String base = fe.has("base") && !fe.get("base").isJsonNull() ? fe.get("base").getAsString() : null;
    Flag flag = new Flag(name, type, base);
    for (Map.Entry<String, JsonElement> ve : fe.getAsJsonObject("values").entrySet()) {
      JsonObject vo = ve.getValue().getAsJsonObject();
      List<Integer> chans = new ArrayList<>();
      List<Double> deltas = new ArrayList<>();
      for (Map.Entry<String, JsonElement> pe : vo.getAsJsonObject("d").entrySet()) {
        for (Map.Entry<String, JsonElement> ce : pe.getValue().getAsJsonObject().entrySet()) {
          chans.add(chan(pe.getKey(), ce.getKey()));
          deltas.add(ce.getValue().getAsDouble());
        }
      }
      List<Integer> vn = new ArrayList<>();
      List<boolean[]> vf = new ArrayList<>();
      if (vo.has("vis")) {
        for (Map.Entry<String, JsonElement> pe : vo.getAsJsonObject("vis").entrySet()) {
          JsonArray a = pe.getValue().getAsJsonArray();
          vn.add(indexOfPath(pe.getKey()));
          vf.add(new boolean[] {a.get(0).getAsDouble() > 0.5, a.get(1).getAsDouble() > 0.5});
        }
      }
      boolean[] visVisible = new boolean[vn.size()];
      boolean[] visSkip = new boolean[vn.size()];
      for (int i = 0; i < vn.size(); i++) {
        visVisible[i] = vf.get(i)[0];
        visSkip[i] = vf.get(i)[1];
      }
      Dynamics mode = vo.has("mode") ? dynamics(vo.getAsJsonObject("mode")) : null;
      flag.values.put(ve.getKey(), new Value(chans.stream().mapToInt(Integer::intValue).toArray(),
          deltas.stream().mapToDouble(Double::doubleValue).toArray(), vn.stream().mapToInt(Integer::intValue).toArray(),
          visVisible, visSkip, mode));
    }
    return flag;
  }
}
