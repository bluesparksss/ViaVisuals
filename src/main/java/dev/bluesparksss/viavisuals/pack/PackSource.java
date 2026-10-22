package dev.bluesparksss.viavisuals.pack;

import java.util.HexFormat;
import java.util.Locale;

public final class PackSource {

  public static final String URL = "https://github.com/bluesparksss/ViaVisuals-Pack/releases/download/v1/ViaVisuals-resource-pack.zip";
  public static final String SHA1 = "5dd376079889e05855df9edf31a613b362eb14de";

  private PackSource() {
  }

  public static String url() {
    String val = System.getProperty("viavisuals.pack.url");
    return val == null || val.isBlank() ? URL : val.trim();
  }

  public static byte[] hash() {
    String val = System.getProperty("viavisuals.pack.sha1");
    String hex = (val == null || val.isBlank() ? SHA1 : val.trim()).toLowerCase(Locale.ROOT);
    if (!hex.matches("[0-9a-f]{40}")) {
      throw new IllegalStateException("Resource pack SHA-1 must be 40 hex characters, got '" + hex + "'");
    }
    return HexFormat.of().parseHex(hex);
  }
}
