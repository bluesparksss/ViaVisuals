package dev.bluesparksss.viavisuals.entity.anim;

import java.util.Map;

public final class PoseSolver {

  public static final double ROOT_Y = 1.501;

  private static final String[] FLOAT_KEYS = {"0.0", "0.25", "0.5", "0.75", "1.0"};

  public final RigData rig;
  public final double[] pose;
  public final boolean[] visible;
  public final boolean[] skip;
  public final double[] mat;

  private final double[] dynTop;
  private final double[] dynMode;
  private final double[] cs;
  private final double[] sn;

  public PoseSolver(RigData rig) {
    this.rig = rig;
    int n = rig.nodeCount();
    this.pose = new double[n * RigData.CH];
    this.visible = new boolean[n];
    this.skip = new boolean[n];
    this.mat = new double[n * 12];
    this.dynTop = new double[n * RigData.CH];
    this.dynMode = new double[n * RigData.CH];
    int maxOmega = 0;
    maxOmega = Math.max(maxOmega, maxOmega(rig.top));
    for (RigData.Flag f : rig.flags.values()) {
      for (RigData.Value v : f.values.values()) {
        if (v.mode != null) {
          maxOmega = Math.max(maxOmega, maxOmega(v.mode));
        }
      }
    }
    this.cs = new double[maxOmega];
    this.sn = new double[maxOmega];
  }

  private static int maxOmega(RigData.Dynamics d) {
    int m = 0;
    if (d.walk != null) {
      m = Math.max(m, d.walk.omega.length);
    }
    if (d.walkLevels != null) {
      for (RigData.Series s : d.walkLevels) {
        m = Math.max(m, s.omega.length);
      }
    }
    if (d.idle != null) {
      m = Math.max(m, d.idle.omega.length);
    }
    return m;
  }

  public void solve(double walkPos, double walkSpeed, double age, double yaw, double pitch, Map<String, ?> flags) {
    int n = rig.nodeCount();
    System.arraycopy(rig.pose0, 0, pose, 0, pose.length);
    System.arraycopy(rig.visible0, 0, visible, 0, n);
    System.arraycopy(rig.skip0, 0, skip, 0, n);
    java.util.Arrays.fill(dynTop, 0);
    RigData.Dynamics mode = null;
    double modeWeight = 0;
    java.util.Map<String, Double> active = rig.pairs.isEmpty() ? null : new java.util.HashMap<>();
    if (flags != null) {
      for (Map.Entry<String, ?> e : flags.entrySet()) {
        RigData.Flag flag = rig.flags.get(e.getKey());
        if (flag == null) {
          continue;
        }
        switch (flag.type) {
          case BOOLEAN, ENUM -> {
            RigData.Value v = flag.values.get(String.valueOf(e.getValue()));
            if (v != null) {
              applyDelta(v, 1.0);
              if (active != null) {
                active.put(flag.name + "=" + e.getValue(), 1.0);
              }
              if (v.mode != null && mode == null) {
                mode = v.mode;
                modeWeight = 1.0;
              }
            }
          }
          case FLOAT -> {
            double x = ((Number) e.getValue()).doubleValue();
            double[] w = new double[2];
            int lo = floatCell(x, w);
            RigData.Value a = flag.values.get(FLOAT_KEYS[lo]);
            RigData.Value b = flag.values.get(FLOAT_KEYS[Math.min(4, lo + 1)]);
            if (a != null) {
              applyDelta(a, w[0]);
            }
            if (b != null) {
              applyDelta(b, w[1]);
            }
            if (active != null) {
              double base = flag.base == null ? 0 : Double.parseDouble(flag.base);
              active.put(flag.name + "=1.0", Math.abs(1 - base) < 1e-6 ? x : (x - base) / (1 - base));
            }
            RigData.Value full = flag.values.get("1.0");
            if (full != null && full.mode != null && mode == null) {
              mode = full.mode;
              modeWeight = Math.max(0, Math.min(1, x));
            }
          }
        }
      }
    }
    if (active != null && active.size() > 1) {
      for (java.util.Map.Entry<String, Double> a : active.entrySet()) {
        java.util.List<RigData.Pair> list = rig.pairs.get(a.getKey());
        if (list == null) {
          continue;
        }
        for (RigData.Pair p : list) {
          Double wb = active.get(p.b);
          if (wb != null) {
            double w = a.getValue() * wb;
            for (int i = 0; i < p.chan.length; i++) {
              pose[p.chan[i]] += p.delta[i] * w;
            }
          }
        }
      }
    }
    addDynamics(rig.top, walkPos, walkSpeed, age, yaw, pitch, dynTop);
    if (mode != null) {
      java.util.Arrays.fill(dynMode, 0);
      addDynamics(mode, walkPos, walkSpeed, age, yaw, pitch, dynMode);
      for (int i = 0; i < pose.length; i++) {
        pose[i] += dynTop[i] + (dynMode[i] - dynTop[i]) * modeWeight;
      }
    } else {
      for (int i = 0; i < pose.length; i++) {
        pose[i] += dynTop[i];
      }
    }
  }

  private static int floatCell(double x, double[] w) {
    double c = x * 4;
    int lo = Math.max(0, Math.min(3, (int) Math.floor(c)));
    double f = c - lo;
    w[0] = 1 - f;
    w[1] = f;
    return lo;
  }

