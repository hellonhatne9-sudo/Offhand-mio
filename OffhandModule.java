package me.mioclient.module.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import me.mioclient.Hub;
import me.mioclient.internal.RegistrySetting;
import me.mioclient.internal.Timer;
import me.mioclient.internal.AbstractTotemSwap;
import me.mioclient.internal.PacketSender;
import me.mioclient.module.Category;
import me.mioclient.module.Module;
import me.mioclient.module.player.NoInteractModule;
import me.mioclient.setting.Setting;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.SwordItem;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.HoverEvent.Action;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import me.mioclient.nick.Settings;
import me.mioclient.module.client.AntiCheatModule;
import me.mioclient.event.client.Subscribe;
import me.mioclient.event.interaction.InteractBlockEvent;
import me.mioclient.event.network.PacketReceiveEvent;
import me.mioclient.event.render.FrameRenderEvent;
import me.mioclient.predicate.combat.OffhandGappleBindPredicate;
import me.mioclient.predicate.combat.OffhandGappleBind2Predicate;
import me.mioclient.predicate.combat.OffhandGappleBind3Predicate;
import me.mioclient.predicate.combat.OffhandGappleItemGappleBindPredicate;
import me.mioclient.enum_.combat.TotemType;
import me.mioclient.enum_.misc.Priority;
import me.mioclient.enum_.shared.SettingMode;
import me.mioclient.internal.util.BlockUtil;
import me.mioclient.internal.util.ChatUtil;
import me.mioclient.internal.util.EntityUtil;
import me.mioclient.internal.util.InventoryUtil;
import me.mioclient.internal.util.KeybindUtil;

public class OffhandModule extends Module {
   public static final AutoCrystalModule autoCrystalModule = Hub.moduleAccessor.getModule(AutoCrystalModule.class);
   public static final OffhandModule offhandModule = Hub.moduleAccessor.getModule(OffhandModule.class);
   public static final NoInteractModule noInteractModule = Hub.moduleAccessor.getModule(NoInteractModule.class);
   public static AntiCheatModule antiCheatModule = Hub.moduleAccessor.getModule(AntiCheatModule.class);
   public Setting<TotemType> item;
   public Setting<Integer> delay;
   public Setting<Float> health;
   public Setting<Boolean> lethal;
   public Setting<Boolean> swap117;
   public Setting<Boolean> containers;
   public Setting<Boolean> gappleBind;
   public Setting<Float> safe;
   public Setting<Boolean> pickaxe;
   public Setting<Boolean> crystals;
   public Setting<Boolean> totems;
   public Setting<Boolean> crapple;
   public Setting<Set<Item>> custom;
   public final Timer swapTimer;
   public final Timer timer;
   public final Timer timer2;
   public final Timer popTimer;
   public final List<Long> swapTimestamps;
   public int field_1980;
   public boolean stoppedSprinting;
   public boolean field_1982;
   public boolean field_478;
   public final AbstractTotemSwap abstractTotemSwap;
   public static boolean swapped;

