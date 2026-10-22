package dev.bluesparksss.viavisuals.entity.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class KeyframeAnim {

  private static final int POSITION = 0;
  private static final int ROTATION = 1;
  private static final int SCALE = 2;

  public final String id;
  public final double length;
  public final boolean looping;
  private final String[] bones;
  private final int[] target;
  private final double[][] time;
  private final double[][][] pre;
  private final double[][][] post;
  private final boolean[][] catmull;

  private KeyframeAnim(String id, double length, boolean looping, List<String> bones, List<Integer> targets,
                       List<double[]> time, List<double[][]> pre, List<double[][]> post, List<boolean[]> catmull) {
    this.id = id;
    this.length = length;
    this.looping = looping;
    this.bones = bones.toArray(new String[0]);
    this.target = targets.stream().mapToInt(Integer::intValue).toArray();
    this.time = time.toArray(new double[0][]);
    this.pre = pre.toArray(new double[0][][]);
    this.post = post.toArray(new double[0][][]);
    this.catmull = catmull.toArray(new boolean[0][]);
  }

  public static KeyframeAnim parse(JsonObject doc) {
    List<String> bones = new ArrayList<>();
    List<Integer> targets = new ArrayList<>();
    List<double[]> times = new ArrayList<>();
    List<double[][]> pres = new ArrayList<>();
    List<double[][]> posts = new ArrayList<>();
    List<boolean[]> cats = new ArrayList<>();
    for (Map.Entry<String, JsonElement> be : doc.getAsJsonObject("bones").entrySet()) {
      for (JsonElement ce : be.getValue().getAsJsonArray()) {
        JsonObject ch = ce.getAsJsonObject();
        JsonArray frames = ch.getAsJsonArray("keyframes");
        int n = frames.size();
        double[] t = new double[n];
        double[][] pr = new double[n][];
        double[][] po = new double[n][];
        boolean[] cat = new boolean[n];
        for (int i = 0; i < n; i++) {
          JsonObject f = frames.get(i).getAsJsonObject();
          t[i] = f.get("t").getAsDouble();
          pr[i] = vec(f.getAsJsonArray("pre"));
          po[i] = vec(f.getAsJsonArray("post"));
          cat[i] = f.get("interp").getAsString().equals("catmullrom");
        }
        bones.add(be.getKey());
        targets.add(switch (ch.get("target").getAsString()) {
          case "position" -> POSITION;
          case "rotation" -> ROTATION;
          default -> SCALE;
        });
        times.add(t);
        pres.add(pr);
        posts.add(po);
        cats.add(cat);
      }
    }
    return new KeyframeAnim(doc.get("id").getAsString(), doc.get("length").getAsDouble(),
        doc.get("looping").getAsBoolean(), bones, targets, times, pres, posts, cats);
  }

  private static double[] vec(JsonArray a) {
    return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
  }

  public int[] bind(RigData rig) {
    int[] nodes = new int[bones.length];
    for (int i = 0; i < nodes.length; i++) {
      nodes[i] = rig.indexOfName(bones[i]);
    }
    return nodes;
  }

  public void apply(int[] nodes, double millis, double scale, double[] pose) {
    double seconds = millis / 1000.0;
    if (looping) {
      seconds = seconds % length;
    }
    double[] v = new double[3];
    for (int c = 0; c < nodes.length; c++) {
      if (nodes[c] < 0) {
        continue;
      }
      double[] t = time[c];
      int len = t.length;
      int first = len;
      for (int i = 0; i < len; i++) {
        if (seconds <= t[i]) {
          first = i;
          break;
        }
      }
      int prev = Math.max(0, first - 1);
      int next = Math.min(len - 1, prev + 1);
      double alpha = 0;
      if (next != prev) {
        alpha = Math.max(0, Math.min(1, (seconds - t[prev]) / (t[next] - t[prev])));
      }
      if (catmull[c][next]) {
        for (int k = 0; k < 3; k++) {
          v[k] = catmullrom(alpha, post[c][Math.max(0, prev - 1)][k], post[c][prev][k], post[c][next][k],
              post[c][Math.min(len - 1, next + 1)][k]) * scale;
        }
      } else {
        for (int k = 0; k < 3; k++) {
          v[k] = (post[c][prev][k] + (pre[c][next][k] - post[c][prev][k]) * alpha) * scale;
        }
      }
      int base = nodes[c] * RigData.CH + target[c] * 3;
      pose[base] += v[0];
      pose[base + 1] += v[1];
      pose[base + 2] += v[2];
    }
  }

  private static double catmullrom(double alpha, double p0, double p1, double p2, double p3) {
    return 0.5 * (2.0 * p1 + (p2 - p0) * alpha + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * alpha * alpha
        + (3.0 * p1 - p0 - 3.0 * p2 + p3) * alpha * alpha * alpha);
  }
}
