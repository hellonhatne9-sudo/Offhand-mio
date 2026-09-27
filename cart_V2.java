package vcore.features.modules.combat;

import java.awt.Color;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult.Type;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.RaycastContext.FluidHandling;
import net.minecraft.world.RaycastContext.ShapeType;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import vcore.core.Managers;
import vcore.core.manager.client.AsyncManager;
import vcore.core.manager.client.ModuleManager;
import vcore.events.impl.EventFixVelocity;
import vcore.events.impl.EventKeyboardInput;
import vcore.events.impl.EventSync;
import vcore.events.impl.PacketEvent;
import vcore.events.impl.TotemPopEvent;
import vcore.features.modules.Module;
import vcore.gui.notification.Notification;
import vcore.injection.accesors.IInteractionManager;
import vcore.setting.Setting;
import vcore.setting.impl.Bind;
import vcore.setting.impl.SettingGroup;
import vcore.utility.Timer;
import vcore.utility.player.InteractionUtility;
import vcore.utility.player.InventoryUtility;
import vcore.utility.player.PlayerUtility;
import vcore.utility.player.SearchInvResult;
import vcore.utility.render.Render2DEngine;
import vcore.utility.render.Render3DEngine;

public class AutoCart extends Module {
   private static final Color CROSSBOW_DEBUG_FILL = new Color(255, 0, 0, 100);
   private static final int HOTBAR_SLOT_SIZE = 20;
   private static final int HOTBAR_Y_OFFSET = 22;
   private static final int HOTBAR_LEFT_OFFSET = 91;
   private static final int TICK_LENGTH_MS = 50;
   private static final long CART_AURA_TARGET_MEMORY_MS = 1000L;
   private final Setting<AutoCart.Mode> mode = new Setting<>("Mode", AutoCart.Mode.Bow);
   private final Setting<Float> maxDistance = new Setting<>("Max Distance", 4.5F, 2.0F, 6.0F, v -> this.mode.is(AutoCart.Mode.Bow));
   private final Setting<Integer> startDelay = new Setting<>("Start Delay", 25, 0, 60, v -> this.mode.is(AutoCart.Mode.Bow));
   private final Setting<Bind> activeBind = new Setting<>("Active Bind", new Bind(-1, false, false), v -> this.mode.is(AutoCart.Mode.CrossBow));
   private final Setting<Integer> delay = new Setting<>("Delay", 25, 0, 100, v -> this.mode.is(AutoCart.Mode.Bow) || this.mode.is(AutoCart.Mode.CrossBow));
   private final Setting<Integer> cartAuraDelay = new Setting<>("Cart Aura Delay", 0, 0, 20, v -> this.isCartAuraEnabled());
   private final Setting<Integer> refillSlot = new Setting<>("Slot", 9, 1, 9, v -> this.isRefillSlotVisible());
   private final Setting<Boolean> swapBack = new Setting<>("Swap Back", true);
   private final Setting<Boolean> totemCheck = new Setting<>("Totem Check", true, v -> this.isCartAuraEnabled());
   private final Setting<Boolean> changeLook = new Setting<>("Change Look", false);
   private final Setting<Boolean> cartAura = new Setting<>("Cart Aura", false, v -> this.mode.is(AutoCart.Mode.CrossBow));
   private final Setting<Boolean> flintAndSteel = new Setting<>("Flint and Steel", false, v -> this.mode.is(AutoCart.Mode.CrossBow));
   private final Setting<Boolean> notify = new Setting<>("Notify", true, v -> this.mode.is(AutoCart.Mode.CrossBow));
   private final Setting<AutoCart.ReFillMode> reFill = new Setting<>("ReFill", AutoCart.ReFillMode.None);
   private final Setting<SettingGroup> cartAuraTargets = new Setting<>(
      "Target", new SettingGroup(false, 0), v -> this.mode.is(AutoCart.Mode.CrossBow) && this.cartAura.getValue()
   );
   private final Setting<Boolean> cartAuraTarget = new Setting<>("Aura Target", true, v -> this.mode.is(AutoCart.Mode.CrossBow) && this.cartAura.getValue())
      .addToGroup(this.cartAuraTargets);
   private final Setting<Boolean> cartOtherPlayer = new Setting<>("Other Player", false, v -> this.mode.is(AutoCart.Mode.CrossBow) && this.cartAura.getValue())
      .addToGroup(this.cartAuraTargets);
   private volatile float[] silentRotation = null;
   private volatile boolean rotating = false;
   private boolean crossBowPressed = false;
   private volatile boolean cartAuraExecuting = false;
   private UUID lastAuraTargetUuid = null;
   private int lastAuraTargetId = -1;
   private long lastAuraTargetAt = -1L;
   private final List<AutoCart.PendingShot> pendingShots = new CopyOnWriteArrayList<>();
   private long lastAutoShotTime = 0L;
   private final Timer refillTimer = new Timer();

   public AutoCart() {
      super("AutoCart", "Automates TNT minecart combat setups.", Module.Category.COMBAT);
   }

   private boolean isCartAuraEnabled() {
      return this.mode.is(AutoCart.Mode.CrossBow) && this.cartAura != null && this.cartAura.getValue();
   }

   private boolean isRefillSlotVisible() {
      return this.reFill != null && this.reFill.getValue() != AutoCart.ReFillMode.None;
   }