   public OffhandModule() {
      super("Offhand", "Manages your offhand automatically.", Category.COMBAT, "autototem");
      Settings.initialize(this);
      // Setting-visibility predicates (restored from loader SettingPredicates metadata)
      this.safe.method_5(new OffhandGappleBindPredicate(this));
      this.pickaxe.method_5(new OffhandGappleBind2Predicate(this));
      this.crystals.method_5(new OffhandGappleBind3Predicate(this));
      this.crapple.method_5(new OffhandGappleItemGappleBindPredicate(this));
      this.swapTimer = new Timer();
      this.timer = new Timer();
      this.timer2 = new Timer();
      this.popTimer = new Timer();
      this.swapTimestamps = new ArrayList<>();
      this.field_1980 = -1;
      this.abstractTotemSwap = new AbstractTotemSwap() {
         @Override
         public boolean method_206() {
            return mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)
               ? false
               : OffhandModule.this.lethal.getValue() || OffhandModule.this.health.method_105();
         }
      };
      this.health.method_2("Adaptive", SettingMode.MIN);
      this.lethal.method_5(var1 -> !this.health.method_105());
      this.gappleBind.method_31("GappleBind");
      this.safe.method_31("SafeHealth");
      this.method_7(this.custom);
   }

   @Override
   public String getInfo() {
      return this.item.getValue().toString();
   }

   @Override
   public void onToggle() {
      this.field_1980 = -1;
   }

   @Subscribe(
      getPriority = 100
   )
   public void onFrameRender(FrameRenderEvent var1) {
      this.field_1982 = var1.getFlag();
      this.method_633();
   }

   public void method_633() {
      if (!this.method_535()) {
         if (mc.player.isAlive()) {
            this.field_478 = false;
         }

         swapped = false;
         this.abstractTotemSwap.method_142();
         if (!this.isForceSuicide()) {
            if (this.method_569() && this.swapTimer.method_9((long)this.delay.getValue().intValue())) {
               this.swapTimestamps.removeIf(var0 -> var0 == null || System.currentTimeMillis() > var0 + 1000L);
               byte var1 = 4;
               if (this.swapTimestamps.size() > var1) {
                  this.timer.reset();
               }

               this.method_300();
            }
         }
      }
   }

   @Subscribe
   public void onPacketReceive(PacketReceiveEvent var1) {
      if (var1.getPacket() instanceof EntityStatusS2CPacket var2
         && !this.isForceSuicide()
         && this.method_569()
         && var2.getEntity(mc.world) == mc.player
         && var2.getStatus() == 35) {
         if (!mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) && mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            mc.player.getOffHandStack().decrement(1);
         }

         this.popTimer.reset();
         this.swapTimestamps.clear();
         this.method_300();
      }

      if (var1.getPacket() instanceof EntitySpawnS2CPacket var4 && this.abstractTotemSwap.method_206() && var4.getEntityType() == EntityType.END_CRYSTAL) {
         Vec3d var6 = new Vec3d(var4.getX(), var4.getY(), var4.getZ());
         if (this.abstractTotemSwap.method_29(var6)) {
            this.timer2.reset();
         }
      }

      if (var1.getPacket() instanceof DeathMessageS2CPacket) {
         if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return;
         }

         this.warnPossibleDeath();
      }
   }

   @Subscribe
   public void method_2(InteractBlockEvent var1) {
      if (var1.method_12() == Hand.MAIN_HAND && this.method_2((TotemType)null) != null && !antiCheatModule.is2b2t()) {
         mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
         var1.method_463();
      }
   }

   public TotemType getTotemType() {
      if (autoCrystalModule.isToggled() && autoCrystalModule.forceSuicide.getValue() && this.item.getValue() != TotemType.TOTEM) {
         return TotemType.CRYSTAL;
      } else {
         boolean var1 = this.crapple.getValue();
         TotemType var2 = TotemType.TOTEM;
         if (this.abstractTotemSwap.check25()) {
            return TotemType.TOTEM;
         } else {
            float var3 = EntityUtil.getValue27();
            if (var3 > this.health.getValue()
               && mc.player.fallDistance < 16F
               && InventoryUtil.method_29((Predicate<ItemStack>)(var2x -> var2x.isOf(this.item.getValue().method_98(var1)))) > 0) {
               var2 = this.item.getValue();
            }

            return this.method_2(var2);
         }
      }
   }

   public boolean method_14(Item var1) {
      if (var1 instanceof SwordItem || var1 instanceof AxeItem) {
         return true;
      } else if (var1 instanceof PickaxeItem && this.pickaxe.getValue()) {
         return true;
      } else {
         return var1 == Items.TOTEM_OF_UNDYING && this.totems.getValue() ? true : var1 == Items.END_CRYSTAL && this.crystals.getValue();
      }
   }

   public TotemType method_2(TotemType var1) {
      if (var1 != null && var1.method_98(this.crapple.getValue()) instanceof ShieldItem) {
         return var1;
      } else if (BlockUtil.method_2(mc.crosshairTarget) && !mc.player.isSneaking() && !noInteractModule.isToggled()) {
         return var1;
      } else {
         float var2 = EntityUtil.getValue27();
         Item var3 = mc.player.getMainHandStack().getItem();
         boolean var4 = this.safe.getValue() < var2;
         if (var3 == Items.TOTEM_OF_UNDYING) {
            var4 = true;
         }

         return this.gappleBind.getValue()
               && mc.options.useKey.isPressed()
               && var4
               && this.method_14(var3)
               && mc.currentScreen == null
               && this.item.getValue() != TotemType.GAPPLE
            ? TotemType.GAPPLE
            : var1;
      }
   }

   public void startSwap() {
      this.swapTimestamps.add(System.currentTimeMillis());
      Hub.inventoryPacketManager.method_154(true);
      if (Hub.latencyHandler.getSprinting()) {
         PacketSender.method_2(mc.player, Mode.STOP_SPRINTING, 0);
         this.stoppedSprinting = true;
      }
   }

   public void endSwap() {
      Hub.inventoryPacketManager.method_154(false);
      if (!(mc.currentScreen instanceof HandledScreen)) {
         Hub.inventoryPacketManager.close();
      }

      if (this.stoppedSprinting) {
         PacketSender.method_2(mc.player, Mode.START_SPRINTING, 0);
         this.stoppedSprinting = false;
      }
   }

   public void method_300() {
      TotemType var1 = this.getTotemType();
      int var2 = var1.method_186(this.crapple.getValue());
      if (this.field_1980 != -1
         && (!(mc.currentScreen instanceof HandledScreen) || mc.currentScreen instanceof InventoryScreen)
         && mc.player
            .getInventory()
            .getStack(this.field_1980 >= 36 ? this.field_1980 - 36 : this.field_1980)
            .isOf(var1.method_98(this.crapple.getValue()))
         && var2 != -1) {
         var2 = this.field_1980;
      }

      if (var2 != -1) {
         if (this.containers.getValue()
            || !(mc.currentScreen instanceof ShulkerBoxScreen) && !(mc.currentScreen instanceof GenericContainerScreen)) {
            if (var2 < mc.player.currentScreenHandler.slots.size()) {
               ItemStack var3 = mc.player.currentScreenHandler.getCursorStack();
               ItemStack var4 = mc.player.currentScreenHandler.getSlot(var2).getStack();
               if (!var4.contains(DataComponentTypes.FOOD) || this.field_1982) {
                  this.startSwap();
                  int var5 = mc.player.currentScreenHandler.syncId;
                  if (!var3.isEmpty() && !InventoryUtil.isInventoryFull()) {
                     mc.interactionManager.clickSlot(var5, InventoryUtil.method_9(Items.AIR), 0, SlotActionType.PICKUP, mc.player);
                  }

                  if (this.swap117.getValue() && mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                     this.field_1980 = var2;
                     mc.interactionManager.clickSlot(var5, var2, 40, SlotActionType.SWAP, mc.player);
                     this.swapTimer.reset();
                  } else {
                     this.method_78(var2);
                     this.field_1980 = var2;
                  }

                  swapped = true;
                  this.endSwap();
                  if (KeybindUtil.method_2(mc.options.useKey) && var4.contains(DataComponentTypes.FOOD)) {
                     mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
                  }
               }
            }
         }
      }
   }

   public void method_78(int var1) {
      if (this.swapTimer.method_9((long)this.delay.getValue().intValue())) {
         if (mc.player.currentScreenHandler.slots.size() > 45) {
            InventoryUtil.method_5(var1, 45);
            this.swapTimer.reset();
         }
      }
   }

   public void method_7(Setting<Set<Item>> var1) {
      var1.method_9(() -> {
         RegistrySetting var1x = (RegistrySetting)var1;
         if (var1x.method_98() != null) {
            ((Set)var1.getValue()).clear();
            ((Set)var1.getValue()).add((Item)var1x.method_98());
         }
      });
   }

   public void warnPossibleDeath() {
      long var1 = this.popTimer.getElapsed();
      int var3 = Hub.latencyHandler.method_983();
      if (!this.field_478) {
         MutableText var4 = Text.literal("Since last pop: %dms. Latency: %dms.".formatted(var1, var3))
            .append(
               Text.literal(" Possible death reasons: Damage override/Lag spike.*")
                  .styled(var0 -> var0.withHoverEvent(new HoverEvent(Action.SHOW_TEXT, Text.literal("More info in the game log."))))
            );
         if ((long)var3 > var1) {
            ChatUtil.method_2(var4, ChatUtil.method_38(Math.abs(this.getName().hashCode()) * -1 - 1), Priority.HIGH);
            System.out
               .println(
                  "*Death was most likely caused by you taking damage before the server receives the update slot packet, making you die holding a totem client-side."
               );
            this.field_478 = true;
         }
      }
   }

   public boolean isForceSuicide() {
      return autoCrystalModule.isToggled() && autoCrystalModule.forceSuicide.getValue() && this.item.getValue() == TotemType.TOTEM;
   }

   public boolean method_569() {
      return this.swap117.getValue()
         ? true
         : !(mc.currentScreen instanceof CreativeInventoryScreen)
            && mc.player.playerScreenHandler == mc.player.currentScreenHandler
            && !(mc.currentScreen instanceof GenericContainerScreen);
   }

   public static boolean method_639() {
      return swapped && offhandModule.isToggled();
   }
}
