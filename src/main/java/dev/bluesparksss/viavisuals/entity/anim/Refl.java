package dev.bluesparksss.viavisuals.entity.anim;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class Refl {

  private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
  private static final Method NONE;

  static {
    try {
      NONE = Object.class.getMethod("hashCode");
    } catch (NoSuchMethodException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private Refl() {
  }

  static Object call(Object target, String name) {
    if (target == null) {
      return null;
    }
    Method m = METHODS.computeIfAbsent(target.getClass().getName() + "#" + name, key -> {
      try {
        Method found = target.getClass().getMethod(name);
        found.setAccessible(true);
        return found;
      } catch (ReflectiveOperationException | RuntimeException ex) {
        return NONE;
      }
    });
    if (m == NONE) {
      return null;
    }
    try {
      return m.invoke(target);
    } catch (ReflectiveOperationException | RuntimeException ex) {
      return null;
    }
  }

  static boolean bool(Object target, String name) {
    return call(target, name) instanceof Boolean b && b;
  }

  static double num(Object target, String name, double fallback) {
    return call(target, name) instanceof Number n ? n.doubleValue() : fallback;
  }

  static String enumName(Object target, String name) {
    Object val = call(target, name);
    return val instanceof Enum<?> e ? e.name() : "";
  }
}