   private void runOnClientThreadAndWait(Runnable action) {
      if (mc.isOnThread()) {
         action.run();
      } else {
         CountDownLatch latch = new CountDownLatch(1);
         AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
         mc.execute(() -> {
            try {
               action.run();
            } catch (RuntimeException error) {
               errorRef.set(error);
            } finally {
               latch.countDown();
            }
         });

         try {
            latch.await();
         } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
         }

         RuntimeException error = errorRef.get();
         if (error != null) {
            throw error;
         }
      }
   }

   private <T> T callOnClientThreadAndWait(Supplier<T> supplier) {
      AtomicReference<T> resultRef = new AtomicReference<>();
      this.runOnClientThreadAndWait(() -> resultRef.set(supplier.get()));
      return resultRef.get();
   }

   @Override
   public void onEnable() {
      this.resetState();
   }

   @Override
   public void onDisable() {
      this.resetState();
   }

   private void resetState() {
      this.silentRotation = null;
      this.rotating = false;
      this.crossBowPressed = false;
      if (this.cartAuraExecuting) {
         ModuleManager.aura.externalPause = false;
      }

      this.cartAuraExecuting = false;
      this.lastAuraTargetUuid = null;
      this.lastAuraTargetId = -1;
      this.lastAuraTargetAt = -1L;
      this.pendingShots.clear();
      this.clearRefillState();
   }

   @EventHandler
   public void onSync(EventSync e) {
      if (!fullNullCheck()) {
         if (this.rotating && this.silentRotation != null) {
            mc.player.setYaw(this.silentRotation[0]);
            mc.player.setPitch(this.silentRotation[1]);
         }
      }
   }

   @EventHandler(priority = -200)
   public void onPlayerMove(EventFixVelocity event) {
      float[] rotation = this.getAutoCartMoveFixRotation();
      if (rotation != null) {
         event.setVelocity(this.fixMovement(rotation[0], event.getMovementInput(), event.getSpeed()));
      }
   }

   @EventHandler(priority = -200)
   public void onKeyboardInput(EventKeyboardInput event) {
      float[] rotation = this.getAutoCartMoveFixRotation();
      if (rotation != null) {
         float moveForward = mc.player.input.movementForward;
         float moveSideways = mc.player.input.movementSideways;
         float delta = (mc.player.getYaw() - rotation[0]) * (float) (Math.PI / 180.0);
         float cos = MathHelper.cos(delta);
         float sin = MathHelper.sin(delta);
         mc.player.input.movementSideways = Math.round(moveSideways * cos - moveForward * sin);
         mc.player.input.movementForward = Math.round(moveForward * cos + moveSideways * sin);
      }
   }

   @EventHandler
   public void onPacketSendPost(PacketEvent.@NotNull SendPost event) {
      if (!fullNullCheck()) {
         if (this.mode.is(AutoCart.Mode.Bow)) {
            if (event.getPacket() instanceof PlayerActionC2SPacket action
               && action.getAction() == Action.RELEASE_USE_ITEM
               && mc.player.getMainHandStack().getItem() == Items.BOW) {
               this.executeBowMode();
            }
         } else if (this.mode.is(AutoCart.Mode.CrossBow) && this.notify.getValue()) {
            if (event.getPacket() instanceof PlayerInteractItemC2SPacket packet && mc.player != null) {
               ItemStack stack = mc.player.getStackInHand(packet.getHand());
               if (this.isCrossbowCharged(stack)) {
                  this.checkManualCrossbowShot();
               }
            }
         }
      }
   }

   @EventHandler(priority = 200)
   public void onPacketReceive(PacketEvent.@NotNull Receive event) {
      if (!fullNullCheck() && this.mode.is(AutoCart.Mode.CrossBow)) {
         if (this.cartAura.getValue()
            && event.getPacket() instanceof EntityStatusS2CPacket packet
            && packet.getStatus() == 35
            && packet.getEntity(mc.world) instanceof PlayerEntity player) {
            this.handleCartAuraPop(player);
         }
         if (this.notify.getValue()) {
            if (event.getPacket() instanceof ExplosionS2CPacket explosion) {
               this.handleExplosion(explosion);
            } else if (event.getPacket() instanceof EntityTrackerUpdateS2CPacket tracker) {
               for (AutoCart.PendingShot shot : this.pendingShots) {
                  if (!shot.finished && shot.cartEntityId != -1 && tracker.id() == shot.cartEntityId) {
                     if (!shot.passesThroughFire) {
                        shot.coldHitDetected = true;
                     }
                  }
               }
            }
         }
      }
   }

   @Override
   public void onUpdate() {
      if (!fullNullCheck()) {
         this.handleRefill();
         if (this.mode.is(AutoCart.Mode.CrossBow)) {
            this.updateCartAuraTargetMemory();
            if (this.notify.getValue()) {
               this.checkPendingShots();
            }
            boolean pressed = this.isKeyPressed(this.activeBind);
            if (pressed && !this.crossBowPressed) {
               this.crossBowPressed = true;
               this.executeCrossBowMode();
            }

            if (!pressed) {
               this.crossBowPressed = false;
            }
         }
      }
   }

   @Override
   public void onRender3D(MatrixStack stack) {
      if (!fullNullCheck()) {
         this.handleRefill();
      }
   }

   @Override
   public void onRender2D(DrawContext context) {
      if (!fullNullCheck() && this.mode.is(AutoCart.Mode.CrossBow) && !mc.options.hudHidden) {
         if (this.hasCrossbowDebugBaseRequirements()) {
            this.renderCrossbowDebug(context);
         }
      }
   }

   private void handleRefill() {
      if (this.reFill.is(AutoCart.ReFillMode.None) || mc.player == null) {
         return;
      }

      int targetHotbarSlot = this.refillSlot.getValue() - 1;
      if (targetHotbarSlot < 0 || targetHotbarSlot > 8) {
         return;
      }

      ItemStack currentStack = mc.player.getInventory().getStack(targetHotbarSlot);
      if (currentStack.isOf(Items.TNT_MINECART)) {
         return;
      }

      if (this.reFill.is(AutoCart.ReFillMode.Legit)) {
         if (mc.currentScreen instanceof HandledScreen<?> handledScreen) {
            ScreenHandler handler = handledScreen.getScreenHandler();
            if (handler == null || !handler.getCursorStack().isEmpty()) {
               return;
            }

            if (!this.refillTimer.passedMs(100L)) {
               return;
            }

            if (mc.player.isCreative() && mc.currentScreen instanceof CreativeInventoryScreen) {
               if (mc.interactionManager != null) {
                  mc.interactionManager.clickCreativeStack(new ItemStack(Items.TNT_MINECART), 36 + targetHotbarSlot);
                  mc.player.getInventory().setStack(targetHotbarSlot, new ItemStack(Items.TNT_MINECART));
                  this.refillTimer.reset();
               }
               return;
            }

            int cartSlotId = this.findCartSlotInHandler(handler, targetHotbarSlot);
            if (cartSlotId != -1 && mc.interactionManager != null) {
               mc.interactionManager.clickSlot(handler.syncId, cartSlotId, targetHotbarSlot, SlotActionType.SWAP, mc.player);
               this.refillTimer.reset();
            }
         }
      } else if (this.reFill.is(AutoCart.ReFillMode.Normal)) {
         if (mc.currentScreen == null) {
            if (this.refillTimer.passedMs(50L)) {
               int cartSlot = this.findCartSlotForSilentRefill(targetHotbarSlot);
               if (cartSlot != -1) {
                  clickSlot(cartSlot, targetHotbarSlot, SlotActionType.SWAP);
                  this.refillTimer.reset();
               }
            }
         } else if (mc.currentScreen instanceof HandledScreen<?> handledScreen) {
            ScreenHandler handler = handledScreen.getScreenHandler();
            if (handler != null && handler.getCursorStack().isEmpty() && this.refillTimer.passedMs(50L)) {
               int cartSlotId = this.findCartSlotInHandler(handler, targetHotbarSlot);
               if (cartSlotId != -1 && mc.interactionManager != null) {
                  mc.interactionManager.clickSlot(handler.syncId, cartSlotId, targetHotbarSlot, SlotActionType.SWAP, mc.player);
                  this.refillTimer.reset();
               }
            }
         }
      }
   }

   private int findCartSlotInHandler(ScreenHandler handler, int targetHotbarSlot) {
      if (handler == null || mc.player == null) {
         return -1;
      }

      for (Slot slot : handler.slots) {
         if (slot.inventory == mc.player.getInventory()
            && slot.getIndex() >= 9
            && slot.getIndex() < 36
            && slot.hasStack()
            && slot.getStack().isOf(Items.TNT_MINECART)) {
            return slot.id;
         }
      }

      for (Slot slot : handler.slots) {
         if (slot.inventory == mc.player.getInventory() && slot.getIndex() == targetHotbarSlot) {
            continue;
         }
         if (slot.hasStack() && slot.getStack().isOf(Items.TNT_MINECART)) {
            return slot.id;
         }
      }

      return -1;
   }

   private int findCartSlotForSilentRefill(int targetHotbarSlot) {
      if (mc.player == null) {
         return -1;
      }

      for (int i = 9; i < 36; i++) {
         if (mc.player.getInventory().getStack(i).isOf(Items.TNT_MINECART)) {
            return i;
         }
      }

      for (int i = 0; i < 9; i++) {
         if (i != targetHotbarSlot && mc.player.getInventory().getStack(i).isOf(Items.TNT_MINECART)) {
            return i + 36;
         }
      }

      return -1;
   }

   private void clearRefillState() {
      this.refillTimer.reset();
   }

   @EventHandler
   public void onTotemPop(@NotNull TotemPopEvent event) {
      this.handleCartAuraPop(event.getEntity());
   }

   private void handleCartAuraPop(PlayerEntity popTarget) {
      if (!fullNullCheck()) {
         if (this.mode.is(AutoCart.Mode.CrossBow) && this.cartAura.getValue() && !this.cartAuraExecuting) {
            if (popTarget != null && popTarget != mc.player) {
               this.updateCartAuraTargetMemory();
               boolean isAuraTarget = this.cartAuraTarget.getValue() && this.isRememberedAuraTarget(popTarget);
               boolean isOtherPlayer = false;
               if (this.cartOtherPlayer.getValue()) {
                  isOtherPlayer = !Managers.FRIEND.isFriend(popTarget) && mc.player.getPos().distanceTo(popTarget.getPos()) <= 6.0;
               }

               if (isAuraTarget || isOtherPlayer) {
                  mc.execute(() -> {
                     if (!fullNullCheck() && !this.cartAuraExecuting && !this.isDisabled()) {
                        if (this.mode.is(AutoCart.Mode.CrossBow) && this.cartAura.getValue()) {
                           if (!popTarget.isRemoved()) {
                              if (!this.totemCheck.getValue() || !this.isHoldingTotem(popTarget)) {
                                 if (this.hasBasicCartAuraResources()) {
                                    this.executeCartAura(popTarget);
                                 }
                              }
                           }
                        }
                     }
                  });
               }
            }
         }
      }
   }

   private void executeCartAura(PlayerEntity target) {
      this.cartAuraExecuting = true;
      int delayMs = this.delay.getValue();
      int startDelayMs = this.cartAuraDelay.getValue() * 50;
      Managers.ASYNC.run(() -> {
         try {
            if (startDelayMs > 0) {
               AsyncManager.sleep(startDelayMs);
            }

            if (fullNullCheck() || this.isDisabled() || !this.mode.is(AutoCart.Mode.CrossBow) || !this.cartAura.getValue()) {
               return;
            }

            int[] prevSlotRef = new int[]{-1};
            AutoCart.CartAuraPlan plan = this.callOnClientThreadAndWait(() -> {
               if (this.totemCheck.getValue() && this.isHoldingTotem(target)) {
                  return null;
               }

               AutoCart.CartAuraPlan createdPlan = this.createCartAuraPlan(target);
               if (mc.player != null) {
                  prevSlotRef[0] = mc.player.getInventory().selectedSlot;
               }

               return createdPlan;
            });
            if (plan != null) {
               BlockPos basePos = plan.basePos();
               BlockPos firePos = plan.firePos();
               Vec3d shootVec = plan.shootVec();
               int prevSlot = prevSlotRef[0];
               ModuleManager.aura.externalPause = true;
               Vec3d placeVec = new Vec3d(basePos.getX() + 0.5, basePos.up().getY(), basePos.getZ() + 0.5);
               this.runOnClientThreadAndWait(() -> this.applyRotation(InteractionUtility.calculateAngle(placeVec)));
               AsyncManager.sleep(delayMs);
               if (!plan.railExists()) {
                  this.runOnClientThreadAndWait(() -> {
                     if (!this.isRailBlock(mc.world.getBlockState(basePos.up()).getBlock())) {
                        this.selectHotbarLegit(plan.railResult().slot());
                        this.placeRailOn(basePos);
                     }
                  });
                  AsyncManager.sleep(delayMs);
               }

               if (!plan.cartExists()) {
                  this.runOnClientThreadAndWait(() -> {
                     this.selectHotbarLegit(plan.cartResult().slot());
                     this.placeMinecartOn(basePos);
                  });
                  AsyncManager.sleep(delayMs);
               }

               if (firePos != null && !plan.fireExists()) {
                  this.runOnClientThreadAndWait(() -> {
                     Vec3d fireVec = new Vec3d(firePos.getX() + 0.5, firePos.getY() + 1.0, firePos.getZ() + 0.5);
                     this.applyRotation(InteractionUtility.calculateAngle(fireVec));
                     this.selectHotbarLegit(plan.flintResult().slot());
                     BlockHitResult fireHit = new BlockHitResult(fireVec, Direction.UP, firePos, false);
                     this.interactWithBlock(fireHit);
                  });
                  AsyncManager.sleep(delayMs);
               }

               this.runOnClientThreadAndWait(() -> {
                  float[] shootAngle = InteractionUtility.calculateAngle(shootVec);
                  this.applyRotation(shootAngle);
                  this.selectHotbarLegit(plan.crossbowResult().slot());
                  this.interactWithItem();
                  mc.player.swingHand(Hand.MAIN_HAND);
                  if (this.notify.getValue()) {
                     this.registerPendingShot(basePos, shootVec);
                  }
               });
               AsyncManager.sleep(delayMs);
               this.runOnClientThreadAndWait(() -> {
                  if (this.swapBack.getValue()) {
                     this.selectHotbarLegit(prevSlot);
                  }

                  this.endRotation();
               });
               return;
            }
         } finally {
            ModuleManager.aura.externalPause = false;
            this.cartAuraExecuting = false;
         }
      });
   }

   private void updateCartAuraTargetMemory() {
      if (this.isCartAuraEnabled() && this.cartAuraTarget.getValue()) {
         if (Aura.target instanceof PlayerEntity player && player != mc.player && !player.isRemoved()) {
            this.lastAuraTargetUuid = player.getUuid();
            this.lastAuraTargetId = player.getId();
            this.lastAuraTargetAt = System.currentTimeMillis();
         }
      }
   }

   private boolean isRememberedAuraTarget(PlayerEntity player) {
      if (player == null) {
         return false;
      } else if (this.isSameAuraTarget(Aura.target, player)) {
         return true;
      } else if (ModuleManager.aura.isOn() && this.isSameAuraTarget(ModuleManager.aura.findTarget(), player)) {
         return true;
      } else {
         return this.lastAuraTargetUuid != null && System.currentTimeMillis() - this.lastAuraTargetAt <= 1000L
            ? player.getUuid().equals(this.lastAuraTargetUuid) || player.getId() == this.lastAuraTargetId
            : false;
      }
   }

   private boolean isSameAuraTarget(Entity auraTarget, PlayerEntity player) {
      return !(auraTarget instanceof PlayerEntity auraPlayer)
         ? false
         : auraPlayer == player || auraPlayer.getId() == player.getId() || auraPlayer.getUuid().equals(player.getUuid());
   }

   private void executeBowMode() {
      if (!fullNullCheck()) {
         SearchInvResult bowResult = InventoryUtility.findItemInHotBar(Items.BOW);
         SearchInvResult cartResult = InventoryUtility.findItemInHotBar(Items.TNT_MINECART);
         if (bowResult.found() && cartResult.found()) {
            BlockPos targetPos = this.calcBowTrajectory(mc.player.getYaw());
            if (targetPos != null) {
               BlockPos basePos = this.getCartBasePos(targetPos);
               boolean railExists = this.isRailBlock(mc.world.getBlockState(basePos.up()).getBlock());
               if (railExists || this.findRailInHotBar().found()) {
                  float distSq = PlayerUtility.squaredDistanceFromEyes(basePos.up().toCenterPos());
                  float maxDistSq = this.maxDistance.getValue() * this.maxDistance.getValue();
                  float safeDistSq = 4.0F;
                  if (!(distSq > maxDistSq) && !(distSq < safeDistSq)) {
                     Managers.ASYNC.run(() -> this.executeBowPlacement(targetPos), this.startDelay.getValue().intValue());
                  }
               }
            }
         }
      }
   }

   private void executeBowPlacement(@NotNull BlockPos targetPos) {
      if (!fullNullCheck() && this.mode.is(AutoCart.Mode.Bow)) {
         BlockPos basePos = this.getCartBasePos(targetPos);
         boolean railExists = this.isRailBlock(mc.world.getBlockState(basePos.up()).getBlock());
         SearchInvResult railResult = this.findRailInHotBar();
         SearchInvResult cartResult = InventoryUtility.findItemInHotBar(Items.TNT_MINECART);
         if (cartResult.found()) {
            if (railExists || railResult.found()) {
               int prevSlot = mc.player.getInventory().selectedSlot;
               int delayMs = this.delay.getValue();
               Vec3d placeVec = new Vec3d(basePos.getX() + 0.5, basePos.up().getY(), basePos.getZ() + 0.5);
               mc.executeSync(() -> this.applyRotation(InteractionUtility.calculateAngle(placeVec)));
               AsyncManager.sleep(delayMs);
               if (!railExists) {
                  mc.executeSync(() -> {
                     if (!this.isRailBlock(mc.world.getBlockState(basePos.up()).getBlock())) {
                        this.selectHotbarLegit(railResult.slot());
                        this.placeRailOn(basePos);
                     }
                  });
                  AsyncManager.sleep(delayMs);
               }

               mc.executeSync(() -> {
                  this.selectHotbarLegit(cartResult.slot());
                  this.placeMinecartOn(basePos);
               });
               AsyncManager.sleep(delayMs);
               mc.executeSync(() -> {
                  if (this.swapBack.getValue()) {
                     this.selectHotbarLegit(prevSlot);
                  }

                  this.endRotation();
               });
            }
         }
      }
   }

   @NotNull
   private BlockPos getCartBasePos(@NotNull BlockPos targetPos) {
      BlockState targetState = mc.world.getBlockState(targetPos);
      return !this.isRailBlock(targetState.getBlock()) && !targetState.isReplaceable() ? targetPos : targetPos.down();
   }

   private boolean hasBasicCartAuraResources() {
      SearchInvResult crossbowResult = this.findLoadedCrossbowInHotBar();
      SearchInvResult cartResult = InventoryUtility.findItemInHotBar(Items.TNT_MINECART);
      if (crossbowResult.found() && cartResult.found()) {
         boolean hasFlame = this.hasFlameEnchant(mc.player.getInventory().getStack(crossbowResult.slot()));
         if (this.flintAndSteel.getValue()) {
            return hasFlame || InventoryUtility.findItemInHotBar(Items.FLINT_AND_STEEL).found();
         } else {
            return hasFlame;
         }
      } else {
         return false;
      }
   }

   @Nullable
   private AutoCart.CartAuraPlan createCartAuraPlan(PlayerEntity target) {
      if (!this.isValidCartAuraTarget(target)) {
         return null;
      }

      SearchInvResult crossbowResult = this.findLoadedCrossbowInHotBar();
      SearchInvResult cartResult = InventoryUtility.findItemInHotBar(Items.TNT_MINECART);
      if (!crossbowResult.found() || !cartResult.found()) {
         return null;
      }

      boolean hasFlame = this.hasFlameEnchant(mc.player.getInventory().getStack(crossbowResult.slot()));
      boolean useFlint = this.flintAndSteel.getValue();
      SearchInvResult flintResult = InventoryUtility.findItemInHotBar(Items.FLINT_AND_STEEL);
      if (!hasFlame && (!useFlint || !flintResult.found())) {
         return null;
      }

      BlockPos basePos = this.findCartAuraPosition(target);
      if (basePos == null) {
         return null;
      }

      boolean railExists = this.isRailBlock(mc.world.getBlockState(basePos.up()).getBlock());
      SearchInvResult railResult = this.findRailInHotBar();
      if (!railExists && !railResult.found()) {
         return null;
      }

      Vec3d playerEyes = InteractionUtility.getEyesPos(mc.player);
      BlockPos firePos = null;
      Vec3d shootVec = new Vec3d(basePos.getX() + 0.5, basePos.getY() + 1.4, basePos.getZ() + 0.5);
      boolean fireExists = false;

      if (useFlint || !hasFlame) {
         FirePositionResult fireResult = this.findFirePosition(basePos, playerEyes, target);
         if (fireResult != null) {
            firePos = fireResult.firePos();
            shootVec = fireResult.aimPoint();
            fireExists = fireResult.fireExists();
         } else if (!hasFlame) {
            return null;
         }
      }

      boolean cartExists = this.hasExistingMinecart(basePos);

      return new AutoCart.CartAuraPlan(
         basePos, firePos, shootVec, crossbowResult, railResult, cartResult, flintResult, hasFlame, railExists, cartExists, fireExists
      );
   }

   private boolean isValidCartAuraTarget(PlayerEntity target) {
      return target != null && target != mc.player && !target.isRemoved();
   }

   private boolean isHoldingTotem(PlayerEntity player) {
      return player.getMainHandStack().getItem() == Items.TOTEM_OF_UNDYING || player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING;
   }

   private void executeCrossBowMode() {
      if (!fullNullCheck()) {
         SearchInvResult crossbowResult = this.findLoadedCrossbowInHotBar();
         SearchInvResult railResult = this.findRailInHotBar();
         SearchInvResult cartResult = InventoryUtility.findItemInHotBar(Items.TNT_MINECART);
         if (crossbowResult.found() && railResult.found() && cartResult.found()) {
            boolean hasFlame = this.hasFlameEnchant(mc.player.getInventory().getStack(crossbowResult.slot()));
            boolean useFlint = this.flintAndSteel.getValue();
            SearchInvResult flintResult = InventoryUtility.findItemInHotBar(Items.FLINT_AND_STEEL);
            if (hasFlame || (useFlint && flintResult.found())) {
               BlockHitResult rayResult = this.rayTraceFromEyes(4.5);
               if (rayResult != null && rayResult.getType() == Type.BLOCK) {
                  BlockPos hitPos = rayResult.getBlockPos();
                  if (!(PlayerUtility.squaredDistanceFromEyes(hitPos.toCenterPos()) > 20.25F)) {
                     BlockPos basePos;
                     if (mc.world.getBlockState(hitPos).isReplaceable()) {
                        basePos = hitPos.down();
                     } else {
                        basePos = hitPos;
                     }

                     Vec3d playerEyes = InteractionUtility.getEyesPos(mc.player);
                     FirePositionResult fireResult = null;
                     if (useFlint || !hasFlame) {
                        fireResult = this.findFirePosition(basePos, playerEyes, null);
                        if (!hasFlame && fireResult == null) {
                           return;
                        }
                     }

                     final FirePositionResult finalFireResult = fireResult;
                     int prevSlot = mc.player.getInventory().selectedSlot;
                     int delayMs = this.delay.getValue();
                     Managers.ASYNC.run(() -> {
                        Vec3d placeVec = new Vec3d(basePos.getX() + 0.5, basePos.up().getY(), basePos.getZ() + 0.5);
                        mc.executeSync(() -> this.applyRotation(InteractionUtility.calculateAngle(placeVec)));
                        AsyncManager.sleep(delayMs);
                        mc.executeSync(() -> {
                           if (!this.isRailBlock(mc.world.getBlockState(basePos.up()).getBlock())) {
                              this.selectHotbarLegit(railResult.slot());
                              this.placeRailOn(basePos);
                           }
                        });
                        AsyncManager.sleep(delayMs);
                        if (!this.hasExistingMinecart(basePos)) {
                           mc.executeSync(() -> {
                              this.selectHotbarLegit(cartResult.slot());
                              this.placeMinecartOn(basePos);
                           });
                           AsyncManager.sleep(delayMs);
                        }
                        if (finalFireResult != null && !finalFireResult.fireExists()) {
                           mc.executeSync(() -> {
                              BlockPos firePos = finalFireResult.firePos();
                              Vec3d fireVec = new Vec3d(firePos.getX() + 0.5, firePos.getY() + 1.0, firePos.getZ() + 0.5);
                              this.applyRotation(InteractionUtility.calculateAngle(fireVec));
                              this.selectHotbarLegit(flintResult.slot());
                              BlockHitResult fireHit = new BlockHitResult(fireVec, Direction.UP, firePos, false);
                              this.interactWithBlock(fireHit);
                           });
                           AsyncManager.sleep(delayMs);
                        }

                        mc.executeSync(() -> {
                           Vec3d shootVec = finalFireResult != null
                              ? finalFireResult.aimPoint()
                              : new Vec3d(basePos.getX() + 0.5, basePos.getY() + 1.4, basePos.getZ() + 0.5);
                           float[] shootAngle = InteractionUtility.calculateAngle(shootVec);
                           this.applyRotation(shootAngle);
                           this.selectHotbarLegit(crossbowResult.slot());
                           this.interactWithItem();
                           mc.player.swingHand(Hand.MAIN_HAND);
                           if (this.notify.getValue()) {
                              this.registerPendingShot(basePos, shootVec);
                           }
                        });
                        AsyncManager.sleep(delayMs);
                        mc.executeSync(() -> {
                           if (this.swapBack.getValue()) {
                              this.selectHotbarLegit(prevSlot);
                           }

                           this.endRotation();
                        });
                     });
                  }
               }
            }
         }
      }
   }

   private void applyRotation(float[] angle) {
      if (this.changeLook.getValue()) {
         mc.player.setYaw(angle[0]);
         mc.player.setPitch(angle[1]);
      } else {
         this.silentRotation = angle;
         this.rotating = true;
      }
   }

   private void endRotation() {
      this.silentRotation = null;
      this.rotating = false;
   }

   public boolean isAutoCartMoveFixActive() {
      return this.getAutoCartMoveFixRotation() != null;
   }

   @Nullable
   private float[] getAutoCartMoveFixRotation() {
      float[] rotation = this.silentRotation;
      return this.isOn() && !fullNullCheck() && !this.changeLook.getValue() && this.rotating && rotation != null && !mc.player.isRiding() ? rotation : null;
   }

   private Vec3d fixMovement(float yaw, Vec3d movementInput, float speed) {
      double lengthSquared = movementInput.lengthSquared();
      if (lengthSquared < 1.0E-7) {
         return Vec3d.ZERO;
      }

      Vec3d movement = (lengthSquared > 1.0 ? movementInput.normalize() : movementInput).multiply(speed);
      float sin = MathHelper.sin(yaw * (float) (Math.PI / 180.0));
      float cos = MathHelper.cos(yaw * (float) (Math.PI / 180.0));
      return new Vec3d(movement.x * cos - movement.z * sin, movement.y, movement.z * cos + movement.x * sin);
   }

   private void selectHotbarLegit(int slot) {
      if (mc.player != null && mc.interactionManager != null && slot >= 0 && slot <= 8) {
         mc.player.getInventory().selectedSlot = slot;
         ((IInteractionManager)mc.interactionManager).syncSlot();
      }
   }

   private void runWithInteractionRotation(@Nullable float[] angle, Runnable action) {
      if (mc.player != null) {
         if (!this.changeLook.getValue() && angle != null) {
            float prevYaw = mc.player.getYaw();
            float prevPitch = mc.player.getPitch();
            mc.player.setYaw(angle[0]);
            mc.player.setPitch(angle[1]);

            try {
               action.run();
            } finally {
               mc.player.setYaw(prevYaw);
               mc.player.setPitch(prevPitch);
            }
         } else {
            action.run();
         }
      }
   }

   private void interactWithItem() {
      if (mc.player != null && mc.interactionManager != null) {
         this.runWithInteractionRotation(this.silentRotation, () -> mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND));
      }
   }

   private void placeRailOn(BlockPos basePos) {
      if (mc.world != null && mc.player != null && mc.interactionManager != null) {
         BlockHitResult hitResult = new BlockHitResult(new Vec3d(basePos.getX() + 0.5, basePos.up().getY(), basePos.getZ() + 0.5), Direction.UP, basePos, false);
         this.interactWithBlock(hitResult);
      }
   }

   private void placeMinecartOn(BlockPos basePos) {
      if (mc.world != null && mc.player != null && mc.interactionManager != null) {
         BlockHitResult hitResult = new BlockHitResult(
            new Vec3d(basePos.getX() + 0.5, basePos.up().getY() + 0.125, basePos.getZ() + 0.5), Direction.UP, basePos.up(), false
         );
         this.interactWithBlock(hitResult);
      }
   }

   private void interactWithBlock(BlockHitResult hitResult) {
      this.runWithInteractionRotation(this.silentRotation, () -> mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult));
      mc.player.swingHand(Hand.MAIN_HAND);
   }

   @Nullable
   private BlockPos calcBowTrajectory(float yaw) {
      if (mc.player != null && mc.world != null) {
         double x = Render2DEngine.interpolate(mc.player.prevX, mc.player.getX(), Render3DEngine.getTickDelta());
         double y = Render2DEngine.interpolate(mc.player.prevY, mc.player.getY(), Render3DEngine.getTickDelta());
         double z = Render2DEngine.interpolate(mc.player.prevZ, mc.player.getZ(), Render3DEngine.getTickDelta());
         y += mc.player.getEyeHeight(mc.player.getPose()) - 0.1000000014901161;
         float pitch = mc.player.getPitch();
         double motionX = -MathHelper.sin(yaw / 180.0F * (float) Math.PI) * MathHelper.cos(pitch / 180.0F * (float) Math.PI);
         double motionY = -MathHelper.sin(pitch / 180.0F * (float) Math.PI);
         double motionZ = MathHelper.cos(yaw / 180.0F * (float) Math.PI) * MathHelper.cos(pitch / 180.0F * (float) Math.PI);
         float power = mc.player.getItemUseTime() / 20.0F;
         power = (power * power + power * 2.0F) / 3.0F;
         if (power > 1.0F) {
            power = 1.0F;
         }

         if (power < 0.1F) {
            return null;
         }

         float dist = MathHelper.sqrt((float)(motionX * motionX + motionY * motionY + motionZ * motionZ));
         motionX /= dist;
         motionY /= dist;
         motionZ /= dist;
         float pow = power * 3.0F;
         motionX *= pow;
         motionY *= pow;
         motionZ *= pow;
         if (!mc.player.isOnGround()) {
            motionY += mc.player.getVelocity().getY();
         }

         for (int i = 0; i < 300; i++) {
            Vec3d lastPos = new Vec3d(x, y, z);
            x += motionX;
            y += motionY;
            z += motionZ;
            motionX *= 0.99;
            motionY *= 0.99;
            motionZ *= 0.99;
            motionY -= 0.05F;

            for (Entity ent : mc.world.getEntities()) {
               if (!(ent instanceof ArrowEntity)
                  && !ent.equals(mc.player)
                  && ent.getBoundingBox().intersects(new Box(x - 0.3, y - 0.3, z - 0.3, x + 0.3, y + 0.3, z + 0.3))) {
                  return null;
               }
            }

            Vec3d pos = new Vec3d(x, y, z);
            BlockHitResult bhr = mc.world.raycast(new RaycastContext(lastPos, pos, ShapeType.OUTLINE, FluidHandling.NONE, mc.player));
            if (bhr != null && bhr.getType() == Type.BLOCK) {
               return bhr.getBlockPos();
            }

            if (y <= -65.0) {
               break;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   @Nullable
   private BlockPos findCartAuraPosition(Entity target) {
      if (mc.player != null && mc.world != null) {
         Vec3d playerEyes = InteractionUtility.getEyesPos(mc.player);
         Box targetBox = target.getBoundingBox();
         Vec3d targetFeet = new Vec3d(target.getX(), targetBox.minY, target.getZ());
         BlockPos targetBlockPos = target.getBlockPos();
         int r = 4;
         BlockPos bestPos = null;
         double bestDist = Double.MAX_VALUE;

         for (int x = targetBlockPos.getX() - r; x <= targetBlockPos.getX() + r; x++) {
            for (int z = targetBlockPos.getZ() - r; z <= targetBlockPos.getZ() + r; z++) {
               for (int y = targetBlockPos.getY() - r; y <= targetBlockPos.getY(); y++) {
                  BlockPos bp = new BlockPos(x, y, z);
                  if (mc.world.getBlockState(bp).isSolid() && !(bp.getY() + 1 > targetBox.minY)) {
                     BlockState aboveState = mc.world.getBlockState(bp.up());
                     if (aboveState.isReplaceable() || this.isRailBlock(aboveState.getBlock())) {
                        Vec3d surfacePos = new Vec3d(bp.getX() + 0.5, bp.getY() + 1.0, bp.getZ() + 0.5);
                        if (!(PlayerUtility.squaredDistanceFromEyes(surfacePos) > 20.25F)) {
                           BlockHitResult blockCheck = mc.world
                              .raycast(new RaycastContext(playerEyes, surfacePos, ShapeType.COLLIDER, FluidHandling.NONE, mc.player));
                           if ((blockCheck == null || blockCheck.getType() != Type.BLOCK || blockCheck.getBlockPos().equals(bp))
                              && this.isPathClearOfPlayers(playerEyes, surfacePos, target)) {
                              Vec3d cartCenter = new Vec3d(bp.getX() + 0.5, bp.getY() + 1.4, bp.getZ() + 0.5);
                              BlockHitResult damageCheck = mc.world
                                 .raycast(new RaycastContext(cartCenter, targetFeet, ShapeType.COLLIDER, FluidHandling.NONE, mc.player));
                              if (damageCheck == null || damageCheck.getType() != Type.BLOCK) {
                                 SearchInvResult cbResult = this.findLoadedCrossbowInHotBar();
                                 boolean hasFlame = cbResult.found() && this.hasFlameEnchant(mc.player.getInventory().getStack(cbResult.slot()));
                                 boolean useFlint = this.flintAndSteel.getValue();
                                 if (!hasFlame && useFlint) {
                                    FirePositionResult fireResult = this.findFirePosition(bp, playerEyes, target);
                                    if (fireResult == null) {
                                       continue;
                                    }
                                 } else if (!hasFlame && !useFlint) {
                                    continue;
                                 }

                                 double distToTarget = cartCenter.squaredDistanceTo(targetFeet);
                                 if (distToTarget < bestDist) {
                                    bestDist = distToTarget;
                                    bestPos = bp;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         return bestPos;
      } else {
         return null;
      }
   }

   private boolean isPathClearOfPlayers(Vec3d from, Vec3d to, Entity exclude) {
      if (mc.world == null) {
         return true;
      }

      for (PlayerEntity player : mc.world.getPlayers()) {
         if (player != mc.player && player != exclude && player.getBoundingBox().raycast(from, to).isPresent()) {
            return false;
         }
      }

      return true;
   }

   private SearchInvResult findRailInHotBar() {
      return InventoryUtility.findItemInHotBar(Items.RAIL, Items.ACTIVATOR_RAIL, Items.DETECTOR_RAIL, Items.POWERED_RAIL);
   }

   private void renderCrossbowDebug(DrawContext context) {
      int hotbarLeft = mc.getWindow().getScaledWidth() / 2 - 91;
      int hotbarTop = mc.getWindow().getScaledHeight() - 22;

      for (int slot = 0; slot < 9; slot++) {
         ItemStack stack = mc.player.getInventory().getStack(slot);
         if (this.isCrossbowDebugCandidate(stack)) {
            float slotX = hotbarLeft + slot * 20 + 1;
            float slotY = hotbarTop + 1;
            Render2DEngine.drawRect(context.getMatrices(), slotX, slotY, 20.0F, 20.0F, CROSSBOW_DEBUG_FILL);
         }
      }
   }

   private boolean hasCrossbowDebugBaseRequirements() {
      if (this.findRailInHotBar().found() && InventoryUtility.findItemInHotBar(Items.TNT_MINECART).found()) {
         if (!this.hasArrowAmmoInInventory()) {
            return false;
         }

         for (int slot = 0; slot < 9; slot++) {
            if (this.isCrossbowDebugCandidate(mc.player.getInventory().getStack(slot))) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private boolean isCrossbowDebugCandidate(ItemStack stack) {
      if (!this.isUnloadedCrossbow(stack)) {
         return false;
      }
      if (this.flintAndSteel.getValue()) {
         return this.hasFlameEnchant(stack) || InventoryUtility.findItemInHotBar(Items.FLINT_AND_STEEL).found();
      } else {
         return this.hasFlameEnchant(stack);
      }
   }

   private boolean hasArrowAmmoInInventory() {
      for (ItemStack stack : mc.player.getInventory().main) {
         if (this.isArrowStack(stack)) {
            return true;
         }
      }

      for (ItemStack stack : mc.player.getInventory().offHand) {
         if (this.isArrowStack(stack)) {
            return true;
         }
      }

      return false;
   }

   private boolean isArrowStack(ItemStack stack) {
      return !stack.isEmpty() && stack.getItem() instanceof ArrowItem;
   }

   private boolean isUnloadedCrossbow(ItemStack stack) {
      return stack.getItem() == Items.CROSSBOW && !this.isCrossbowCharged(stack);
   }

   private boolean isCrossbowCharged(ItemStack stack) {
      return stack.getItem() == Items.CROSSBOW
         && stack.get(DataComponentTypes.CHARGED_PROJECTILES) != null
         && !((ChargedProjectilesComponent)stack.get(DataComponentTypes.CHARGED_PROJECTILES)).isEmpty();
   }

   private SearchInvResult findLoadedCrossbowInHotBar() {
      return InventoryUtility.findInHotBar(this::isCrossbowCharged);
   }

   private boolean isRailBlock(Block block) {
      return block == Blocks.RAIL || block == Blocks.POWERED_RAIL || block == Blocks.DETECTOR_RAIL || block == Blocks.ACTIVATOR_RAIL;
   }

   private boolean hasFlameEnchant(ItemStack stack) {
      return mc.world == null
         ? false
         : EnchantmentHelper.getLevel(
               (RegistryEntry)mc.world.getRegistryManager().get(Enchantments.FLAME.getRegistryRef()).getEntry(Enchantments.FLAME).get(), stack
            )
            > 0;
   }

   @Nullable
   private BlockHitResult rayTraceFromEyes(double maxRange) {
      if (mc.player != null && mc.world != null) {
         Vec3d eyePos = mc.player.getEyePos();
         Vec3d lookVec = mc.player.getRotationVec(1.0F);
         Vec3d endPos = eyePos.add(lookVec.multiply(maxRange));
         return mc.world.raycast(new RaycastContext(eyePos, endPos, ShapeType.OUTLINE, FluidHandling.NONE, mc.player));
      } else {
         return null;
      }
   }

   @Nullable
   private FirePositionResult findFirePosition(BlockPos basePos, Vec3d playerEyes, @Nullable Entity target) {
      if (mc.world == null || mc.player == null) {
         return null;
      }

      Box cartBox = new Box(
         basePos.getX() + 0.01,
         basePos.getY() + 1.0625,
         basePos.getZ() + 0.01,
         basePos.getX() + 0.99,
         basePos.getY() + 1.7625,
         basePos.getZ() + 0.99
      );
      Vec3d defaultCartCenter = new Vec3d(basePos.getX() + 0.5, basePos.getY() + 1.4, basePos.getZ() + 0.5);

      FirePositionResult bestResult = null;
      double bestDist = Double.MAX_VALUE;

      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) {
               continue;
            }
            for (int dy = -1; dy <= 1; dy++) {
               BlockPos candidate = basePos.add(dx, dy, dz);
               if (!mc.world.getBlockState(candidate).isSolid()) {
                  continue;
               }

               BlockPos fireBlockPos = candidate.up();
               BlockState fireState = mc.world.getBlockState(fireBlockPos);
               boolean isFire = fireState.getBlock() == Blocks.FIRE;
               boolean isAir = fireState.isAir() || fireState.isReplaceable();

               if (!isFire && !isAir) {
                  continue;
               }
               if (this.isRailBlock(fireState.getBlock())) {
                  continue;
               }

               Vec3d fireSurface = new Vec3d(candidate.getX() + 0.5, candidate.getY() + 1.0, candidate.getZ() + 0.5);
               if (PlayerUtility.squaredDistanceFromEyes(fireSurface) > 20.25F) {
                  continue;
               }

               if (!isFire) {
                  BlockHitResult placeCheck = mc.world.raycast(
                     new RaycastContext(playerEyes, fireSurface, ShapeType.COLLIDER, FluidHandling.NONE, mc.player)
                  );
                  if (placeCheck != null
                     && placeCheck.getType() == Type.BLOCK
                     && !placeCheck.getBlockPos().equals(candidate)
                     && !placeCheck.getBlockPos().equals(fireBlockPos)) {
                     continue;
                  }
               }

               Box fireBox = new Box(
                  candidate.getX(),
                  candidate.getY() + 1.0,
                  candidate.getZ(),
                  candidate.getX() + 1.0,
                  candidate.getY() + 2.0,
                  candidate.getZ() + 1.0
               );

               Vec3d validAimPoint = null;
               double[] xOffsets = new double[]{0.0, -0.2, 0.2, -0.35, 0.35};
               double[] yOffsets = new double[]{0.0, -0.15, 0.15};
               double[] zOffsets = new double[]{0.0, -0.2, 0.2, -0.35, 0.35};

               for (double xo : xOffsets) {
                  for (double yo : yOffsets) {
                     for (double zo : zOffsets) {
                        Vec3d testPt = defaultCartCenter.add(xo, yo, zo);
                        if (!cartBox.contains(testPt)) {
                           continue;
                        }

                        if (fireBox.raycast(playerEyes, testPt).isPresent()) {
                           BlockHitResult shotCheck = mc.world.raycast(
                              new RaycastContext(playerEyes, testPt, ShapeType.COLLIDER, FluidHandling.NONE, mc.player)
                           );
                           if (shotCheck == null || shotCheck.getType() != Type.BLOCK) {
                              if (target == null || this.isPathClearOfPlayers(playerEyes, testPt, target)) {
                                 validAimPoint = testPt;
                                 break;
                              }
                           }
                        }
                     }
                     if (validAimPoint != null) {
                        break;
                     }
                  }
                  if (validAimPoint != null) {
                     break;
                  }
               }

               if (validAimPoint != null) {
                  double dist = candidate.getSquaredDistance(basePos);
                  if (dist < bestDist) {
                     bestDist = dist;
                     bestResult = new FirePositionResult(candidate, validAimPoint, isFire);
                  }
               }
            }
         }
      }

      return bestResult;
   }

   @Nullable
   private BlockPos findFirePosition(BlockPos targetPos) {
      if (mc.player == null) {
         return null;
      }
      FirePositionResult result = this.findFirePosition(targetPos, InteractionUtility.getEyesPos(mc.player), null);
      return result != null ? result.firePos() : null;
   }

   private boolean hasExistingMinecart(BlockPos basePos) {
      if (mc.world == null) {
         return false;
      }
      Box box = new Box(basePos.up());
      List<TntMinecartEntity> carts = mc.world.getEntitiesByClass(
         TntMinecartEntity.class, box, cart -> cart.isAlive() && !cart.isRemoved()
      );
      return !carts.isEmpty();
   }

   @Override
   public String getDisplayInfo() {
      return this.mode.getValue().name();
   }

   private record CartAuraPlan(
      BlockPos basePos,
      @Nullable BlockPos firePos,
      Vec3d shootVec,
      SearchInvResult crossbowResult,
      SearchInvResult railResult,
      SearchInvResult cartResult,
      SearchInvResult flintResult,
      boolean hasFlame,
      boolean railExists,
      boolean cartExists,
      boolean fireExists
   ) {
   }

   private void notifyDone() {
      this.sendMessage(Formatting.GREEN + "done!");
      mc.execute(() -> Managers.NOTIFICATION.publicity("AutoCart", "done!", 2, Notification.Type.SUCCESS));
   }

   private void notifyFail() {
      this.sendMessage(Formatting.RED + "fail");
      mc.execute(() -> Managers.NOTIFICATION.publicity("AutoCart", "fail", 2, Notification.Type.ERROR));
   }

   @Nullable
   private TntMinecartEntity getTargetedMinecart(double maxDistance) {
      if (mc.player == null || mc.world == null) {
         return null;
      }
      Vec3d eyes = InteractionUtility.getEyesPos(mc.player);
      Vec3d look = mc.player.getRotationVec(1.0F);
      Vec3d end = eyes.add(look.multiply(maxDistance));

      Box searchBox = mc.player.getBoundingBox().stretch(look.multiply(maxDistance)).expand(1.0);
      List<TntMinecartEntity> carts = mc.world.getEntitiesByClass(
         TntMinecartEntity.class, searchBox, cart -> cart.isAlive() && !cart.isRemoved()
      );

      TntMinecartEntity closestCart = null;
      double closestDist = maxDistance * maxDistance;

      for (TntMinecartEntity cart : carts) {
         Box box = cart.getBoundingBox().expand(0.2);
         var hitOpt = box.raycast(eyes, end);
         if (hitOpt.isPresent()) {
            double dist = eyes.squaredDistanceTo(hitOpt.get());
            if (dist < closestDist) {
               BlockHitResult bhr = mc.world.raycast(
                  new RaycastContext(eyes, hitOpt.get(), ShapeType.COLLIDER, FluidHandling.NONE, mc.player)
               );
               if (bhr == null || bhr.getType() == Type.MISS || eyes.squaredDistanceTo(bhr.getPos()) >= dist - 0.1) {
                  closestDist = dist;
                  closestCart = cart;
               }
            }
         }
      }
      return closestCart;
   }

   private void registerPendingShot(@NotNull BlockPos basePos, @NotNull Vec3d aimPoint) {
      if (mc.player == null || mc.world == null) {
         return;
      }
      this.lastAutoShotTime = System.currentTimeMillis();
      Vec3d eyes = InteractionUtility.getEyesPos(mc.player);
      Box cartBox = new Box(basePos.getX(), basePos.getY() + 1.0, basePos.getZ(), basePos.getX() + 1.0, basePos.getY() + 2.0, basePos.getZ() + 1.0);

      int cartId = -1;
      List<TntMinecartEntity> carts = mc.world.getEntitiesByClass(
         TntMinecartEntity.class, cartBox.expand(0.5), Entity::isAlive
      );
      if (!carts.isEmpty()) {
         cartId = carts.get(0).getId();
      }

      boolean pathClear = this.isPathClearToPoint(eyes, aimPoint);
      boolean passesThroughFire = this.hasFlameEnchant(mc.player.getMainHandStack()) || this.doesRayPassThroughFire(eyes, aimPoint);

      this.pendingShots.add(new AutoCart.PendingShot(
         this.lastAutoShotTime, eyes, aimPoint, cartBox, basePos, cartId, passesThroughFire, true, pathClear
      ));
   }

   private void registerManualShot(@NotNull TntMinecartEntity cart, @NotNull Vec3d aimPoint) {
      if (mc.player == null || mc.world == null) {
         return;
      }
      Vec3d eyes = InteractionUtility.getEyesPos(mc.player);
      Box cartBox = cart.getBoundingBox();
      BlockPos basePos = BlockPos.ofFloored(cart.getX(), cart.getY() - 0.5, cart.getZ());

      boolean cartExisted = cart.isAlive() && !cart.isRemoved();
      boolean pathClear = this.isPathClearToPoint(eyes, aimPoint);
      boolean passesThroughFire = this.hasFlameEnchant(mc.player.getMainHandStack())
         || this.hasFlameEnchant(mc.player.getOffHandStack())
         || this.doesRayPassThroughFire(eyes, aimPoint);

      this.pendingShots.add(new AutoCart.PendingShot(
         System.currentTimeMillis(), eyes, aimPoint, cartBox, basePos, cart.getId(), passesThroughFire, cartExisted, pathClear
      ));
   }

   private void checkManualCrossbowShot() {
      if (mc.player == null || mc.world == null) {
         return;
      }
      if (System.currentTimeMillis() - this.lastAutoShotTime < 250L) {
         return;
      }

      TntMinecartEntity targetCart = this.getTargetedMinecart(6.0);
      if (targetCart != null) {
         Vec3d eyes = InteractionUtility.getEyesPos(mc.player);
         Vec3d look = mc.player.getRotationVec(1.0F);
         Vec3d aimPoint = targetCart.getBoundingBox().raycast(eyes, eyes.add(look.multiply(6.0))).orElse(targetCart.getBoundingBox().getCenter());
         this.registerManualShot(targetCart, aimPoint);
      }
   }

   private boolean isPathClearToPoint(Vec3d start, Vec3d end) {
      if (mc.world == null || mc.player == null) {
         return false;
      }
      BlockHitResult hit = mc.world.raycast(
         new RaycastContext(start, end, ShapeType.COLLIDER, FluidHandling.NONE, mc.player)
      );
      if (hit != null && hit.getType() == Type.BLOCK) {
         if (start.squaredDistanceTo(hit.getPos()) < start.squaredDistanceTo(end) - 0.25) {
            return false;
         }
      }
      return this.isPathClearOfOtherEntities(start, end, null);
   }

   private boolean isPathClearOfOtherEntities(Vec3d start, Vec3d end, @Nullable Entity targetCart) {
      if (mc.world == null || mc.player == null) {
         return false;
      }
      Box searchBox = new Box(start, end).expand(0.5);
      for (Entity entity : mc.world.getOtherEntities(mc.player, searchBox)) {
         if (entity == targetCart || entity instanceof ArrowEntity || entity instanceof TntMinecartEntity) {
            continue;
         }
         Box box = entity.getBoundingBox().expand(0.1);
         if (box.raycast(start, end).isPresent()) {
            return false;
         }
      }
      return true;
   }

   private boolean doesRayPassThroughFire(Vec3d start, Vec3d end) {
      if (mc.world == null) {
         return false;
      }
      double dist = start.distanceTo(end);
      if (dist < 0.1) {
         return false;
      }
      Vec3d dir = end.subtract(start).normalize();
      int steps = (int) Math.ceil(dist / 0.15);
      for (int i = 0; i <= steps; i++) {
         Vec3d pt = start.add(dir.multiply(i * 0.15));
         BlockPos pos = BlockPos.ofFloored(pt);
         BlockState state = mc.world.getBlockState(pos);
         if (state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)) {
            return true;
         }
      }
      return false;
   }

   private void handleExplosion(ExplosionS2CPacket explosion) {
      double ex = explosion.getX();
      double ey = explosion.getY();
      double ez = explosion.getZ();
      for (AutoCart.PendingShot shot : this.pendingShots) {
         if (!shot.finished && shot.getDistanceSq(ex, ey, ez) <= 9.0) {
            shot.finished = true;
            this.notifyDone();
            this.pendingShots.remove(shot);
         }
      }
   }

   private void checkPendingShots() {
      if (this.pendingShots.isEmpty()) {
         return;
      }
      long now = System.currentTimeMillis();
      long timeout = Math.max(450L, Managers.SERVER.getPing() * 2L + 150L);

      for (AutoCart.PendingShot shot : this.pendingShots) {
         if (shot.finished) {
            this.pendingShots.remove(shot);
            continue;
         }

         long elapsed = now - shot.timestamp;
         if (shot.coldHitDetected || elapsed >= timeout) {
            shot.finished = true;
            this.pendingShots.remove(shot);
            if (shot.cartExisted && shot.pathClear) {
               this.notifyFail();
            }
         }
      }
   }

   private static class PendingShot {
      final long timestamp;
      final Vec3d shootOrigin;
      final Vec3d aimPoint;
      final Box cartBox;
      final BlockPos basePos;
      final int cartEntityId;
      final boolean passesThroughFire;
      final boolean cartExisted;
      final boolean pathClear;
      volatile boolean finished = false;
      volatile boolean coldHitDetected = false;

      PendingShot(
         long timestamp,
         Vec3d shootOrigin,
         Vec3d aimPoint,
         Box cartBox,
         BlockPos basePos,
         int cartEntityId,
         boolean passesThroughFire,
         boolean cartExisted,
         boolean pathClear
      ) {
         this.timestamp = timestamp;
         this.shootOrigin = shootOrigin;
         this.aimPoint = aimPoint;
         this.cartBox = cartBox;
         this.basePos = basePos;
         this.cartEntityId = cartEntityId;
         this.passesThroughFire = passesThroughFire;
         this.cartExisted = cartExisted;
         this.pathClear = pathClear;
      }

      double getDistanceSq(double x, double y, double z) {
         Vec3d center = this.cartBox.getCenter();
         double dx = center.x - x;
         double dy = center.y - y;
         double dz = center.z - z;
         return dx * dx + dy * dy + dz * dz;
      }
   }

   public record FirePositionResult(BlockPos firePos, Vec3d aimPoint, boolean fireExists) {
   }

   public enum Mode {
      Bow,
      CrossBow;
   }

   public enum ReFillMode {
      None,
      Normal,
      Legit;
   }
}
