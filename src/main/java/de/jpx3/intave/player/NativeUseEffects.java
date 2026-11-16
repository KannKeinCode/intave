/*
 * Copyright 2026 Intave
 *
 * This software is licensed under the PolyForm Perimeter License 1.0.0.
 * You may use this software for any purpose, except for providing to
 * others any product that competes with the software.
 *
 * A copy of the license is available at:
 *   https://polyformproject.org/licenses/perimeter/1.0.0/
 */

package de.jpx3.intave.player;

import com.comphenix.protocol.utility.MinecraftReflection;
import de.jpx3.intave.IntaveLogger;
import de.jpx3.intave.adapter.MinecraftVersion;
import de.jpx3.intave.annotate.Nullable;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads an item's effective {@code minecraft:use_effects} component from the native item stack:
 * the item type's default merged with the stack's own changes.
 * <p>
 * Bukkit's {@code ItemMeta} only exposes a stack's own changes, so an unmodified spear looks as if
 * it had no use effects, which would mean "no sprinting, 20% speed". Its vanilla default allows
 * sprinting at full speed.
 * <p>
 * Native members are resolved by their structure, not their names, so the same code works on
 * Mojang-mapped (Paper) and obfuscated (Spigot) servers:
 * <ul>
 *   <li>the component type is the {@code DataComponents} constant typed
 *   {@code DataComponentType<UseEffects>};</li>
 *   <li>the getter is the component getter interface's only method taking a component type and
 *   returning its value, which excludes {@code ItemStack#remove} with the same signature;</li>
 *   <li>the sprint and speed values are identified by calibrating against vanilla's own
 *   {@code UseEffects.DEFAULT}, which must read as the fallback profile.</li>
 * </ul>
 * If any of this does not hold, native reading stays disabled and callers fall back.
 */
final class NativeUseEffects {
  private static final MinecraftVersion USE_EFFECTS_VERSION = new MinecraftVersion("1.21.11");

  private NativeUseEffects() {
  }

  /**
   * @return the item's effective use effects, {@link ItemProperties#DEFAULT_USE_PROFILE} if it has
   * none, or {@code null} if native reading is unavailable or failed
   */
  @Nullable
  static ItemProperties.ItemUseProfile read(ItemStack itemStack) {
    Access access = State.access();
    if (access == null) {
      return null;
    }
    try {
      Object nativeItem = MinecraftReflection.getMinecraftItemStack(itemStack);
      Object useEffects = access.get.invoke(nativeItem, access.componentType);
      if (useEffects == null) {
        return ItemProperties.DEFAULT_USE_PROFILE;
      }
      return ItemProperties.validatedUseProfile(
        (Boolean) access.canSprint.invoke(useEffects),
        ((Number) access.speedMultiplier.invoke(useEffects)).floatValue()
      );
    } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
      State.warnOnce("Unable to read item use effects, falling back to item metadata: " + exception);
      return null;
    }
  }

  private static final class State {
    private static volatile boolean resolved;
    private static volatile Access access;
    private static boolean warned;

    @Nullable
    static Access access() {
      if (!resolved) {
        synchronized (State.class) {
          if (!resolved) {
            access = resolve();
            resolved = true;
          }
        }
      }
      return access;
    }

    @Nullable
    private static Access resolve() {
      if (USE_EFFECTS_VERSION.below()) {
        return null;
      }
      try {
        return Access.resolve();
      } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
        warnOnce("Unable to resolve item use effects, falling back to item metadata: " + exception);
        return null;
      }
    }

    static synchronized void warnOnce(String message) {
      if (warned) {
        return;
      }
      warned = true;
      IntaveLogger logger = IntaveLogger.logger();
      if (logger != null) {
        logger.warn(message);
      }
    }
  }

  private static final class Access {
    private final Object componentType;
    private final Method get;
    private final Method canSprint;
    private final Method speedMultiplier;

    private Access(Object componentType, Method get, Method canSprint, Method speedMultiplier) {
      this.componentType = componentType;
      this.get = get;
      this.canSprint = canSprint;
      this.speedMultiplier = speedMultiplier;
    }

    static Access resolve() throws ReflectiveOperationException {
      // An item without use effects behaves like vanilla's default use effects.
      ItemProperties.ItemUseProfile vanillaDefault = ItemProperties.DEFAULT_USE_PROFILE;
      Class<?> itemStack = MinecraftReflection.getItemStackClass();
      ClassLoader loader = itemStack.getClassLoader();
      Class<?> componentTypeClass = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
      Class<?> components = Class.forName("net.minecraft.core.component.DataComponents", true, loader);
      Class<?> useEffects = Class.forName("net.minecraft.world.item.component.UseEffects", true, loader);

      Object componentType = only(staticFieldsTyped(components, componentTypeClass, useEffects), "use effects component type")
        .get(null);
      Method get = only(componentGetters(itemStack, componentTypeClass), "component getter");

      Object vanillaUseEffects = only(staticFieldsOfType(useEffects), "default use effects").get(null);
      Method canSprint = only(
        accessorsReturning(useEffects, boolean.class, vanillaUseEffects, vanillaDefault.canSprint()),
        "sprint accessor"
      );
      Method speedMultiplier = only(
        accessorsReturning(useEffects, float.class, vanillaUseEffects, vanillaDefault.speedMultiplier()),
        "speed accessor"
      );
      return new Access(componentType, get, canSprint, speedMultiplier);
    }

    /** Public static fields declared as {@code rawType<argument>}. */
    private static List<Field> staticFieldsTyped(Class<?> owner, Class<?> rawType, Class<?> argument) {
      List<Field> fields = new ArrayList<>();
      for (Field field : owner.getFields()) {
        Type generic = field.getGenericType();
        if (Modifier.isStatic(field.getModifiers()) && generic instanceof ParameterizedType) {
          ParameterizedType parameterized = (ParameterizedType) generic;
          Type[] arguments = parameterized.getActualTypeArguments();
          if (parameterized.getRawType() == rawType && arguments.length == 1 && arguments[0] == argument) {
            fields.add(field);
          }
        }
      }
      return fields;
    }

    private static List<Field> staticFieldsOfType(Class<?> type) {
      List<Field> fields = new ArrayList<>();
      for (Field field : type.getFields()) {
        if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
          fields.add(field);
        }
      }
      return fields;
    }

    /**
     * Methods of the item stack's interfaces that take a component type and return its value,
     * one per signature: an interface that overrides an inherited getter declares the same
     * virtual method again. Invoking it dispatches to the item stack's implementation.
     */
    private static List<Method> componentGetters(Class<?> itemStack, Class<?> componentType) {
      Set<Class<?>> interfaces = new LinkedHashSet<>();
      collectInterfaces(itemStack, interfaces);
      Map<String, Method> getters = new LinkedHashMap<>();
      for (Class<?> type : interfaces) {
        for (Method method : type.getDeclaredMethods()) {
          Class<?>[] parameters = method.getParameterTypes();
          if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
            && parameters.length == 1 && parameters[0] == componentType
            && method.getReturnType() == Object.class) {
            getters.putIfAbsent(method.getName(), method);
          }
        }
      }
      return new ArrayList<>(getters.values());
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> interfaces) {
      for (Class<?> current = type; current != null; current = current.getSuperclass()) {
        for (Class<?> implemented : current.getInterfaces()) {
          if (interfaces.add(implemented)) {
            collectInterfaces(implemented, interfaces);
          }
        }
      }
    }

    /** Parameterless accessors of the given type whose value on the vanilla default matches. */
    private static List<Method> accessorsReturning(
      Class<?> type, Class<?> returnType, Object vanillaInstance, Object vanillaValue
    ) throws ReflectiveOperationException {
      List<Method> accessors = new ArrayList<>();
      for (Method method : type.getDeclaredMethods()) {
        if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
          && method.getParameterCount() == 0 && method.getReturnType() == returnType
          && vanillaValue.equals(method.invoke(vanillaInstance))) {
          accessors.add(method);
        }
      }
      return accessors;
    }

    private static <T> T only(List<T> candidates, String description) throws NoSuchFieldException {
      if (candidates.size() != 1) {
        throw new NoSuchFieldException("Expected exactly one " + description + ", found " + candidates.size());
      }
      return candidates.get(0);
    }
  }
}
