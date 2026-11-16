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

import de.jpx3.intave.adapter.MinecraftVersion;
import de.jpx3.intave.test.IntegrationTests;
import de.jpx3.intave.test.Severity;
import de.jpx3.intave.test.Test;
import de.jpx3.intave.user.User;
import de.jpx3.intave.user.UserFactory;
import de.jpx3.intave.user.meta.ProtocolMetadata;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Checks {@link NativeUseEffects} against the running server's own item components, which unit
 * tests cannot reach.
 */
public final class ItemUseEffectsTests extends IntegrationTests {
  private static final MinecraftVersion USE_EFFECTS_VERSION = new MinecraftVersion("1.21.11");

  public ItemUseEffectsTests() {
    super("UE");
  }

  @Test(testCode = "NATIVE", severity = Severity.WARNING)
  public void readsEffectiveUseEffects() throws ReflectiveOperationException {
    if (USE_EFFECTS_VERSION.below()) {
      return;
    }
    // A default spear's use effects only exist as its item type's default component.
    ItemProperties.ItemUseProfile spear = check(new ItemStack(Material.valueOf("WOODEN_SPEAR")));
    assertTrue(spear.canSprint());
    assertEquals(1.0F, spear.speedMultiplier());
    assertFalse(spear.restrictsMovement());

    // Items without the component behave like vanilla's default: no sprinting, 20% speed.
    assertEquals(ItemProperties.DEFAULT_USE_PROFILE, check(new ItemStack(Material.SHIELD)));

    // A stack's own changes override and remove the item type's default.
    ItemProperties.ItemUseProfile slowed = check(itemFromString(
      "minecraft:wooden_spear[minecraft:use_effects={can_sprint:false,speed_multiplier:0.5}]"
    ));
    assertFalse(slowed.canSprint());
    assertEquals(0.5F, slowed.speedMultiplier());
    assertEquals(
      ItemProperties.DEFAULT_USE_PROFILE,
      check(itemFromString("minecraft:wooden_spear[!minecraft:use_effects]"))
    );
  }

  @Test(testCode = "PROFILE", severity = Severity.WARNING)
  public void spearUseDoesNotRestrictMovement() {
    if (USE_EFFECTS_VERSION.below()) {
      return;
    }
    User user = UserFactory.createFallback();
    user.meta().protocol().setProtocolVersion(ProtocolMetadata.VER_1_21_11);
    ItemProperties.ItemUseProfile profile = ItemProperties.itemUseProfile(
      user, new ItemStack(Material.valueOf("WOODEN_SPEAR"))
    );
    assertNotNull(profile);
    assertFalse(profile.restrictsMovement());
  }

  private ItemProperties.ItemUseProfile check(ItemStack itemStack) {
    ItemProperties.ItemUseProfile profile = NativeUseEffects.read(itemStack);
    // null means native reading is unavailable on this server.
    assertNotNull(profile);
    return profile;
  }

  private static ItemStack itemFromString(String input) throws ReflectiveOperationException {
    // ItemFactory#createItemStack(String) is newer than the compile API.
    Method createItemStack = ItemFactory.class.getMethod("createItemStack", String.class);
    return (ItemStack) createItemStack.invoke(Bukkit.getItemFactory(), input);
  }
}
