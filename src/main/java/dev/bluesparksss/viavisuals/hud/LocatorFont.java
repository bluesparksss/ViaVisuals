package dev.bluesparksss.viavisuals.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LocatorFont {

  public static final Key FONT = Key.key("viavisuals", "locator");

  public record Glyph(String name, char ch, int width, int height, int advance) {
  }

  public record SpriteStyle(int near, int far, List<String> sprites) {
  }

  public record AnimFrame(int index, int time) {
  }

  private final Map<String, Glyph> glyphs = new HashMap<>();
  private final Map<Integer, Character> spacers = new HashMap<>();
  private final Map<String, SpriteStyle> styles = new HashMap<>();
  private final List<AnimFrame> arrowAnim = new ArrayList<>();
  private final char digitBase;
  private final char digitSide;
  private final char digitUp;
  private final char digitDown;
  private final int digitAdvance;
  private final int barWidth;
  private final int dotSize;
  private final int arrowWidth;

  private LocatorFont(JsonObject root) {
    for (Map.Entry<String, JsonElement> ent : root.getAsJsonObject("glyphs").entrySet()) {
      JsonObject obj = ent.getValue().getAsJsonObject();
      glyphs.put(ent.getKey(), new Glyph(ent.getKey(), obj.get("char").getAsString().charAt(0), obj.get("width").getAsInt(),
          obj.get("height").getAsInt(), obj.get("advance").getAsInt()));
    }
    for (Map.Entry<String, JsonElement> ent : root.getAsJsonObject("spacers").entrySet()) {
      spacers.put(Integer.parseInt(ent.getKey()), ent.getValue().getAsString().charAt(0));
    }
    for (Map.Entry<String, JsonElement> ent : root.getAsJsonObject("styles").entrySet()) {
      JsonObject obj = ent.getValue().getAsJsonObject();
      List<String> sprites = new ArrayList<>();
      for (JsonElement el : obj.getAsJsonArray("sprites")) {
        sprites.add(el.getAsString());
      }
      styles.put(ent.getKey(), new SpriteStyle(obj.get("near").getAsInt(), obj.get("far").getAsInt(), sprites));
    }
    JsonArray anim = root.getAsJsonArray("arrowAnimation");
    for (JsonElement el : anim) {
      JsonObject obj = el.getAsJsonObject();
      arrowAnim.add(new AnimFrame(obj.get("index").getAsInt(), obj.get("time").getAsInt()));
    }
    JsonObject digits = root.getAsJsonObject("digits");
    digitBase = (char) digits.get("base").getAsInt();
    digitSide = (char) digits.get("side").getAsInt();
    digitUp = (char) digits.get("up").getAsInt();
    digitDown = (char) digits.get("down").getAsInt();
    digitAdvance = digits.get("advance").getAsInt();
    JsonObject layout = root.getAsJsonObject("layout");
    barWidth = layout.get("barWidth").getAsInt();
    dotSize = layout.get("dotSize").getAsInt();
    arrowWidth = layout.get("arrowWidth").getAsInt();
  }

  public static LocatorFont load(InputStream in) throws IOException {
    try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return new LocatorFont(JsonParser.parseReader(reader).getAsJsonObject());
    }
  }

  public Glyph glyph(String name) {
    return glyphs.get(name);
  }

  public SpriteStyle style(String name) {
    SpriteStyle st = styles.get(name);
    return st != null ? st : styles.get("default");
  }

  public int barWidth() {
    return barWidth;
  }

  public int dotSize() {
    return dotSize;
  }

  public int arrowWidth() {
    return arrowWidth;
  }

  public int arrowFrame(long tick) {
    int total = 0;
    for (AnimFrame frame : arrowAnim) {
      total += frame.time();
    }
    long at = tick % total;
    for (AnimFrame frame : arrowAnim) {
      if (at < frame.time()) {
        return frame.index();
      }
      at -= frame.time();
    }
    return 0;
  }

  public Run run() {
    return new Run();
  }

  public final class Run {
    private final List<Component> parts = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private TextColor color = NamedTextColor.WHITE;
    private int cursor;

    public int cursor() {
      return cursor;
    }

    public void moveTo(int x) {
      int delta = x - cursor;
      while (delta != 0) {
        int sign = delta > 0 ? 1 : -1;
        int mag = Math.abs(delta);
        int power = 1;
        while (power * 2 <= mag && spacers.containsKey(sign * power * 2)) {
          power *= 2;
        }
        append(spacers.get(sign * power), NamedTextColor.WHITE);
        delta -= sign * power;
      }
      cursor = x;
    }

    public void glyph(Glyph glyph, TextColor tint) {
      append(glyph.ch(), tint);
      cursor += glyph.advance();
    }

    public void digits(String number, TextColor tint, int dy, boolean outline) {
      char base = dy < 0 ? digitUp : dy > 0 ? digitDown : outline ? digitSide : digitBase;
      for (int i = 0; i < number.length(); i++) {
        append((char) (base + (number.charAt(i) - '0')), tint);
        cursor += digitAdvance;
      }
    }

    private void append(char ch, TextColor tint) {
      if (!tint.equals(color)) {
        flush();
        color = tint;
      }
      text.append(ch);
    }

    private void flush() {
      if (text.length() > 0) {
        parts.add(Component.text(text.toString(), Style.style(color).font(FONT)));
        text.setLength(0);
      }
    }

    public Component build() {
      flush();
      return Component.empty().children(parts);
    }
  }

  public int digitsWidth(String number) {
    return number.length() * digitAdvance;
  }
}
