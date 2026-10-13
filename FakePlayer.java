package releon.ru.api.module.impl.misc;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.drag.impl.Notifications;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.AttackEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.manager.Manager;

import java.util.UUID;

public class FakePlayer extends Module {

    public static RemotePlayer fakePlayer;

    private final BooleanSetting copyInventory = register(new BooleanSetting("Copy Inventory", "Copy player inventory to fake player.", true));
    private final BooleanSetting autoTotem = register(new BooleanSetting("Auto Totem", "Automatically give fake player a new totem.", true));
    private final StringSetting name = register(new StringSetting("Name", "Name of the fake player.", "Fake Player", 32));

    private long lastTotemPopAt = 0L;
    private int deathTime = 0;
    private int totemPops = 0;

    public FakePlayer() {
        super("FakePlayer", "Fake player for testing PvP modules.", ModuleCategory.MISC);
    }

    @Override
    protected void onEnable() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            setEnabled(false);
            return;
        }

        deathTime = 0;
        totemPops = 0;
        lastTotemPopAt = 0L;

        GameProfile profile = new GameProfile(UUID.fromString("66123666-6666-6666-6666-666666666600"), name.getValue());
        fakePlayer = new RemotePlayer(client.level, profile);
        fakePlayer.setId(-66666);
        copyPlayerState(client);

        if (copyInventory.getValue()) {
            fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, client.player.getMainHandItem().copy());
            fakePlayer.setItemInHand(InteractionHand.OFF_HAND, client.player.getOffhandItem().copy());
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.isArmor()) {
                    fakePlayer.setItemSlot(slot, client.player.getItemBySlot(slot).copy());
                }
            }
        }

        client.level.addEntity(fakePlayer);
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 99999, 2));
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 99999, 4));
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 99999, 1));
        Notifications.push("FakePlayer", "Đã spawn FakePlayer thành công!");
    }

    @Override
    protected void onDisable() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            client.level.removeEntity(-66666, Entity.RemovalReason.KILLED);
            if (fakePlayer != null) {
                client.level.removeEntity(fakePlayer.getId(), Entity.RemovalReason.KILLED);
            }
        }
        if (fakePlayer != null) {
            fakePlayer.discard();
            fakePlayer = null;
        }
        deathTime = 0;
        lastTotemPopAt = 0L;
        Notifications.push("FakePlayer", "Đã xóa FakePlayer!");
    }

    private void copyPlayerState(Minecraft client) {
        if (client.player == null || fakePlayer == null) return;
        fakePlayer.copyPosition(client.player);
        fakePlayer.yRotO = client.player.yRotO;
        fakePlayer.xRotO = client.player.xRotO;
        fakePlayer.yHeadRot = client.player.yHeadRot;
        fakePlayer.yHeadRotO = client.player.yHeadRotO;
        fakePlayer.yBodyRot = client.player.yBodyRot;
        fakePlayer.yBodyRotO = client.player.yBodyRotO;
        fakePlayer.setPose(client.player.getPose());
        fakePlayer.setShiftKeyDown(client.player.isShiftKeyDown());
        fakePlayer.setSprinting(client.player.isSprinting());
        fakePlayer.setSwimming(client.player.isSwimming());
        fakePlayer.setOnGround(client.player.onGround());
        fakePlayer.setDeltaMovement(client.player.getDeltaMovement());
        fakePlayer.setHealth(20.0F);
        fakePlayer.setAbsorptionAmount(16.0F);
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        if (fakePlayer != null) {
            if (autoTotem.getValue()
                    && !fakePlayer.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                    && System.currentTimeMillis() - lastTotemPopAt >= 50L) {
                fakePlayer.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            }

            if (fakePlayer.isDeadOrDying()) {
                deathTime++;
                if (deathTime > 10) {
                    setEnabled(false);
                }
            }
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (fakePlayer == null || client.player == null || client.level == null) return;

        if (event.isReceive() && event.getPacket() instanceof ClientboundExplodePacket explosion) {
            if (fakePlayer.hurtTime == 0) {
                Vec3 center = explosion.center();
                double dist = fakePlayer.position().distanceTo(center);
                if (dist <= 10.0) {
                    float damage = (float) Math.max(1.0, (10.0 - dist) * 2.5);
                    fakePlayer.hurtTime = 10;
                    fakePlayer.hurtDuration = 10;
                    fakePlayer.animateHurt(0.0F);

                    float currentHp = fakePlayer.getHealth() + fakePlayer.getAbsorptionAmount();
                    float newHp = currentHp - damage;
                    if (newHp <= 0.0F) {
                        if (!tryUseTotem()) {
                            fakePlayer.setHealth(0.0F);
                            fakePlayer.setPose(net.minecraft.world.entity.Pose.DYING);
                            setEnabled(false);
                            return;
                        }
                    } else {
                        if (newHp > 20.0F) {
                            fakePlayer.setHealth(20.0F);
                            fakePlayer.setAbsorptionAmount(newHp - 20.0F);
                        } else {
                            fakePlayer.setHealth(newHp);
                            fakePlayer.setAbsorptionAmount(0.0F);
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    private void onAttack(AttackEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (fakePlayer != null && event.getTarget() == fakePlayer && fakePlayer.hurtTime == 0 && client.player != null && client.level != null) {
            client.level.playSound(client.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                    SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 1.0F);

            if (client.player.fallDistance > 0.0F) {
                client.level.playSound(client.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                        SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.0F);
            }

            fakePlayer.hurtTime = 10;
            fakePlayer.hurtDuration = 10;
            fakePlayer.animateHurt(0.0F);

            float damage = calculateHitDamage(client.player, fakePlayer);
            float currentHp = fakePlayer.getHealth() + fakePlayer.getAbsorptionAmount();
            float newHp = currentHp - damage;

            if (newHp <= 0.0F) {
                if (!tryUseTotem()) {
                    fakePlayer.setHealth(0.0F);
                    fakePlayer.setPose(net.minecraft.world.entity.Pose.DYING);
                    setEnabled(false);
                    return;
                }
            } else {
                if (newHp > 20.0F) {
                    fakePlayer.setHealth(20.0F);
                    fakePlayer.setAbsorptionAmount(newHp - 20.0F);
                } else {
                    fakePlayer.setHealth(newHp);
                    fakePlayer.setAbsorptionAmount(0.0F);
                }
            }
        }
    }

    private boolean tryUseTotem() {
        if (fakePlayer == null) return false;
        ItemStack mainHand = fakePlayer.getMainHandItem();
        ItemStack offHand = fakePlayer.getOffhandItem();
        boolean hasTotem = false;
        if (mainHand.is(Items.TOTEM_OF_UNDYING)) {
            mainHand.shrink(1);
            hasTotem = true;
        } else if (offHand.is(Items.TOTEM_OF_UNDYING)) {
            offHand.shrink(1);
            hasTotem = true;
        }
        if (hasTotem) {
            handleTotemPop();
            return true;
        }
        return false;
    }

    private void handleTotemPop() {
        if (fakePlayer == null) return;
        Minecraft client = Minecraft.getInstance();
        lastTotemPopAt = System.currentTimeMillis();
        fakePlayer.setHealth(10.0F);
        fakePlayer.removeAllEffects();
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));

        if (client.level != null) {
            if (client.particleEngine != null) {
                client.particleEngine.createTrackingEmitter(fakePlayer, ParticleTypes.TOTEM_OF_UNDYING, 30);
            }
            client.level.playLocalSound(fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                    SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F, false);
        }

        fakePlayer.handleEntityEvent((byte) 35);

        totemPops++;
        Notifications.push("FakePlayer", "FakePlayer đã nổ totem! (Lần: " + totemPops + ")");
    }

    private float calculateHitDamage(Player player, RemotePlayer target) {
        ItemStack weapon = player.getMainHandItem();
        float baseDamage = 1.0F;

        if (weapon.is(Items.NETHERITE_SWORD)) baseDamage = 8.0F;
        else if (weapon.is(Items.DIAMOND_SWORD)) baseDamage = 7.0F;
        else if (weapon.is(Items.IRON_SWORD)) baseDamage = 6.0F;
        else if (weapon.is(Items.STONE_SWORD)) baseDamage = 5.0F;
        else if (weapon.is(Items.WOODEN_SWORD) || weapon.is(Items.GOLDEN_SWORD)) baseDamage = 4.0F;
        else if (weapon.is(Items.NETHERITE_AXE)) baseDamage = 10.0F;
        else if (weapon.is(Items.DIAMOND_AXE) || weapon.is(Items.IRON_AXE) || weapon.is(Items.STONE_AXE)) baseDamage = 9.0F;
        else if (weapon.is(Items.WOODEN_AXE) || weapon.is(Items.GOLDEN_AXE)) baseDamage = 7.0F;
        else if (weapon.is(Items.MACE)) {
            baseDamage = 6.0F;
            if (player.fallDistance > 1.5F) {
                baseDamage += player.fallDistance * 3.0F;
            }
        }

        float scale = player.getAttackStrengthScale(0.5F);
        float damage = baseDamage * (0.2F + scale * scale * 0.8F);

        if (player.fallDistance > 0.0F && !player.onGround() && !player.onClimbable() && !player.isInWater()) {
            damage *= 1.5F;
        }

        int armor = target.getArmorValue();
        if (armor > 0) {
            damage = damage * (1.0F - Math.min(20.0F, Math.max(armor / 5.0F, armor - damage / 2.0F)) / 25.0F);
        }

        return Math.max(1.0F, damage);
    }
}
