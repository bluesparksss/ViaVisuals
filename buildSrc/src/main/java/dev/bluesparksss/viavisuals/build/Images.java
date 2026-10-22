package dev.bluesparksss.viavisuals.build;

import java.awt.image.BufferedImage;

final class Images {

  private Images() {
  }

  /**
   * ARGB copy. A grayscale PNG is read from its raw samples: drawing it converts linear gray to sRGB, which makes
   * every texel about 1.5 times lighter than the gray the game multiplies a tint with.
   */
  static BufferedImage copy(BufferedImage src) {
    BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
    if (src.getColorModel().getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY) {
      java.awt.image.Raster raster = src.getRaster();
      int bands = raster.getNumBands();
      int shift = Math.max(0, src.getColorModel().getComponentSize(0) - 8);
      for (int y = 0; y < src.getHeight(); y++) {
        for (int x = 0; x < src.getWidth(); x++) {
          int gray = raster.getSample(x, y, 0) >> shift;
          int alpha = bands > 1 ? raster.getSample(x, y, 1) >> shift : 255;
          out.setRGB(x, y, (alpha << 24) | (gray << 16) | (gray << 8) | gray);
        }
      }
      return out;
    }
    out.getGraphics().drawImage(src, 0, 0, null);
    return out;
  }

  static BufferedImage multiply(BufferedImage src, int rgb) {
    BufferedImage out = copy(src);
    int tr = (rgb >> 16) & 0xFF;
    int tg = (rgb >> 8) & 0xFF;
    int tb = rgb & 0xFF;
    for (int y = 0; y < out.getHeight(); y++) {
      for (int x = 0; x < out.getWidth(); x++) {
        int argb = out.getRGB(x, y);
        int a = argb >>> 24;
        int r = ((argb >> 16) & 0xFF) * tr / 255;
        int g = ((argb >> 8) & 0xFF) * tg / 255;
        int b = (argb & 0xFF) * tb / 255;
        out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
      }
    }
    return out;
  }

  /** Alpha-composites {@code top} over {@code base}, scaling top to the base size when needed. */
  static BufferedImage over(BufferedImage base, BufferedImage top) {
    BufferedImage out = copy(base);
    java.awt.Graphics2D g = out.createGraphics();
    g.drawImage(top, 0, 0, base.getWidth(), base.getHeight(), null);
    g.dispose();
    return out;
  }

  /** Vanilla hurt overlay (OverlayTexture 0xB2FF0000, entity.fsh mix): color * 178/255 + red * 77/255. */
  static BufferedImage hurtOverlay(BufferedImage src) {
    BufferedImage out = copy(src);
    for (int y = 0; y < out.getHeight(); y++) {
      for (int x = 0; x < out.getWidth(); x++) {
        int argb = out.getRGB(x, y);
        int a = argb >>> 24;
        if (a == 0) {
          continue;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r = (r * 178 + 255 * 77) / 255;
        g = g * 178 / 255;
        b = b * 178 / 255;
        out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
      }
    }
    return out;
  }
}
