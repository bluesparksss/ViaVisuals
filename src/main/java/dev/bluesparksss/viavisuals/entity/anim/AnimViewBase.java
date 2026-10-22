package dev.bluesparksss.viavisuals.entity.anim;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import dev.bluesparksss.viavisuals.display.ProxyEntities;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

abstract class AnimViewBase implements AnimLook {

  private static final Map<String, ItemStack> ITEMS = new ConcurrentHashMap<>();

  protected record Limb(int id, int src, AnimCatalog.Bone bone, int rgb) {
  }

  protected final Limb[] limbs;
  protected final Map<String, Integer> partColors = new LinkedHashMap<>();
  protected boolean hurt;

  protected AnimViewBase(List<AnimCatalog.Bone> bones, Map<String, PartLimbs> parts) {
    List<Limb> all = new ArrayList<>();
    for (int i = 0; i < bones.size(); i++) {
      all.add(new Limb(ProxyEntities.nextId(), i, bones.get(i), -1));
    }
    for (Map.Entry<String, PartLimbs> part : parts.entrySet()) {
      partColors.put(part.getKey(), part.getValue().rgb());
      for (AnimCatalog.Bone bone : part.getValue().bones()) {
        int src = -1;
        for (int i = 0; i < bones.size(); i++) {
          if (bones.get(i).path().equals(bone.path())) {
            src = i;
            break;
          }
        }
        if (src >= 0) {
          all.add(new Limb(ProxyEntities.nextId(), src, bone, part.getValue().rgb()));
        }
      }
    }
    this.limbs = all.toArray(new Limb[0]);
  }

  @Override
  public Map<String, Integer> partColors() {
    return partColors;
  }

  protected static ItemStack item(String proxy, int cmd, int rgb) {
    return ITEMS.computeIfAbsent(proxy + ":" + cmd + ":" + rgb, key -> {
      org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(Material.valueOf(proxy));
      ItemMeta meta = stack.getItemMeta();
      meta.setCustomModelData(cmd);
      if (rgb >= 0 && meta instanceof LeatherArmorMeta leather) {
        leather.setColor(Color.fromRGB(rgb & 0xFFFFFF));
      }
      stack.setItemMeta(meta);
      return SpigotConversionUtil.fromBukkitItemStack(stack);
    });
  }

  protected static ItemStack limbItem(Limb limb, boolean hurt) {
    AnimCatalog.Bone bone = limb.bone();
    return hurt ? item(bone.hurtProxy(), bone.hurtCmd(), limb.rgb()) : item(bone.proxy(), bone.cmd(), limb.rgb());
  }
}
