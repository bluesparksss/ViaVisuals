package dev.bluesparksss.viavisuals.entity.anim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AnimatedMob {

  private static final Set<String> OMNIDIRECTIONAL = Set.of("allay", "bat", "bee", "blaze", "breeze", "ender_dragon",
      "ghast", "happy_ghast", "parrot", "phantom", "vex");

  public final String entityId;
  public final AnimCatalog.Appearance app;
  public final RigData rig;
  public final PoseSolver solver;
  public final boolean baby;
  public final int boneCount;
  public final int[] boneNode;
  private final boolean omnidirectional;

  public final float[] translation;
  public final float[] rotation;
  public final float[] scale;
  public final boolean[] shown;

  public boolean forced;
  public double forcedPos;
  public double forcedSpeed;
  public double forcedYaw;
  public double forcedPitch;
  public double forcedAge;
  public final Map<String, Object> forcedFlags = new HashMap<>();

  public final Map<String, Object> flags = new HashMap<>();

  public float walkMultiplier = 4f;
  public float walkMax = 1f;
  public float walkSmoothing = 0.4f;
  public float walkBabyScale = 3f;
  public final AnimDriver driver;

  private float walkSpeed;
  private float walkPosition;
  private double lastX;
  private double lastY;
  private double lastZ;
  private boolean haveLast;

  private float headYaw;
  private float headTarget;
  private int headSteps;
  private boolean haveHead;

  private int age;
  private final boolean[] nodeVisible;
  private final boolean[] nodeDraw;

  public AnimatedMob(String entityId, AnimCatalog.Appearance app, RigData rig, boolean baby, int ageSeed,
                     AnimCatalog catalog) {
    this.entityId = entityId;
    this.app = app;
    this.rig = rig;
    this.baby = baby;
    this.solver = new PoseSolver(rig);
    this.omnidirectional = OMNIDIRECTIONAL.contains(entityId);
    this.age = ageSeed;
    List<AnimCatalog.Bone> bones = app.bones();
    this.boneCount = bones.size();
    this.boneNode = new int[boneCount];
    for (int i = 0; i < boneCount; i++) {
      boneNode[i] = rig.indexOfPath(bones.get(i).path());
    }
    this.translation = new float[boneCount * 3];
    this.rotation = new float[boneCount * 4];
    this.scale = new float[boneCount * 3];
    this.shown = new boolean[boneCount];
    this.nodeVisible = new boolean[rig.nodeCount()];
    this.nodeDraw = new boolean[rig.nodeCount()];
    this.driver = AnimDrivers.create(entityId);
    this.driver.attach(this, catalog);
  }

  public void tickInputs(double x, double y, double z, boolean alive, boolean passenger) {
    age++;
    if (alive && !passenger && haveLast) {
      double dx = x - lastX;
      double dz = z - lastZ;
      double dy = omnidirectional ? y - lastY : 0;
      float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
      float target = Math.min(distance * walkMultiplier, walkMax);
      walkSpeed += (target - walkSpeed) * walkSmoothing;
      walkPosition += walkSpeed;
    } else if (!alive || passenger) {
      walkSpeed = 0;
      walkPosition = 0;
    }
    lastX = x;
    lastY = y;
    lastZ = z;
    haveLast = true;
    if (headSteps > 0) {
      headYaw += wrap(headTarget - headYaw) / headSteps;
      headSteps--;
    }
  }

  public void setHeadTarget(float yawDeg) {
    if (!haveHead) {
      headYaw = yawDeg;
      headTarget = yawDeg;
      haveHead = true;
      return;
    }
    headTarget = yawDeg;
    headSteps = 3;
  }

  public float headYaw() {
    return headYaw;
  }

  public boolean hasHead() {
    return haveHead;
  }

  public double walkPosition() {
    return walkPosition * (baby ? walkBabyScale : 1.0);
  }

  public double walkSpeed() {
    return Math.min(walkSpeed, 1.0f);
  }

  public void tickDriver(org.bukkit.entity.Entity ent, int[] events, int eventCount) {
    driver.tickWith(ent, events, eventCount);
  }

  public int age() {
    return age;
  }

  public static float wrap(float deg) {
    float d = deg % 360f;
    if (d >= 180f) {
      d -= 360f;
    }
    if (d < -180f) {
      d += 360f;
    }
    return d;
  }

  public void solve(float bodyYaw, float pitch, float roll, Runnable extraPose) {
    float relYaw = haveHead ? wrap(headYaw - bodyYaw) : 0f;
    Map<String, Object> inputFlags = flags;
    if (!forcedFlags.isEmpty()) {
      inputFlags = new HashMap<>(flags);
      inputFlags.putAll(forcedFlags);
    }
    if (forced) {
      solver.solve(forcedPos, forcedSpeed, forcedAge, forcedYaw, forcedPitch, inputFlags);
    } else {
      solver.solve(walkPosition(), walkSpeed(), age, relYaw, pitch, inputFlags);
    }
    driver.apply(solver.pose);
    driver.applyForced(solver.pose);
    driver.visibility(solver);
    if (extraPose != null) {
      extraPose.run();
    }
    solver.matrices();
    fillTransforms(bodyYaw, roll);
  }

  private void fillTransforms(float bodyYawDeg, float rollDeg) {
    int n = rig.nodeCount();
    for (int i = 0; i < n; i++) {
      int p = rig.parent[i];
      nodeVisible[i] = solver.visible[i] && (p < 0 || nodeVisible[p]);
      nodeDraw[i] = nodeVisible[i] && !solver.skip[i];
    }
    double yaw = Math.toRadians(-bodyYawDeg);
    double cy = Math.cos(yaw);
    double sy = Math.sin(yaw);
    double roll = Math.toRadians(rollDeg);
    double cr = Math.cos(roll);
    double sr = Math.sin(roll);
    double e00 = cy * cr;
    double e01 = -cy * sr;
    double e02 = sy;
    double e10 = sr;
    double e11 = cr;
    double e12 = 0;
    double e20 = -sy * cr;
    double e21 = sy * sr;
    double e22 = cy;
    double frameY = app.frameY();
    for (int b = 0; b < boneCount; b++) {
      int node = boneNode[b];
      if (node < 0 || !nodeDraw[node]) {
        shown[b] = false;
        continue;
      }
      shown[b] = true;
      int m = node * 12;
      double[] mat = solver.mat;
      double a00 = mat[m];
      double a01 = -mat[m + 1];
      double a02 = -mat[m + 2];
      double a10 = -mat[m + 4];
      double a11 = mat[m + 5];
      double a12 = mat[m + 6];
      double a20 = -mat[m + 8];
      double a21 = mat[m + 9];
      double a22 = mat[m + 10];
      double tx = mat[m + 3];
      double ty = frameY - mat[m + 7];
      double tz = -mat[m + 11];
      double s0 = Math.sqrt(a00 * a00 + a10 * a10 + a20 * a20);
      double s1 = Math.sqrt(a01 * a01 + a11 * a11 + a21 * a21);
      double s2 = Math.sqrt(a02 * a02 + a12 * a12 + a22 * a22);
      double inv0 = s0 < 1e-9 ? 0 : 1 / s0;
      double inv1 = s1 < 1e-9 ? 0 : 1 / s1;
      double inv2 = s2 < 1e-9 ? 0 : 1 / s2;
      double r00 = a00 * inv0;
      double r10 = a10 * inv0;
      double r20 = a20 * inv0;
      double r01 = a01 * inv1;
      double r11 = a11 * inv1;
      double r21 = a21 * inv1;
      double r02 = a02 * inv2;
      double r12 = a12 * inv2;
      double r22 = a22 * inv2;
      double det = r00 * (r11 * r22 - r12 * r21) - r01 * (r10 * r22 - r12 * r20) + r02 * (r10 * r21 - r11 * r20);
      if (det < 0) {
        s0 = -s0;
        r00 = -r00;
        r10 = -r10;
        r20 = -r20;
      }
      double l00 = e00 * r00 + e01 * r10 + e02 * r20;
      double l01 = e00 * r01 + e01 * r11 + e02 * r21;
      double l02 = e00 * r02 + e01 * r12 + e02 * r22;
      double l10 = e10 * r00 + e11 * r10 + e12 * r20;
      double l11 = e10 * r01 + e11 * r11 + e12 * r21;
      double l12 = e10 * r02 + e11 * r12 + e12 * r22;
      double l20 = e20 * r00 + e21 * r10 + e22 * r20;
      double l21 = e20 * r01 + e21 * r11 + e22 * r21;
      double l22 = e20 * r02 + e21 * r12 + e22 * r22;
      translation[b * 3] = (float) (e00 * tx + e01 * ty + e02 * tz);
      translation[b * 3 + 1] = (float) (e10 * tx + e11 * ty + e12 * tz);
      translation[b * 3 + 2] = (float) (e20 * tx + e21 * ty + e22 * tz);
      float inv = 1f / app.bones().get(b).fit();
      scale[b * 3] = (float) s0 * inv;
      scale[b * 3 + 1] = (float) s1 * inv;
      scale[b * 3 + 2] = (float) s2 * inv;
      quaternion(l00, l01, l02, l10, l11, l12, l20, l21, l22, rotation, b * 4);
    }
  }

  static void quaternion(double m00, double m01, double m02, double m10, double m11, double m12, double m20, double m21,
                         double m22, float[] out, int at) {
    double trace = m00 + m11 + m22;
    double x;
    double y;
    double z;
    double w;
    if (trace > 0) {
      double s = Math.sqrt(trace + 1.0) * 2;
      w = 0.25 * s;
      x = (m21 - m12) / s;
      y = (m02 - m20) / s;
      z = (m10 - m01) / s;
    } else if (m00 > m11 && m00 > m22) {
      double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2;
      w = (m21 - m12) / s;
      x = 0.25 * s;
      y = (m01 + m10) / s;
      z = (m02 + m20) / s;
    } else if (m11 > m22) {
      double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2;
      w = (m02 - m20) / s;
      x = (m01 + m10) / s;
      y = 0.25 * s;
      z = (m12 + m21) / s;
    } else {
      double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2;
      w = (m10 - m01) / s;
      x = (m02 + m20) / s;
      y = (m12 + m21) / s;
      z = 0.25 * s;
    }
    double len = Math.sqrt(x * x + y * y + z * z + w * w);
    if (w < 0) {
      len = -len;
    }
    out[at] = (float) (x / len);
    out[at + 1] = (float) (y / len);
    out[at + 2] = (float) (z / len);
    out[at + 3] = (float) (w / len);
  }
}
