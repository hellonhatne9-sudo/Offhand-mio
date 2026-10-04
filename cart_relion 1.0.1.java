package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.mixin.accessor.MultiPlayerGameModeAccessor;
import releon.ru.api.drag.impl.Notifications;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;
import releon.ru.utils.player.CartScanner;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.repository.friend.FriendUtils;
import releon.ru.utils.timer.StopWatch;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class AutoCart extends Module {

    private final ModeSetting mode = register(new ModeSetting("Mode", "AutoCart operating mode.", "Bow", "Bow", "CrossBow"));
    private final NumberSetting maxDistance = register(new NumberSetting("Max Distance", "Maximum distance for bow trajectory target.", 4.5, 2.0, 6.0, 0.1));
    private final NumberSetting startDelay = register(new NumberSetting("Start Delay", "Start delay in ticks for bow placement.", 0.0, 0.0, 60.0, 1.0));
    private final BindSetting activeBind = register(new BindSetting("Active Bind", "Key to trigger CrossBow mode.", KeyBind.NONE));
    private final NumberSetting delay = register(new NumberSetting("Delay", "Step delay in milliseconds.", 25.0, 0.0, 100.0, 5.0));
    private final NumberSetting cartAuraDelay = register(new NumberSetting("Cart Aura Delay", "Delay in ticks before cart aura triggers.", 0.0, 0.0, 20.0, 1.0));
    private final NumberSetting refillSlot = register(new NumberSetting("Refill Slot", "Hotbar slot for TNT carts (1-9).", 9.0, 1.0, 9.0, 1.0));
    private final BooleanSetting swapBack = register(new BooleanSetting("Swap Back", "Swap back to previous slot.", true));
    private final BooleanSetting totemCheck = register(new BooleanSetting("Totem Check", "Do not target player if they hold a totem.", true));
    private final BooleanSetting changeLook = register(new BooleanSetting("Change Look", "Visibly turn player camera.", false));
    private final BooleanSetting cartAura = register(new BooleanSetting("Cart Aura", "Automatically setup and detonate on totem pop.", true));
    private final BooleanSetting flintAndSteel = register(new BooleanSetting("Flint and Steel", "Use flint and steel if crossbow has no flame.", true));
    private final BooleanSetting notify = register(new BooleanSetting("Notify", "Show client notifications.", true));
    private final ModeSetting reFill = register(new ModeSetting("ReFill", "Auto refill TNT carts from inventory.", "Normal", "None", "Normal", "Legit"));

    private volatile Angle silentRotation = null;
    private volatile boolean rotating = false;
    private boolean crossBowPressed = false;
    private volatile boolean cartAuraExecuting = false;
    private UUID lastAuraTargetUuid = null;
    private int lastAuraTargetId = -1;
    private long lastAuraTargetAt = -1L;
    private final StopWatch refillTimer = new StopWatch();

    public AutoCart() {
        super("AutoCart", "Automates TNT minecart combat setups.", ModuleCategory.COMBAT);
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    private void resetState() {
        silentRotation = null;
        rotating = false;
        crossBowPressed = false;
        cartAuraExecuting = false;
        lastAuraTargetUuid = null;
        lastAuraTargetId = -1;
        lastAuraTargetAt = -1L;
        AngleConnection.INSTANCE.setRotation(null);
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        Minecraft client = event.getClient();
        if (client.player == null || client.level == null) {
            resetState();
            return;
        }

        handleRefill(client);

        if (mode.is("CrossBow")) {
            updateCartAuraTargetMemory();
            boolean pressed = PlayerInteractionHelper.isKey(activeBind);
            if (pressed && !crossBowPressed) {
                crossBowPressed = true;
                executeCrossBowMode(client);
            } else if (!pressed) {
                crossBowPressed = false;
            }
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        if (event.isSend()) {
            if (mode.is("Bow")) {
                if (event.getPacket() instanceof ServerboundPlayerActionPacket action) {
                    if (action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
                            && (client.player.getMainHandItem().is(Items.BOW) || client.player.getOffhandItem().is(Items.BOW))) {
                        executeBowMode(client);
                    }
                }
            }
        } else if (event.isReceive()) {
            if (mode.is("CrossBow") && cartAura.getValue() && !cartAuraExecuting) {
                if (event.getPacket() instanceof ClientboundEntityEventPacket packet && packet.getEventId() == 35) {
                    Entity entity = packet.getEntity(client.level);
                    if (entity instanceof Player player && player != client.player) {
                        handleCartAuraPop(client, player);
                    }
                }
            }
        }
    }

    private void handleCartAuraPop(Minecraft client, Player popTarget) {
        if (cartAuraExecuting || !cartAura.getValue() || popTarget == null || !popTarget.isAlive()) return;
        if (FriendUtils.isFriend(popTarget)) return;

        updateCartAuraTargetMemory();
        boolean isAuraTarget = isRememberedAuraTarget(popTarget);
        boolean isNearbyPlayer = client.player.position().distanceTo(popTarget.position()) <= 6.0;

        if (isAuraTarget || isNearbyPlayer) {
            if (totemCheck.getValue() && isHoldingTotem(popTarget)) {
                return;
            }
            executeCartAura(client, popTarget);
        }
    }

    private void executeCartAura(Minecraft client, Player target) {
        if (cartAuraExecuting) return;
        cartAuraExecuting = true;
        long delayMs = delay.getValue().longValue();
        long startDelayMs = (long) (cartAuraDelay.getValue() * 50);

        CompletableFuture.runAsync(() -> {
            try {
                if (startDelayMs > 0) sleep(startDelayMs);
                if (!isEnabled() || !mode.is("CrossBow") || !cartAura.getValue()) return;

                int crossbowSlot = findLoadedCrossbowInHotbar(client);
                int cartSlot = InventoryUtils.findHotbarItem(Items.TNT_MINECART);
                int railSlot = findRailInHotbar(client);
                int flintSlot = flintAndSteel.getValue() ? InventoryUtils.findHotbarItem(Items.FLINT_AND_STEEL) : -1;
                boolean hasFlame = crossbowSlot != -1 && hasFlameEnchant(client.player.getInventory().getItem(crossbowSlot));

                if (crossbowSlot == -1 || cartSlot == -1) return;
                if (!hasFlame && flintSlot == -1) return;

                boolean requiresFire = !hasFlame || flintAndSteel.getValue();
                CartScanner.CartSetup setup = CartScanner.findBestCartSetup(client, target, 4, requiresFire);
                if (setup == null) return;
                
                BlockPos basePos = setup.cartPos;
                boolean railExists = isRailBlock(client.level.getBlockState(basePos.above()).getBlock());
                if (!railExists && railSlot == -1) return;

                FirePositionResult fireResult = null;
                if (setup.firePos != null) {
                    fireResult = new FirePositionResult(setup.firePos, setup.aimPos, setup.fireAlreadyExists);
                } else if (requiresFire) {
                    return; // Should not happen since CartScanner checks it
                }

                int prevSlot = client.player.getInventory().getSelectedSlot();
                executeCrossBowPlacement(client, basePos, fireResult, railSlot, cartSlot, flintSlot, crossbowSlot, prevSlot, delayMs);
            } finally {
                cartAuraExecuting = false;
            }
        });
    }

    private void executeBowMode(Minecraft client) {
        if (client.player == null || client.level == null) return;
        int bowSlot = InventoryUtils.findHotbarItem(Items.BOW);
        int cartSlot = InventoryUtils.findHotbarItem(Items.TNT_MINECART);
        int railSlot = findRailInHotbar(client);

        if (bowSlot == -1 || cartSlot == -1) return;

        BlockPos targetPos = calcBowTrajectory(client, client.player.getYRot());
        if (targetPos != null) {
            BlockPos basePos = getCartBasePos(client, targetPos);
            boolean railExists = isRailBlock(client.level.getBlockState(basePos.above()).getBlock());

            if (railExists || railSlot != -1) {
                double distSq = client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(basePos.above()));
                double maxDist = maxDistance.getValue();
                double maxDistSq = maxDist * maxDist;
                double safeDistSq = 4.0;

                if (distSq <= maxDistSq && distSq >= safeDistSq) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            long startDelayMs = (long) (startDelay.getValue() * 50);
                            if (startDelayMs > 0) sleep(startDelayMs);
                            executeBowPlacement(client, basePos, railSlot, cartSlot);
                        } catch (Exception ignored) {}
                    });
                }
            }
        }
    }

    private void executeBowPlacement(Minecraft client, BlockPos basePos, int railSlot, int cartSlot) {
        if (client.player == null || client.level == null || !isEnabled() || !mode.is("Bow")) return;
        int prevSlot = client.player.getInventory().getSelectedSlot();
        long delayMs = delay.getValue().longValue();

        try {
            boolean railExists = isRailBlock(client.level.getBlockState(basePos.above()).getBlock());
            Vec3 placeVec = new Vec3(basePos.getX() + 0.5, basePos.above().getY(), basePos.getZ() + 0.5);
            Angle placeAngle = MathAngle.calculateAngle(placeVec);

            runOnClientThreadAndWait(client, () -> applyRotation(client, placeAngle));
            sleep(delayMs);

            if (!railExists && railSlot != -1) {
                runOnClientThreadAndWait(client, () -> {
                    if (!isRailBlock(client.level.getBlockState(basePos.above()).getBlock())) {
                        selectHotbarLegit(client, railSlot);
                        placeRailOn(client, basePos);
                    }
                });
                sleep(delayMs);
            }

            runOnClientThreadAndWait(client, () -> {
                selectHotbarLegit(client, cartSlot);
                placeMinecartOn(client, basePos);
            });
            sleep(delayMs);

            runOnClientThreadAndWait(client, () -> {
                if (swapBack.getValue()) {
                    selectHotbarLegit(client, prevSlot);
                }
                endRotation(client);
            });
        } finally {
            endRotation(client);
        }
    }

    private void executeCrossBowMode(Minecraft client) {
        if (client.player == null || client.level == null) return;
        int crossbowSlot = findLoadedCrossbowInHotbar(client);
        int cartSlot = InventoryUtils.findHotbarItem(Items.TNT_MINECART);
        int railSlot = findRailInHotbar(client);

        if (crossbowSlot == -1) {
            if (notify.getValue()) Notifications.push("AutoCart", "Không tìm thấy Nỏ đã nạp tên trong hotbar!");
            return;
        }
        if (cartSlot == -1) {
            if (notify.getValue()) Notifications.push("AutoCart", "Không tìm thấy Xe mìn TNT trong hotbar!");
            return;
        }
        if (railSlot == -1) {
            if (notify.getValue()) Notifications.push("AutoCart", "Không tìm thấy Đường ray trong hotbar!");
            return;
        }

        boolean hasFlame = hasFlameEnchant(client.player.getInventory().getItem(crossbowSlot));
        boolean useFlint = flintAndSteel.getValue();
        int flintSlot = InventoryUtils.findHotbarItem(Items.FLINT_AND_STEEL);
        if (!hasFlame && (!useFlint || flintSlot == -1)) {
            if (notify.getValue()) Notifications.push("AutoCart", "Cần Nỏ Flame hoặc Bật lửa (Flint and Steel)!");
            return;
        }

        BlockHitResult rayResult = rayTraceFromEyes(client, 4.5);
        if (rayResult != null && rayResult.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = rayResult.getBlockPos();
            if (client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(hitPos)) > 20.25) return;

            BlockPos basePos = client.level.getBlockState(hitPos).canBeReplaced() ? hitPos.below() : hitPos;
            Vec3 playerEyes = client.player.getEyePosition();
            Vec3 cartCenter = new Vec3(basePos.getX() + 0.5, basePos.getY() + 1.4, basePos.getZ() + 0.5);

            FirePositionResult fireResult = null;
            if (useFlint || !hasFlame) {
                CartScanner.FirePlacement placement = CartScanner.findFirePlacement(client, basePos, cartCenter, playerEyes, null);
                if (placement != null) {
                    fireResult = new FirePositionResult(placement.firePos, placement.aimPoint, placement.alreadyExists);
                }
                
                if (!hasFlame && fireResult == null) {
                    if (notify.getValue()) Notifications.push("AutoCart", "Không tìm thấy vị trí đốt lửa hợp lệ!");
                    return;
                }
            }

            final FirePositionResult finalFireResult = fireResult;
            int prevSlot = client.player.getInventory().getSelectedSlot();
            long delayMs = delay.getValue().longValue();

            CompletableFuture.runAsync(() -> {
                executeCrossBowPlacement(client, basePos, finalFireResult, railSlot, cartSlot, flintSlot, crossbowSlot, prevSlot, delayMs);
            });
        }
    }

    private void executeCrossBowPlacement(Minecraft client, BlockPos basePos, FirePositionResult finalFireResult,
                                          int railSlot, int cartSlot, int flintSlot, int crossbowSlot,
                                          int prevSlot, long delayMs) {
        try {
            boolean railExists = isRailBlock(client.level.getBlockState(basePos.above()).getBlock());
            Vec3 placeVec = new Vec3(basePos.getX() + 0.5, basePos.above().getY(), basePos.getZ() + 0.5);
            Angle placeAngle = MathAngle.calculateAngle(placeVec);

            runOnClientThreadAndWait(client, () -> applyRotation(client, placeAngle));
            sleep(delayMs);

            if (!railExists) {
                runOnClientThreadAndWait(client, () -> {
                    if (!isRailBlock(client.level.getBlockState(basePos.above()).getBlock())) {
                        selectHotbarLegit(client, railSlot);
                        placeRailOn(client, basePos);
                    }
                });
                sleep(delayMs);
            }

            if (!hasExistingMinecart(client, basePos)) {
                runOnClientThreadAndWait(client, () -> {
                    selectHotbarLegit(client, cartSlot);
                    placeMinecartOn(client, basePos);
                });
                sleep(delayMs);
            }

            if (finalFireResult != null && !finalFireResult.fireExists()) {
                runOnClientThreadAndWait(client, () -> {
                    BlockPos firePos = finalFireResult.firePos();
                    Vec3 fireVec = new Vec3(firePos.getX() + 0.5, firePos.getY() + 1.0, firePos.getZ() + 0.5);
                    Angle fireAngle = MathAngle.calculateAngle(fireVec);
                    applyRotation(client, fireAngle);
                    selectHotbarLegit(client, flintSlot);
                    BlockHitResult fireHit = new BlockHitResult(fireVec, Direction.UP, firePos, false);
                    interactWithBlock(client, fireHit, fireAngle);
                });
                sleep(delayMs);
            }

            runOnClientThreadAndWait(client, () -> {
                Vec3 shootVec = finalFireResult != null
                        ? finalFireResult.aimPoint()
                        : new Vec3(basePos.getX() + 0.5, basePos.getY() + 1.4, basePos.getZ() + 0.5);
                Angle shootAngle = MathAngle.calculateAngle(shootVec);
                applyRotation(client, shootAngle);
                selectHotbarLegit(client, crossbowSlot);
                interactWithItem(client, shootAngle);
                if (notify.getValue()) {
                    Notifications.push("AutoCart", "Đã kích nổ TNT Minecart tại " + basePos.toShortString());
                }
            });
            sleep(delayMs);

            runOnClientThreadAndWait(client, () -> {
                if (swapBack.getValue()) {
                    selectHotbarLegit(client, prevSlot);
                }
                endRotation(client);
            });
        } finally {
            endRotation(client);
        }
    }

    private void runOnClientThreadAndWait(Minecraft client, Runnable action) {
        if (client.isSameThread()) {
            action.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
            client.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    errorRef.set(e);
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            RuntimeException error = errorRef.get();
            if (error != null) throw error;
        }
    }

    private void selectHotbarLegit(Minecraft client, int slot) {
        if (client.player == null || client.gameMode == null || slot < 0 || slot > 8) return;
        client.player.getInventory().setSelectedSlot(slot);
        ((MultiPlayerGameModeAccessor) client.gameMode).releon$ensureHasSentCarriedItem();
    }

    private void placeRailOn(Minecraft client, BlockPos basePos) {
        Vec3 hitVec = new Vec3(basePos.getX() + 0.5, basePos.above().getY(), basePos.getZ() + 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, basePos, false);
        interactWithBlock(client, hitResult, MathAngle.calculateAngle(hitVec));
    }

    private void placeMinecartOn(Minecraft client, BlockPos basePos) {
        Vec3 hitVec = new Vec3(basePos.getX() + 0.5, basePos.above().getY() + 0.125, basePos.getZ() + 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, basePos.above(), false);
        interactWithBlock(client, hitResult, MathAngle.calculateAngle(hitVec));
    }

    private void interactWithBlock(Minecraft client, BlockHitResult hitResult, Angle angle) {
        if (client.player == null || client.gameMode == null) return;
        runWithInteractionRotation(client, angle, () -> {
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
            client.player.swing(InteractionHand.MAIN_HAND);
        });
    }

    private void interactWithItem(Minecraft client, Angle angle) {
        if (client.player == null || client.gameMode == null) return;
        runWithInteractionRotation(client, angle, () -> {
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            client.player.swing(InteractionHand.MAIN_HAND);
        });
    }

    private void runWithInteractionRotation(Minecraft client, Angle angle, Runnable action) {
        if (client.player == null) return;
        if (!changeLook.getValue() && angle != null) {
            float prevYaw = client.player.getYRot();
            float prevPitch = client.player.getXRot();
            client.player.setYRot(angle.getYaw());
            client.player.setXRot(angle.getPitch());
            try {
                action.run();
            } finally {
                client.player.setYRot(prevYaw);
                client.player.setXRot(prevPitch);
            }
        } else {
            action.run();
        }
    }

    private void applyRotation(Minecraft client, Angle angle) {
        if (changeLook.getValue()) {
            client.player.setYRot(angle.getYaw());
            client.player.setXRot(angle.getPitch());
        } else {
            silentRotation = angle;
            rotating = true;
            AngleConnection.INSTANCE.setRotation(angle);
        }
    }

    private void endRotation(Minecraft client) {
        silentRotation = null;
        rotating = false;
        AngleConnection.INSTANCE.setRotation(null);
    }

    private BlockHitResult rayTraceFromEyes(Minecraft client, double maxRange) {
        if (client.player == null || client.level == null) return null;
        Vec3 eyePos = client.player.getEyePosition();
        Vec3 lookVec = client.player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(lookVec.scale(maxRange));
        return client.level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
    }

    private BlockPos calcBowTrajectory(Minecraft client, float yaw) {
        if (client.player == null || client.level == null) return null;
        double x = client.player.getX();
        double y = client.player.getY() + client.player.getEyeHeight(client.player.getPose()) - 0.1;
        double z = client.player.getZ();
        float pitch = client.player.getXRot();

        double motionX = -Mth.sin(yaw / 180.0F * (float) Math.PI) * Mth.cos(pitch / 180.0F * (float) Math.PI);
        double motionY = -Mth.sin(pitch / 180.0F * (float) Math.PI);
        double motionZ = Mth.cos(yaw / 180.0F * (float) Math.PI) * Mth.cos(pitch / 180.0F * (float) Math.PI);

        int ticksUsing = client.player.getTicksUsingItem();
        float power = ticksUsing > 0 ? ticksUsing / 20.0F : 1.0F;
        power = (power * power + power * 2.0F) / 3.0F;
        if (power > 1.0F) power = 1.0F;
        if (power < 0.1F) return null;

        float dist = Mth.sqrt((float) (motionX * motionX + motionY * motionY + motionZ * motionZ));
        motionX /= dist;
        motionY /= dist;
        motionZ /= dist;
        float pow = power * 3.0F;
        motionX *= pow;
        motionY *= pow;
        motionZ *= pow;
        if (!client.player.onGround()) {
            motionY += client.player.getDeltaMovement().y;
        }

        for (int i = 0; i < 300; i++) {
            Vec3 lastPos = new Vec3(x, y, z);
            x += motionX;
            y += motionY;
            z += motionZ;
            motionX *= 0.99;
            motionY *= 0.99;
            motionZ *= 0.99;
            motionY -= 0.05F;

            for (Entity ent : client.level.entitiesForRendering()) {
                if (!(ent instanceof AbstractArrow)
                        && !ent.equals(client.player)
                        && ent.getBoundingBox().intersects(new AABB(x - 0.3, y - 0.3, z - 0.3, x + 0.3, y + 0.3, z + 0.3))) {
                    return null;
                }
            }

            Vec3 pos = new Vec3(x, y, z);
            BlockHitResult bhr = client.level.clip(new ClipContext(lastPos, pos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (bhr != null && bhr.getType() == HitResult.Type.BLOCK) {
                return bhr.getBlockPos();
            }

            if (y <= -65.0) break;
        }
        return null;
    }



    private boolean isPathClearOfPlayers(Minecraft client, Vec3 from, Vec3 to, Entity exclude) {
        if (client.level == null) return true;
        for (Player player : client.level.players()) {
            if (player != client.player && player != exclude && player.getBoundingBox().clip(from, to).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasExistingMinecart(Minecraft client, BlockPos basePos) {
        if (client.level == null) return false;
        AABB box = new AABB(basePos.above());
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof MinecartTNT && entity.isAlive() && entity.getBoundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos getCartBasePos(Minecraft client, BlockPos targetPos) {
        if (client.level == null) return targetPos;
        BlockState targetState = client.level.getBlockState(targetPos);
        return !isRailBlock(targetState.getBlock()) && !targetState.canBeReplaced() ? targetPos : targetPos.below();
    }

    private int findRailInHotbar(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (isRailBlock(Block.byItem(stack.getItem()))) {
                return i;
            }
        }
        return -1;
    }

    private int findLoadedCrossbowInHotbar(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (isCrossbowCharged(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCrossbowCharged(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof CrossbowItem)) return false;
        ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
        return charged != null && !charged.isEmpty();
    }

    private boolean hasFlameEnchant(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return false;
        for (var entry : enchantments.entrySet()) {
            if (entry.getKey().is(Enchantments.FLAME)) {
                return entry.getIntValue() > 0;
            }
        }
        return false;
    }

    private boolean isRailBlock(Block block) {
        return block == Blocks.RAIL || block == Blocks.POWERED_RAIL || block == Blocks.DETECTOR_RAIL || block == Blocks.ACTIVATOR_RAIL;
    }

    private boolean isHoldingTotem(Player player) {
        return player.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    private void updateCartAuraTargetMemory() {
        if (cartAura.getValue() && AuraModule.target instanceof Player player && player != Minecraft.getInstance().player && player.isAlive()) {
            lastAuraTargetUuid = player.getUUID();
            lastAuraTargetId = player.getId();
            lastAuraTargetAt = System.currentTimeMillis();
        }
    }

    private boolean isRememberedAuraTarget(Player player) {
        if (player == null) return false;
        if (AuraModule.target instanceof Player auraPlayer && auraPlayer.equals(player)) return true;
        return lastAuraTargetUuid != null && System.currentTimeMillis() - lastAuraTargetAt <= 1000L
                && (player.getUUID().equals(lastAuraTargetUuid) || player.getId() == lastAuraTargetId);
    }

    private void handleRefill(Minecraft client) {
        if (reFill.is("None") || client.player == null) return;
        int targetSlot = refillSlot.getValue().intValue() - 1;
        if (targetSlot < 0 || targetSlot > 8) return;

        ItemStack current = client.player.getInventory().getItem(targetSlot);
        if (current.is(Items.TNT_MINECART)) return;

        if (refillTimer.finished(50)) {
            int cartSlot = InventoryUtils.findItemInInventory(Items.TNT_MINECART);
            if (cartSlot != -1) {
                InventoryUtils.click(cartSlot, targetSlot, ClickType.SWAP);
                refillTimer.reset();
            }
        }
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    private record FirePositionResult(BlockPos firePos, Vec3 aimPoint, boolean fireExists) {}
}
