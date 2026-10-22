package dev.bluesparksss.viavisuals.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Converts baked vanilla ModelPart polygons (resource-pack-src/vanilla-geometry, dumped from the
 * client by tools/vanilla-models) into Java item-model elements.
 *
 * <p>Input positions are ModelPart space in blocks (y down for entities). {@link Space} maps them
 * into item space (pixels, y up). Face UVs are recovered exactly from the polygon corners: Java
 * faces list corners as (0,0) (0,1) (1,1) (1,0) in face-local right/down axes and assign
 * {u1,v1}{u1,v2}{u2,v2}{u2,v1} shifted by rotation/90.
 */
final class VanillaGeometry {

  enum Space {
    /** LivingEntityRenderer at yaw 0: scale(-1,-1,1), translate(0,-1.501,0), rotate Y 180. Front faces +z. */
    ENTITY,
    /** Block-entity renderers that draw ModelParts unflipped (chest, decorated pot). */
    BLOCK_ENTITY,
    /** Skull/head item definitions: transformation translation (0.5,0,0.5) + 180 degrees about X. */
    SKULL,
    /** AbstractBoatRenderer at yaw 0: translate(0,0.375,0), rotate Y 180, scale(-1,-1,1), rotate Y 90. */
    BOAT,
    /** CushionRenderer at yaw 0: rotate X 180, translate(0,-0.25,0) (no living-entity flip or 1.501 root). */
    CUSHION,
    /** Part-local cubes of an animated rig: the ENTITY flip about the part origin, which sits at the item centre (8,8,8). */
    BONE
  }

  /** {@code tex} overrides the cube texture variable for this face (null = use the caller's). */
  record Poly(double[][] pos, double[][] uv, double[] normal, String tex) {

    Poly(double[][] pos, double[][] uv, double[] normal) {
      this(pos, uv, normal, null);
    }
  }

  record Cube(String part, List<Poly> polys) {
  }

  private VanillaGeometry() {
  }

