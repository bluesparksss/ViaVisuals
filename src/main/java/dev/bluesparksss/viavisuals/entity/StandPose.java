package dev.bluesparksss.viavisuals.entity;

import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;

public final class StandPose {

  public static final double NECK = 1.501 - 1.0 / 16.0;

  public record Pose(float headX, float headY, float headZ, double dx, double dy, double dz) {
  }

  private StandPose() {
  }

  public static Pose solve(Quaternion4f rot, Vector3f trans, float yaw, double standOff) {
    double[][] local = matrix(rot);
    double[][] disp = rotY(-yaw);
    double[][] world = mul(mul(disp, local), transpose(disp));
    double[] tw = apply(disp, new double[] {trans.getX(), trans.getY(), trans.getZ()});

    double[][] body = rotY(180.0 - yaw);
    double[][] flip = {{-1, 0, 0}, {0, -1, 0}, {0, 0, 1}};
    double[][] model = mul(mul(mul(mul(flip, transpose(body)), world), body), flip);
    double y = Math.asin(Math.max(-1, Math.min(1, -model[2][0])));
    double x = Math.atan2(model[2][1], model[2][2]);
    double z = Math.atan2(model[1][0], model[0][0]);

    double[] neck = apply(world, new double[] {0, standOff + NECK, 0});
    return new Pose((float) Math.toDegrees(x), (float) Math.toDegrees(y), (float) Math.toDegrees(z),
        tw[0] + neck[0], tw[1] + neck[1] - NECK, tw[2] + neck[2]);
  }

  private static double[][] matrix(Quaternion4f q) {
    double x = q.getX();
    double y = q.getY();
    double z = q.getZ();
    double w = q.getW();
    return new double[][] {
        {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
        {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
        {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)},
    };
  }

  private static double[][] rotY(double deg) {
    double r = Math.toRadians(deg);
    double c = Math.cos(r);
    double s = Math.sin(r);
    return new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
  }

  private static double[][] mul(double[][] a, double[][] b) {
    double[][] out = new double[3][3];
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        for (int k = 0; k < 3; k++) {
          out[i][j] += a[i][k] * b[k][j];
        }
      }
    }
    return out;
  }

  private static double[][] transpose(double[][] m) {
    double[][] out = new double[3][3];
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        out[i][j] = m[j][i];
      }
    }
    return out;
  }

  private static double[] apply(double[][] m, double[] v) {
    return new double[] {
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
    };
  }
}