  private void applyDelta(RigData.Value v, double weight) {
    for (int i = 0; i < v.chan.length; i++) {
      pose[v.chan[i]] += v.delta[i] * weight;
    }
    if (weight >= 0.5) {
      for (int i = 0; i < v.visNode.length; i++) {
        visible[v.visNode[i]] = v.visVisible[i];
        skip[v.visNode[i]] = v.visSkip[i];
      }
    }
  }

  private void addDynamics(RigData.Dynamics d, double walkPos, double walkSpeed, double age, double yaw, double pitch,
                           double[] out) {
    if (d.walkLevels != null) {
      double sp = Math.max(0, Math.min(1, walkSpeed));
      int hi = 0;
      while (hi < d.walkLevels.length - 1 && d.walkLevels[hi].speed < sp) {
        hi++;
      }
      RigData.Series up = d.walkLevels[hi];
      RigData.Series lo = hi == 0 ? null : d.walkLevels[hi - 1];
      double loSpeed = lo == null ? 0 : lo.speed;
      double a = up.speed == loSpeed ? 1 : (sp - loSpeed) / (up.speed - loSpeed);
      up.add(walkPos, a, out, cs, sn);
      if (lo != null) {
        lo.add(walkPos, 1 - a, out, cs, sn);
      }
    } else if (d.walk != null) {
      d.walk.add(walkPos, walkSpeed, out, cs, sn);
    }
    if (d.idle != null) {
      d.idle.add(age, 1.0, out, cs, sn);
    }
    for (int i = 0; i < d.lookChan.length; i++) {
      out[d.lookChan[i]] += d.lookYaw[i] * yaw + d.lookPitch[i] * pitch;
    }
  }

  public void matrices() {
    int n = rig.nodeCount();
    for (int i = 0; i < n; i++) {
      int base = i * RigData.CH;
      double x = pose[base] / 16.0;
      double y = pose[base + 1] / 16.0;
      double z = pose[base + 2] / 16.0;
      double sx = pose[base + 6];
      double sy = pose[base + 7];
      double sz = pose[base + 8];
      double cx = Math.cos(pose[base + 3]);
      double sxr = Math.sin(pose[base + 3]);
      double cy = Math.cos(pose[base + 4]);
      double syr = Math.sin(pose[base + 4]);
      double cz = Math.cos(pose[base + 5]);
      double szr = Math.sin(pose[base + 5]);
      double r00 = cz * cy;
      double r01 = cz * syr * sxr - szr * cx;
      double r02 = cz * syr * cx + szr * sxr;
      double r10 = szr * cy;
      double r11 = szr * syr * sxr + cz * cx;
      double r12 = szr * syr * cx - cz * sxr;
      double r20 = -syr;
      double r21 = cy * sxr;
      double r22 = cy * cx;
      double l00 = r00 * sx;
      double l01 = r01 * sy;
      double l02 = r02 * sz;
      double l10 = r10 * sx;
      double l11 = r11 * sy;
      double l12 = r12 * sz;
      double l20 = r20 * sx;
      double l21 = r21 * sy;
      double l22 = r22 * sz;
      int m = i * 12;
      int p = rig.parent[i];
      if (p < 0) {
        mat[m] = l00;
        mat[m + 1] = l01;
        mat[m + 2] = l02;
        mat[m + 3] = x;
        mat[m + 4] = l10;
        mat[m + 5] = l11;
        mat[m + 6] = l12;
        mat[m + 7] = y;
        mat[m + 8] = l20;
        mat[m + 9] = l21;
        mat[m + 10] = l22;
        mat[m + 11] = z;
      } else {
        int q = p * 12;
        double a00 = mat[q];
        double a01 = mat[q + 1];
        double a02 = mat[q + 2];
        double a03 = mat[q + 3];
        double a10 = mat[q + 4];
        double a11 = mat[q + 5];
        double a12 = mat[q + 6];
        double a13 = mat[q + 7];
        double a20 = mat[q + 8];
        double a21 = mat[q + 9];
        double a22 = mat[q + 10];
        double a23 = mat[q + 11];
        mat[m] = a00 * l00 + a01 * l10 + a02 * l20;
        mat[m + 1] = a00 * l01 + a01 * l11 + a02 * l21;
        mat[m + 2] = a00 * l02 + a01 * l12 + a02 * l22;
        mat[m + 3] = a00 * x + a01 * y + a02 * z + a03;
        mat[m + 4] = a10 * l00 + a11 * l10 + a12 * l20;
        mat[m + 5] = a10 * l01 + a11 * l11 + a12 * l21;
        mat[m + 6] = a10 * l02 + a11 * l12 + a12 * l22;
        mat[m + 7] = a10 * x + a11 * y + a12 * z + a13;
        mat[m + 8] = a20 * l00 + a21 * l10 + a22 * l20;
        mat[m + 9] = a20 * l01 + a21 * l11 + a22 * l21;
        mat[m + 10] = a20 * l02 + a21 * l12 + a22 * l22;
        mat[m + 11] = a20 * x + a21 * y + a22 * z + a23;
      }
    }
  }
}