  static List<Cube> load(Path file) throws IOException {
    JsonObject doc;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      doc = JsonParser.parseReader(reader).getAsJsonObject();
    }
    List<Cube> out = new ArrayList<>();
    for (JsonElement cubeEl : doc.getAsJsonArray("cubes")) {
      JsonObject cube = cubeEl.getAsJsonObject();
      out.add(parseCube(cube, cube.get("part").getAsString()));
    }
    return out;
  }

  /** Cubes of one rig node (tools/vanilla-models DumpRig), in the node's own part space. */
  static List<Cube> cubesOfNode(JsonArray cubes, String part) {
    List<Cube> out = new ArrayList<>();
    for (JsonElement cubeEl : cubes) {
      out.add(parseCube(cubeEl.getAsJsonObject(), part));
    }
    return out;
  }

  private static Cube parseCube(JsonObject cube, String part) {
    List<Poly> polys = new ArrayList<>();
    for (JsonElement polyEl : cube.getAsJsonArray("polygons")) {
      JsonObject poly = polyEl.getAsJsonObject();
      JsonArray verts = poly.getAsJsonArray("vertices");
      double[][] pos = new double[verts.size()][3];
      double[][] uv = new double[verts.size()][2];
      for (int i = 0; i < verts.size(); i++) {
        JsonArray v = verts.get(i).getAsJsonArray();
        for (int k = 0; k < 3; k++) {
          pos[i][k] = v.get(k).getAsDouble();
        }
        uv[i][0] = v.get(3).getAsDouble();
        uv[i][1] = v.get(4).getAsDouble();
      }
      JsonArray n = poly.getAsJsonArray("normal");
      polys.add(new Poly(pos, uv, new double[] {n.get(0).getAsDouble(), n.get(1).getAsDouble(), n.get(2).getAsDouble()}));
    }
    return new Cube(part, polys);
  }

  /** Model-space (blocks) → item space (pixels, y up, centred on x/z = 8, feet at y = 0). */
  static double[] toItem(double[] p, Space space) {
    return switch (space) {
      case ENTITY -> new double[] {p[0] * 16 + 8, (1.501 - p[1]) * 16, -p[2] * 16 + 8};
      case BLOCK_ENTITY -> new double[] {p[0] * 16, p[1] * 16, p[2] * 16};
      case SKULL -> new double[] {p[0] * 16 + 8, -p[1] * 16, -p[2] * 16 + 8};
      case BOAT -> new double[] {p[2] * 16 + 8, (0.375 - p[1]) * 16, p[0] * 16 + 8};
      case CUSHION -> new double[] {-p[0] * 16 + 8, (0.25 - p[1]) * 16, p[2] * 16 + 8};
      case BONE -> new double[] {p[0] * 16 + 8, 8 - p[1] * 16, -p[2] * 16 + 8};
    };
  }

  static double[] dirToItem(double[] n, Space space) {
    return switch (space) {
      case ENTITY, BONE -> new double[] {n[0], -n[1], -n[2]};
      case BLOCK_ENTITY -> n.clone();
      case SKULL -> new double[] {n[0], -n[1], -n[2]};
      case BOAT -> new double[] {n[2], -n[1], n[0]};
      case CUSHION -> new double[] {-n[0], -n[1], n[2]};
    };
  }

  /**
   * Appends elements for every cube (optionally filtered by part path) using texture variable
   * {@code texVar}. {@code inflate} grows each element (pixels) to avoid z-fighting overlays.
   */
  static void appendElements(
      JsonArray elements,
      List<Cube> cubes,
      Space space,
      String texVar,
      double inflate,
      Predicate<String> partFilter
  ) {
    appendElements(elements, cubes, space, texVar, inflate, partFilter, false);
  }

  /** {@code exact}: keep each cube's true orientation as an Euler rotation (1.21.11+ model format). */
  static void appendElements(
      JsonArray elements,
      List<Cube> cubes,
      Space space,
      String texVar,
      double inflate,
      Predicate<String> partFilter,
      boolean exact
  ) {
    for (Cube cube : cubes) {
      if (partFilter != null && !partFilter.test(cube.part())) {
        continue;
      }
      List<JsonObject> voxels = exact ? null : voxelsIfPoorFit(cube, space, texVar, inflate);
      if (voxels != null) {
        voxels.forEach(elements::add);
        continue;
      }
      JsonObject elem = convertCube(cube, space, texVar, inflate, exact);
      if (elem != null) {
        elements.add(elem);
      }
    }
  }

  /** Boat cubes whose best single-axis rotation is further than this from the true orientation (degrees). */
  private static final double POOR_FIT_DEGREES = 25;
  /** "part residual" of every cube above {@link #REPORT_DEGREES}, for the build log. */
  static final List<String> POOR_FIT = new ArrayList<>();
  private static final double REPORT_DEGREES = 12;
  /** Cube faces that could not be expressed as an element face (should stay empty), for the build log. */
  static final List<String> DROPPED_FACES = new ArrayList<>();
  /** Extra edge length (pixels) and centre spacing (fraction of the thinnest side) of the small cubes. */
  private static final double VOXEL_GROW = 0.3;
  private static final double VOXEL_STEP = 0.55;

  private static final double[][] DIRS = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
  private static final String[] DIR_NAMES = {"north", "south", "west", "east", "down", "up"};

  /**
   * Legacy clients accept one axis and +-22.5/+-45 degrees per element. A cube tilted about several axes (boat
   * paddles) cannot be reproduced that way, so it is rebuilt as a staircase of small axis-aligned cubes that
   * follows its true shape; each small face samples the texture of the original face it points along.
   * Returns null when a single rotation fits well enough.
   */
  private static List<JsonObject> voxelsIfPoorFit(Cube cube, Space space, String texVar, double inflate) {
    List<double[][]> itemPos = new ArrayList<>();
    List<double[]> normals = new ArrayList<>();
    for (Poly poly : cube.polys()) {
      double[][] pts = new double[poly.pos().length][];
      for (int i = 0; i < pts.length; i++) {
        pts[i] = toItem(poly.pos()[i], space);
      }
      itemPos.add(pts);
      normals.add(normalize(dirToItem(poly.normal(), space)));
    }
    double[][] basis = cubeBasis(normals);
    double[] size = sizes(basis, itemPos);
    double[] weight = new double[3];
    for (int k = 0; k < 3; k++) {
      weight[k] = size[k] * size[k] + 1;
    }
    double residual = Rotation.fromBasis(basis, weight).residualDegrees(basis);
    if (residual > REPORT_DEGREES) {
      POOR_FIT.add(String.format("%s %.0f", cube.part(), residual));
    }
    if (residual <= POOR_FIT_DEGREES || space != Space.BOAT) {
      return null;
    }
    double[] center = centroid(itemPos);
    double thin = Math.max(1.0, Math.min(size[0], Math.min(size[1], size[2])));
    // cubes overlap their neighbours: a tilted lattice of touching cubes would show see-through gaps
    double edge = thin + VOXEL_GROW;
    int[] count = new int[3];
    double[] step = new double[3];
    for (int j = 0; j < 3; j++) {
      count[j] = size[j] <= thin * 1.5 ? 1 : (int) Math.ceil(size[j] / (thin * VOXEL_STEP) - 1e-6);
      step[j] = size[j] / count[j];
    }
    List<JsonObject> out = new ArrayList<>();
    for (int i = 0; i < count[0]; i++) {
      for (int j = 0; j < count[1]; j++) {
        for (int k = 0; k < count[2]; k++) {
          double[] off = {(i + 0.5 - count[0] / 2.0) * step[0], (j + 0.5 - count[1] / 2.0) * step[1],
              (k + 0.5 - count[2] / 2.0) * step[2]};
          double[] pos = center.clone();
          for (int a = 0; a < 3; a++) {
            for (int m = 0; m < 3; m++) {
              pos[m] += off[a] * basis[a][m];
            }
          }
          JsonObject faces = new JsonObject();
          for (int d = 0; d < 6; d++) {
            JsonObject face = voxelFace(pos, edge, thin * VOXEL_STEP, d, itemPos, normals, cube, texVar);
            if (face != null) {
              faces.add(DIR_NAMES[d], face);
            }
          }
          if (faces.isEmpty()) {
            continue;
          }
          double half = edge / 2 + inflate;
          JsonObject elem = new JsonObject();
          elem.add("from", vec(pos[0] - half, pos[1] - half, pos[2] - half));
          elem.add("to", vec(pos[0] + half, pos[1] + half, pos[2] + half));
          elem.add("faces", faces);
          out.add(elem);
        }
      }
    }
    return out.isEmpty() ? null : out;
  }

  /** One face of a small cube centred at {@code pos}: the UV rectangle the matching original face has there. */
  private static JsonObject voxelFace(double[] pos, double edge, double sample, int dir, List<double[][]> itemPos,
                                      List<double[]> normals, Cube cube, String texVar) {
    double[] d = DIRS[dir];
    int best = -1;
    double bestDot = 0.25;
    for (int p = 0; p < normals.size(); p++) {
      double dd = dot(normals.get(p), d);
      if (dd > bestDot && area(itemPos.get(p)) > 1e-6) {
        bestDot = dd;
        best = p;
      }
    }
    if (best < 0) {
      return null;
    }
    double[][] quad = itemPos.get(best);
    Poly src = cube.polys().get(best);
    double[] e1 = sub(quad[1], quad[0]);
    double[] e3 = sub(quad[3], quad[0]);
    double len1 = dot(e1, e1);
    double len3 = dot(e3, e3);
    double[] uv0 = src.uv()[0];
    double[] uv1 = src.uv()[1];
    double[] uv3 = src.uv()[3];
    int[] other = dir < 2 ? new int[] {0, 1} : dir < 4 ? new int[] {2, 1} : new int[] {0, 2};
    double minU = Double.MAX_VALUE;
    double minV = Double.MAX_VALUE;
    double maxU = -Double.MAX_VALUE;
    double maxV = -Double.MAX_VALUE;
    for (int sx = -1; sx <= 1; sx += 2) {
      for (int sy = -1; sy <= 1; sy += 2) {
        double[] corner = pos.clone();
        for (int m = 0; m < 3; m++) {
          corner[m] += d[m] * edge / 2;
        }
        // the texture area is the cell's own footprint (not the overlapping cube), so it stays inside the part
        corner[other[0]] += sx * sample / 2;
        corner[other[1]] += sy * sample / 2;
        double[] rel = sub(corner, quad[0]);
        double s = Math.max(0, Math.min(1, len1 < 1e-9 ? 0 : dot(rel, e1) / len1));
        double t = Math.max(0, Math.min(1, len3 < 1e-9 ? 0 : dot(rel, e3) / len3));
        double u = (uv0[0] + s * (uv1[0] - uv0[0]) + t * (uv3[0] - uv0[0])) * 16;
        double v = (uv0[1] + s * (uv1[1] - uv0[1]) + t * (uv3[1] - uv0[1])) * 16;
        minU = Math.min(minU, u);
        maxU = Math.max(maxU, u);
        minV = Math.min(minV, v);
        maxV = Math.max(maxV, v);
      }
    }
    // stay inside the original face's texture area (the texture is padded with transparent texels around it)
    double loU = Math.min(Math.min(uv0[0], uv1[0]), Math.min(uv3[0], uv0[0] + uv3[0] - uv0[0] + uv1[0] - uv0[0])) * 16;
    double hiU = Math.max(Math.max(uv0[0], uv1[0]), Math.max(uv3[0], uv0[0] + uv3[0] - uv0[0] + uv1[0] - uv0[0])) * 16;
    double loV = Math.min(Math.min(uv0[1], uv1[1]), Math.min(uv3[1], uv0[1] + uv3[1] - uv0[1] + uv1[1] - uv0[1])) * 16;
    double hiV = Math.max(Math.max(uv0[1], uv1[1]), Math.max(uv3[1], uv0[1] + uv3[1] - uv0[1] + uv1[1] - uv0[1])) * 16;
    minU = Math.max(minU, loU);
    maxU = Math.min(maxU, hiU);
    minV = Math.max(minV, loV);
    maxV = Math.min(maxV, hiV);
    if (maxU - minU < 0.06) {
      double mid = Math.max(loU + 0.03, Math.min(hiU - 0.03, (maxU + minU) / 2));
      minU = mid - 0.03;
      maxU = mid + 0.03;
    }
    if (maxV - minV < 0.06) {
      double mid = Math.max(loV + 0.03, Math.min(hiV - 0.03, (maxV + minV) / 2));
      minV = mid - 0.03;
      maxV = mid + 0.03;
    }
    JsonObject face = new JsonObject();
    face.add("uv", vec(minU, minV, maxU, maxV));
    face.addProperty("texture", src.tex() != null ? src.tex() : texVar);
    return face;
  }

  private static final double TWIN_EPS = 0.002;
  private static final String[][] OPPOSITE = {
      {"north", "south"}, {"south", "north"}, {"west", "east"}, {"east", "west"}, {"up", "down"}, {"down", "up"},
  };

  /**
   * Vanilla draws mobs with entityCutoutNoCull; item models are back-face culled. For every element
   * whose texture has see-through pixels, each textured face gets a back-facing twin (a zero-thickness
   * element in the same plane) so legs, fins and hair stay visible from behind like in vanilla.
   */
  static void addBackFaces(JsonArray elements, Function<String, BufferedImage> textures) {
    List<JsonObject> twins = new ArrayList<>();
    for (JsonElement el : elements) {
      JsonObject elem = el.getAsJsonObject();
      JsonObject faces = elem.getAsJsonObject("faces");
      boolean seeThrough = false;
      for (String dir : faces.keySet()) {
        seeThrough |= coverage(faces.getAsJsonObject(dir), textures) < 1.0;
      }
      if (!seeThrough) {
        continue;
      }
      for (String[] pair : OPPOSITE) {
        JsonObject face = faces.getAsJsonObject(pair[0]);
        if (face == null || coverage(face, textures) <= 0.0) {
          continue;
        }
        twins.add(twin(elem, pair[0], pair[1], face));
      }
    }
    twins.forEach(elements::add);
  }

  /** Removes faces whose texture area is fully transparent, then elements left without faces (tinted overlays). */
  static void dropTransparent(JsonArray elements, Function<String, BufferedImage> textures) {
    JsonArray keep = new JsonArray();
    for (JsonElement el : elements) {
      JsonObject elem = el.getAsJsonObject();
      JsonObject faces = elem.getAsJsonObject("faces");
      for (String dir : new ArrayList<>(faces.keySet())) {
        if (coverage(faces.getAsJsonObject(dir), textures) <= 0.0) {
          faces.remove(dir);
        }
      }
      if (!faces.isEmpty()) {
        keep.add(elem);
      }
    }
    while (elements.size() > 0) {
      elements.remove(0);
    }
    keep.forEach(elements::add);
  }

  /** Fraction of opaque texels under a face's UV rectangle (1 when the texture is unknown). */
  private static double coverage(JsonObject face, Function<String, BufferedImage> textures) {
    String var = face.get("texture").getAsString();
    BufferedImage img = textures.apply(var.startsWith("#") ? var.substring(1) : var);
    if (img == null) {
      return 1.0;
    }
    JsonArray uv = face.getAsJsonArray("uv");
    double sx = img.getWidth() / 16.0;
    double sy = img.getHeight() / 16.0;
    int x0 = (int) Math.floor(Math.min(uv.get(0).getAsDouble(), uv.get(2).getAsDouble()) * sx + 1e-6);
    int x1 = (int) Math.ceil(Math.max(uv.get(0).getAsDouble(), uv.get(2).getAsDouble()) * sx - 1e-6);
    int y0 = (int) Math.floor(Math.min(uv.get(1).getAsDouble(), uv.get(3).getAsDouble()) * sy + 1e-6);
    int y1 = (int) Math.ceil(Math.max(uv.get(1).getAsDouble(), uv.get(3).getAsDouble()) * sy - 1e-6);
    int total = 0;
    int opaque = 0;
    for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
      for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++) {
        total++;
        if ((img.getRGB(x, y) >>> 24) != 0) {
          opaque++;
        }
      }
    }
    return total == 0 ? 0.0 : opaque / (double) total;
  }

  private static JsonObject twin(JsonObject elem, String dir, String back, JsonObject face) {
    JsonArray from = elem.getAsJsonArray("from");
    JsonArray to = elem.getAsJsonArray("to");
    double[] lo = {from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble()};
    double[] hi = {to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble()};
    // just outside the source face, so it never ties with a touching neighbour's coplanar face
    switch (dir) {
      case "north" -> hi[2] = lo[2] = lo[2] - TWIN_EPS;
      case "south" -> lo[2] = hi[2] = hi[2] + TWIN_EPS;
      case "west" -> hi[0] = lo[0] = lo[0] - TWIN_EPS;
      case "east" -> lo[0] = hi[0] = hi[0] + TWIN_EPS;
      case "down" -> hi[1] = lo[1] = lo[1] - TWIN_EPS;
      default -> lo[1] = hi[1] = hi[1] + TWIN_EPS;
    }
    // Seen from behind the image is mirrored across the face's vertical axis (horizontal for up/down);
    // with a 90/270 face rotation that axis is the other texture axis.
    int rot = face.has("rotation") ? face.get("rotation").getAsInt() : 0;
    boolean horizontal = dir.equals("up") || dir.equals("down");
    boolean flipU = horizontal == (rot % 180 != 0);
    JsonArray uv = face.getAsJsonArray("uv");
    JsonObject out = face.deepCopy();
    out.add("uv", flipU
        ? vec(uv.get(2).getAsDouble(), uv.get(1).getAsDouble(), uv.get(0).getAsDouble(), uv.get(3).getAsDouble())
        : vec(uv.get(0).getAsDouble(), uv.get(3).getAsDouble(), uv.get(2).getAsDouble(), uv.get(1).getAsDouble()));
    out.remove("cullface");
    JsonObject faces = new JsonObject();
    faces.add(back, out);
    JsonObject res = new JsonObject();
    res.add("from", vec(lo));
    res.add("to", vec(hi));
    if (elem.has("rotation")) {
      res.add("rotation", elem.get("rotation").deepCopy());
    }
    res.add("faces", faces);
    return res;
  }

  /**
   * Legacy parsers reject element coordinates outside -16..32. Compresses the elements about the model
   * centre (8, 8, 8) when needed and returns the factor; display transforms must scale by 1/factor
   * (they scale about the same centre, so the rendered result is unchanged).
   */
  static double fitAroundCenter(JsonArray elements) {
    double reach = reachAroundCenter(elements);
    if (reach <= 24) {
      return 1.0;
    }
    double scale = 23.9 / reach;
    scaleAboutCenter(elements, scale);
    return scale;
  }

  /**
   * Enlarges the elements about the centre by up to {@code want} while they stay inside the legacy bounds; returns the
   * factor applied (at least 1). Display transforms must scale by 1/factor.
   */
  static double growAroundCenter(JsonArray elements, double want) {
    double reach = reachAroundCenter(elements);
    double scale = Math.min(want, reach <= 0 ? 1.0 : 23.9 / reach);
    if (scale <= 1.0001) {
      return 1.0;
    }
    scaleAboutCenter(elements, scale);
    return scale;
  }

  private static double reachAroundCenter(JsonArray elements) {
    double reach = 0;
    for (JsonElement el : elements) {
      JsonObject e = el.getAsJsonObject();
      for (String k : new String[] {"from", "to"}) {
        JsonArray v = e.getAsJsonArray(k);
        for (int i = 0; i < 3; i++) {
          reach = Math.max(reach, Math.abs(v.get(i).getAsDouble() - 8));
        }
      }
    }
    return reach;
  }

  private static void scaleAboutCenter(JsonArray elements, double scale) {
    for (JsonElement el : elements) {
      JsonObject e = el.getAsJsonObject();
      for (String k : new String[] {"from", "to"}) {
        e.add(k, aboutCenter(e.getAsJsonArray(k), scale));
      }
      if (e.has("rotation")) {
        JsonObject rot = e.getAsJsonObject("rotation");
        rot.add("origin", aboutCenter(rot.getAsJsonArray("origin"), scale));
      }
    }
  }

  private static JsonArray aboutCenter(JsonArray v, double scale) {
    return vec(8 + (v.get(0).getAsDouble() - 8) * scale, 8 + (v.get(1).getAsDouble() - 8) * scale,
        8 + (v.get(2).getAsDouble() - 8) * scale);
  }

  /** Multiplies every display context's scale by {@code factor} (legacy parsers clamp it to 4). */
  static JsonObject scaleDisplay(JsonObject display, double factor) {
    JsonObject out = display.deepCopy();
    for (String ctx : out.keySet()) {
      JsonObject t = out.getAsJsonObject(ctx);
      JsonArray sc = t.has("scale") ? t.getAsJsonArray("scale") : vec(1, 1, 1);
      t.add("scale", vec(Math.min(4, sc.get(0).getAsDouble() * factor), Math.min(4, sc.get(1).getAsDouble() * factor),
          Math.min(4, sc.get(2).getAsDouble() * factor)));
    }
    return out;
  }

  static JsonObject convertCube(Cube cube, Space space, String texVar, double inflate) {
    return convertCube(cube, space, texVar, inflate, false);
  }

  static JsonObject convertCube(Cube cube, Space space, String texVar, double inflate, boolean exact) {
    List<double[][]> itemPos = new ArrayList<>();
    List<double[]> normals = new ArrayList<>();
    for (Poly poly : cube.polys()) {
      double[][] pts = new double[poly.pos().length][];
      for (int i = 0; i < pts.length; i++) {
        pts[i] = toItem(poly.pos()[i], space);
      }
      itemPos.add(pts);
      normals.add(normalize(dirToItem(poly.normal(), space)));
    }

    // Recover the cube's own axes from its face normals; non axis-aligned cubes become a rotated element.
    double[][] basis = cubeBasis(normals);
    double[] size = sizes(basis, itemPos);
    double[] weight = new double[3];
    for (int k = 0; k < 3; k++) {
      weight[k] = size[k] * size[k] + 1;
    }
    Rotation rot = Rotation.fromBasis(basis, weight);
    if (exact) {
      rot = rot.exact(basis);
    }
    // Face corners and the element box are located in the cube's true frame (same axis assignment as the
    // snapped rotation): in the tilted frame of a snapped rotation two corners can collapse onto one vertex.
    Rotation frame = exact ? rot : rot.exact(basis);
    double[] center = centroid(itemPos);

    double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
    double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
    List<double[][]> local = new ArrayList<>();
    for (double[][] pts : itemPos) {
      double[][] lp = new double[pts.length][];
      for (int i = 0; i < pts.length; i++) {
        lp[i] = frame.unrotate(pts[i], center);
        for (int k = 0; k < 3; k++) {
          min[k] = Math.min(min[k], lp[i][k]);
          max[k] = Math.max(max[k], lp[i][k]);
        }
      }
      local.add(lp);
    }

    JsonObject faces = new JsonObject();
    for (int p = 0; p < local.size(); p++) {
      double[][] lp = local.get(p);
      double[] n = frame.unrotateDir(normals.get(p));
      String dir = direction(n);
      if (dir == null || area(lp) < 1e-6) {
        continue;
      }
      Poly src = cube.polys().get(p);
      JsonObject face = faceFor(dir, lp, src.uv(), min, max);
      if (face != null) {
        face.addProperty("texture", src.tex() != null ? src.tex() : texVar);
        faces.add(dir, face);
      } else {
        DROPPED_FACES.add(cube.part() + ":" + dir);
      }
    }
    if (faces.isEmpty()) {
      return null;
    }

    JsonObject elem = new JsonObject();
    elem.add("from", vec(min[0] - inflate, min[1] - inflate, min[2] - inflate));
    elem.add("to", vec(max[0] + inflate, max[1] + inflate, max[2] + inflate));
    if (rot.euler != null) {
      if (Math.abs(rot.euler[0]) + Math.abs(rot.euler[1]) + Math.abs(rot.euler[2]) > 1e-3) {
        JsonObject r = new JsonObject();
        r.add("origin", vec(center[0], center[1], center[2]));
        r.addProperty("x", Math.round(rot.euler[0] * 1000.0) / 1000.0);
        r.addProperty("y", Math.round(rot.euler[1] * 1000.0) / 1000.0);
        r.addProperty("z", Math.round(rot.euler[2] * 1000.0) / 1000.0);
        elem.add("rotation", r);
      }
    } else if (rot.angle != 0) {
      JsonObject r = new JsonObject();
      r.add("origin", vec(center[0], center[1], center[2]));
      r.addProperty("axis", rot.axis);
      r.addProperty("angle", rot.angle);
      elem.add("rotation", r);
    }
    elem.add("faces", faces);
    return elem;
  }

  /** Face-local axes (right, down) as seen from outside, matching vanilla FaceInfo corner order. */
  private static int[][] faceAxes(String dir) {
    // {axisIndex, sign} for right then down
    return switch (dir) {
      case "north" -> new int[][] {{0, -1}, {1, -1}};
      case "south" -> new int[][] {{0, 1}, {1, -1}};
      case "west" -> new int[][] {{2, 1}, {1, -1}};
      case "east" -> new int[][] {{2, -1}, {1, -1}};
      case "up" -> new int[][] {{0, 1}, {2, 1}};
      default -> new int[][] {{0, 1}, {2, -1}};
    };
  }

  private static JsonObject faceFor(String dir, double[][] pts, double[][] uvs, double[] min, double[] max) {
    int[][] axes = faceAxes(dir);
    // target uv at corners (s,t) in order (0,0) (0,1) (1,1) (1,0)
    double[][] target = new double[4][];
    double[][] corners = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
    for (int c = 0; c < 4; c++) {
      int best = -1;
      double bestD = Double.MAX_VALUE;
      for (int i = 0; i < pts.length; i++) {
        double s = localCoord(pts[i], axes[0], min, max);
        double t = localCoord(pts[i], axes[1], min, max);
        double dist = Math.abs(s - corners[c][0]) + Math.abs(t - corners[c][1]);
        if (dist < bestD) {
          bestD = dist;
          best = i;
        }
      }
      target[c] = new double[] {uvs[best][0] * 16, uvs[best][1] * 16};
    }
    for (int k = 0; k < 4; k++) {
      double[][] list = new double[4][];
      for (int j = 0; j < 4; j++) {
        list[j] = target[Math.floorMod(j - k, 4)];
      }
      if (near(list[0][0], list[1][0]) && near(list[2][0], list[3][0])
          && near(list[0][1], list[3][1]) && near(list[1][1], list[2][1])) {
        JsonObject face = new JsonObject();
        face.add("uv", vec(list[0][0], list[0][1], list[2][0], list[2][1]));
        if (k != 0) {
          face.addProperty("rotation", k * 90);
        }
        return face;
      }
    }
    return null;
  }

  private static double localCoord(double[] p, int[] axis, double[] min, double[] max) {
    int idx = axis[0];
    double span = max[idx] - min[idx];
    if (span < 1e-9) {
      return 0;
    }
    double frac = (p[idx] - min[idx]) / span;
    return axis[1] > 0 ? frac : 1 - frac;
  }

  private static boolean near(double a, double b) {
    return Math.abs(a - b) < 1e-3;
  }

  private static String direction(double[] n) {
    int idx = 0;
    for (int k = 1; k < 3; k++) {
      if (Math.abs(n[k]) > Math.abs(n[idx])) {
        idx = k;
      }
    }
    if (Math.abs(n[idx]) < 0.5) {
      return null;
    }
    return switch (idx) {
      case 0 -> n[0] > 0 ? "east" : "west";
      case 1 -> n[1] > 0 ? "up" : "down";
      default -> n[2] > 0 ? "south" : "north";
    };
  }

  private static double area(double[][] pts) {
    double[] a = sub(pts[1], pts[0]);
    double[] b = sub(pts[2], pts[0]);
    double[] c = cross(a, b);
    return Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
  }

  private static double[][] cubeBasis(List<double[]> normals) {
    double[] x = null;
    double[] y = null;
    for (double[] n : normals) {
      if (x == null) {
        x = n;
      } else if (Math.abs(dot(x, n)) < 0.5) {
        y = n;
        break;
      }
    }
    if (x == null || y == null) {
      return new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }
    return new double[][] {x, y, cross(x, y)};
  }

  /** Size of the cube along each of its own axes. */
  private static double[] sizes(double[][] basis, List<double[][]> polys) {
    double[] out = new double[3];
    for (int k = 0; k < 3; k++) {
      double lo = Double.MAX_VALUE;
      double hi = -Double.MAX_VALUE;
      for (double[][] pts : polys) {
        for (double[] p : pts) {
          double d = dot(p, basis[k]);
          lo = Math.min(lo, d);
          hi = Math.max(hi, d);
        }
      }
      out[k] = hi - lo;
    }
    return out;
  }

  private static double[] centroid(List<double[][]> polys) {
    double[] c = new double[3];
    int n = 0;
    for (double[][] pts : polys) {
      for (double[] p : pts) {
        for (int k = 0; k < 3; k++) {
          c[k] += p[k];
        }
        n++;
      }
    }
    for (int k = 0; k < 3; k++) {
      c[k] /= Math.max(1, n);
    }
    return c;
  }

  static JsonArray vec(double... vals) {
    JsonArray arr = new JsonArray();
    for (double v : vals) {
      arr.add(Math.round(v * 10000.0) / 10000.0);
    }
    return arr;
  }

  private static double[] sub(double[] a, double[] b) {
    return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
  }

  private static double[] cross(double[] a, double[] b) {
    return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
  }

  private static double dot(double[] a, double[] b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  }

  private static double[] normalize(double[] v) {
    double len = Math.sqrt(dot(v, v));
    return len < 1e-12 ? v : new double[] {v[0] / len, v[1] / len, v[2] / len};
  }

  /**
   * Single-axis element rotation approximating a cube's orientation. Rotations that are a pure
   * 90-degree permutation of the axes are absorbed exactly (angle 0); the residual is snapped to the
   * angles every client accepts (±22.5/±45).
   */
  static final class Rotation {

    final String axis;
    final double angle;
    private final double[][] m;
    private final double[][] sym;
    /** Exact rotation as Euler degrees for CuboidRotation.EulerXYZRotation (Rz * Ry * Rx), or null. */
    final double[] euler;

    private Rotation(String axis, double angle, double[][] m, double[][] sym) {
      this(axis, angle, m, sym, null);
    }

    private Rotation(String axis, double angle, double[][] m, double[][] sym, double[] euler) {
      this.axis = axis;
      this.angle = angle;
      this.m = m;
      this.sym = sym;
      this.euler = euler;
    }

    /** Same element-axis assignment, but the cube's true orientation instead of a legacy angle. */
    Rotation exact(double[][] basis) {
      double[][] target = {
          {basis[0][0], basis[1][0], basis[2][0]},
          {basis[0][1], basis[1][1], basis[2][1]},
          {basis[0][2], basis[1][2], basis[2][2]},
      };
      double[][] symT = new double[3][3];
      for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
          symT[i][j] = sym[j][i];
        }
      }
      double[][] rot = mul(target, symT);
      double y = Math.asin(Math.max(-1, Math.min(1, -rot[2][0])));
      double x = Math.atan2(rot[2][1], rot[2][2]);
      double z = Math.atan2(rot[1][0], rot[0][0]);
      return new Rotation("y", 0, rot, sym, new double[] {Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z)});
    }

    /** Angle (degrees) between the cube's true orientation and what this single-axis rotation reproduces. */
    double residualDegrees(double[][] basis) {
      double[][] target = {
          {basis[0][0], basis[1][0], basis[2][0]},
          {basis[0][1], basis[1][1], basis[2][1]},
          {basis[0][2], basis[1][2], basis[2][2]},
      };
      double[][] have = mul(m, sym);
      double trace = 0;
      for (int i = 0; i < 3; i++) {
        for (int k = 0; k < 3; k++) {
          trace += target[i][k] * have[i][k];
        }
      }
      return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (trace - 1) / 2))));
    }

    /** Element-local axis that cube basis axis {@code j} maps to. */
    int localAxis(int j) {
      for (int i = 0; i < 3; i++) {
        if (Math.abs(sym[i][j]) > 0.5) {
          return i;
        }
      }
      return j;
    }

    private static final double[] ANGLES = {-45.0, -22.5, 22.5, 45.0};
    private static final List<double[][]> CUBE_SYMMETRIES = cubeSymmetries();

    /**
     * Picks the single-axis legacy rotation R (times any of the 24 axis permutations, which an AABB
     * absorbs) closest to the cube orientation. {@code basis} rows are the cube's local axes in item space;
     * {@code weight} scales each axis' error by the cube's squared size along it, so the fit minimizes
     * vertex displacement (a long oar keeps its shaft direction rather than its thin axes).
     */
    static Rotation fromBasis(double[][] basis, double[] weight) {
      double[][] target = {
          {basis[0][0], basis[1][0], basis[2][0]},
          {basis[0][1], basis[1][1], basis[2][1]},
          {basis[0][2], basis[1][2], basis[2][2]},
      };
      double bestErr = Double.MAX_VALUE;
      Rotation best = new Rotation("y", 0, identity(), identity());
      for (double[][] sym : CUBE_SYMMETRIES) {
        double err = frob(target, sym, weight);
        if (err < bestErr - 1e-9) {
          bestErr = err;
          best = new Rotation("y", 0, identity(), sym);
        }
        for (String axis : new String[] {"x", "y", "z"}) {
          for (double ang : ANGLES) {
            double[][] rot = axisMatrix(axis, ang);
            double e = frob(target, mul(rot, sym), weight);
            if (e < bestErr - 1e-9) {
              bestErr = e;
              best = new Rotation(axis, ang, rot, sym);
            }
          }
        }
      }
      return best;
    }

    private static List<double[][]> cubeSymmetries() {
      List<double[][]> out = new ArrayList<>();
      int[][] perms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
      for (int[] perm : perms) {
        for (int signs = 0; signs < 8; signs++) {
          double[][] m = new double[3][3];
          for (int r = 0; r < 3; r++) {
            m[r][perm[r]] = ((signs >> r) & 1) == 1 ? -1 : 1;
          }
          if (det(m) > 0) {
            out.add(m);
          }
        }
      }
      return out;
    }

    private static double det(double[][] m) {
      return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
          - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
          + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
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

    private static double frob(double[][] a, double[][] b, double[] weight) {
      double s = 0;
      for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
          double d = a[i][j] - b[i][j];
          s += d * d * weight[j];
        }
      }
      return s;
    }

    private static double[][] identity() {
      return new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double[][] axisMatrix(String axis, double deg) {
      double r = Math.toRadians(deg);
      double c = Math.cos(r);
      double s = Math.sin(r);
      return switch (axis) {
        case "x" -> new double[][] {{1, 0, 0}, {0, c, -s}, {0, s, c}};
        case "y" -> new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
        default -> new double[][] {{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
      };
    }

    double[] unrotate(double[] p, double[] origin) {
      double[] d = {p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]};
      double[] r = unrotateDir(d);
      return new double[] {r[0] + origin[0], r[1] + origin[1], r[2] + origin[2]};
    }

    double[] unrotateDir(double[] d) {
      // transpose (inverse) of rotation matrix
      return new double[] {
          m[0][0] * d[0] + m[1][0] * d[1] + m[2][0] * d[2],
          m[0][1] * d[0] + m[1][1] * d[1] + m[2][1] * d[2],
          m[0][2] * d[0] + m[1][2] * d[1] + m[2][2] * d[2],
      };
    }
  }
}
