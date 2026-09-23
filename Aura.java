package fun.soltise.client.modules.impl.combat;

import fun.soltise.Soltise;
import fun.soltise.api.events.EventLink;
import fun.soltise.api.events.implement.EventAttackEntity;
import fun.soltise.api.events.implement.EventGameUpdate;
import fun.soltise.api.events.implement.EventKeyboardInput;
import fun.soltise.api.events.implement.EventMoveInput;
import fun.soltise.api.events.implement.EventUpdate;
import fun.soltise.api.events.implement.EventUpdatePost;
import fun.soltise.api.storages.implement.FreeLookStorage;
import fun.soltise.api.storages.implement.NeuroAuraStorage;
import fun.soltise.api.storages.implement.RotationStorage;
import fun.soltise.api.storages.implement.helpertstorages.enumvar.ModuleClass;
import fun.soltise.api.utils.combat.IdealHitUtils;
import fun.soltise.api.utils.combat.PredictUtils;
import fun.soltise.api.utils.input.MovingUtil;
import fun.soltise.api.utils.math.MathUtils;
import fun.soltise.api.utils.math.TimerUtils;
import fun.soltise.api.utils.player.HotbarUtil;
import fun.soltise.api.utils.player.SlotSearchResult;
import fun.soltise.api.utils.rotate.MultipointUtils;
import fun.soltise.api.utils.rotate.Rotation;
import fun.soltise.api.utils.rotate.RotationUtils;
import fun.soltise.client.modules.Module;
import fun.soltise.client.modules.impl.combat.components.RotationsSystem;
import fun.soltise.client.modules.impl.combat.components.interpolation.BestPoint;
import fun.soltise.client.modules.impl.combat.components.rotations.FunTimeRotation;
import fun.soltise.client.modules.impl.combat.components.rotations.LegitRotation;
import fun.soltise.client.modules.impl.combat.components.rotations.PredictRots;
import fun.soltise.client.modules.impl.combat.components.rotations.SlothRotation;
import fun.soltise.client.modules.impl.combat.components.rotations.SpookyDuelRotation;
import fun.soltise.client.modules.impl.combat.components.rotations.SpookyTimeRotation;
import fun.soltise.client.modules.impl.combat.components.rotations.TestRotation;
import fun.soltise.client.modules.impl.combat.components.rotations.WellMineRotation;
import fun.soltise.client.modules.impl.combat.components.rotations.WhiteRiseRotation;
import fun.soltise.client.modules.impl.movement.Sprint;
import fun.soltise.client.modules.impl.player.AutoEat;
import fun.soltise.client.modules.settings.Setting;
import fun.soltise.client.modules.settings.implement.BooleanSetting;
import fun.soltise.client.modules.settings.implement.FloatSetting;
import fun.soltise.client.modules.settings.implement.ListSetting;
import fun.soltise.client.modules.settings.implement.ModeSetting;
import fun.soltise.mixin.ILivingEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Generated;
import net.minecraft.class_1268;
import net.minecraft.class_1294;
import net.minecraft.class_1296;
import net.minecraft.class_1297;
import net.minecraft.class_1309;
import net.minecraft.class_1420;
import net.minecraft.class_1431;
import net.minecraft.class_1439;
import net.minecraft.class_1531;
import net.minecraft.class_1588;
import net.minecraft.class_1657;
import net.minecraft.class_1675;
import net.minecraft.class_1713;
import net.minecraft.class_1743;
import net.minecraft.class_1792;
import net.minecraft.class_1794;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_1810;
import net.minecraft.class_1819;
import net.minecraft.class_1821;
import net.minecraft.class_1829;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2350;
import net.minecraft.class_238;
import net.minecraft.class_241;
import net.minecraft.class_243;
import net.minecraft.class_2561;
import net.minecraft.class_2815;
import net.minecraft.class_2846;
import net.minecraft.class_2868;
import net.minecraft.class_3532;
import net.minecraft.class_3959;
import net.minecraft.class_3965;
import net.minecraft.class_3966;
import net.minecraft.class_5134;
import net.minecraft.class_9362;
import net.minecraft.class_239.class_240;
import net.minecraft.class_2846.class_2847;
import net.minecraft.class_3959.class_242;
import net.minecraft.class_3959.class_3960;

public class Aura extends Module {
   public static Aura INSTANCE = new Aura();
   public final ModeSetting rotationType = new ModeSetting(
      "Ротация", "Smooth", "Smooth", "Snap", "Data", "Sloth", "NoRotate", "WellMine", "Test", "Predict", "LegitRotation", "SpookyDuel", "SpookyTime", "FunTime"
   );
   private final ListSetting targets = new ListSetting(
      "Таргеты",
      new BooleanSetting("Игроки", true),
      new BooleanSetting("Голые", true),
      new BooleanSetting("Невидимки", true),
      new BooleanSetting("Мирные", false),
      new BooleanSetting("Мобы", true)
   );
   private final FloatSetting range = new FloatSetting("Дистанция атаки", 3.0F, 0.0F, 6.0F, 0.05F);
   private final FloatSetting aimRange = new FloatSetting("Дистанция наводки", 3.0F, 0.0F, 6.0F, 0.05F);
   private final FloatSetting elytraAimRange = new FloatSetting("Дистанция на элитрах", 50.0F, 10.0F, 100.0F, 0.05F);
   public final BooleanSetting smartCrit = new BooleanSetting("Умные криты", false);
   private final BooleanSetting sprintReset = new BooleanSetting("Сброс спринта", true);
   private final BooleanSetting throughWalls = new BooleanSetting("Бить через стены", true);
   private final BooleanSetting raycast = new BooleanSetting("Проверка на наведение", false);
   private final BooleanSetting unpressShield = new BooleanSetting("Отжимать щит", false);
   private final BooleanSetting breakShield = new BooleanSetting("Ломать щит", true);
   private final BooleanSetting attackOnEating = new BooleanSetting("Не бить когда ешь", true);
   public static BooleanSetting clientLook = new BooleanSetting("Наводка от первого лица", false);
   private final ModeSetting moveFix = new ModeSetting("Коррекция", "Нет", "Нет", "Свободная", "Сфокусированная", "Полная");
   private final ModeSetting priority = new ModeSetting("Приоритет", "Дистанция", "Дистанция", "Здоровье", "Угол", "Никакой");
   private class_1309 target;
   private class_241 currentRotations = new class_241(0.0F, 0.0F);
   private class_241 targetRotations = new class_241(0.0F, 0.0F);
   private final NeuroAuraStorage dataSystem = new NeuroAuraStorage();
   private final TimerUtils attackTimer = new TimerUtils();
   private final BooleanSetting rwWallBypass = new BooleanSetting("Обход рв стен", false);
   private final BooleanSetting rwWallLookDown = new BooleanSetting("Смотреть вниз", false).visible(this.rwWallBypass::isState);
   private final WellMineRotation wellMineRotation = new WellMineRotation();
   private final TestRotation testRotation = new TestRotation();
   private final SlothRotation slothRotation = new SlothRotation();
   private final WhiteRiseRotation whiteRiseRotation = new WhiteRiseRotation(this);
   private final PredictRots predictRots = new PredictRots();
   private final LegitRotation legitRotation = new LegitRotation();
   private final SpookyDuelRotation spookyDuelRotation = new SpookyDuelRotation();
   private final SpookyTimeRotation spookyTimeRotation = new SpookyTimeRotation();
   private final FunTimeRotation funTimeRotation = new FunTimeRotation();
   private final TimerUtils backTimer = new TimerUtils();
   private TpsSync tpsSync;
   private long cps = 0L;
   private boolean needSprintReset = false;
   private boolean sprintResetDone = false;
   private int sprintResetTicks = 0;
   private int ticksToAttack = 0;
   private int snapAttackAge = -1;
   private boolean snapAttackQueued = false;
   private class_1309 snapAttackTarget = null;
   private class_1309 lastDataTarget = null;
   private float lastYaw = 0.0F;
   private float lastPitch = 0.0F;
   public static float adjYaw;
   public static float adjPitch;
   public static float otvodkaYaw;
   public static float otvodkaPitch;
   public boolean isRotated;

   public Aura() {
      super("AttackAura", "Автоматически наводиться и бьёт таргета", Module.ModuleCategory.COMBAT);
      this.addSettings(
         new Setting[]{
            this.rotationType,
            this.targets,
            this.range,
            this.aimRange,
            this.elytraAimRange,
            this.smartCrit,
            this.sprintReset,
            this.attackOnEating,
            this.throughWalls,
            this.rwWallBypass,
            this.rwWallLookDown,
            this.raycast,
            this.unpressShield,
            this.breakShield,
            clientLook,
            this.moveFix,
            this.priority
         }
      );
   }

   @EventLink
   public void onPlayerTick(EventUpdate e) {
      if (mc.field_1724 != null && mc.field_1687 != null) {
         if (this.tpsSync == null && Soltise.INSTANCE != null && Soltise.INSTANCE.moduleStorage != null) {
            this.tpsSync = ModuleClass.tpsSync;
         }

         this.lastYaw++;
         this.updateTarget();
         if (this.dataSystem.isRecording()) {
            class_1309 recordTarget = this.findTargetForRecording();
            this.dataSystem.recordTick(recordTarget, mc.field_1724.method_36454(), mc.field_1724.method_36455());
         }
      }
   }

   @EventLink
   public void onAttackEntity(EventAttackEntity event) {
      if (mc.field_1724 != null && mc.field_1687 != null) {
         if (event.getPlayer() == mc.field_1724) {
            if (event.getTarget() instanceof class_1309 living) {
               if (this.isValidTarget(living)) {
                  this.target = living;
               }
            }
         }
      }
   }

   @EventLink
   public void onMoveInput(EventMoveInput event) {
      if (this.needSprintReset) {
         event.setForward(0.0F);
         event.setStrafe(0.0F);
         this.needSprintReset = false;
         this.sprintResetDone = true;
         this.sprintResetTicks = 0;
      } else {
         this.applyMoveFix(event);
      }
   }

   private void applyMoveFix(EventMoveInput event) {
      if (mc.field_1724 != null && this.target != null && !this.rotationType.is("NoRotate") && this.moveFix.getIndex() != 0) {
         if (this.moveFix.getIndex() == 1) {
            MovingUtil.fixMovementFree(event);
         }
      }
   }

   @EventLink
   public void onKeyboardInput(EventKeyboardInput event) {
      if (mc.field_1724 != null && mc.field_1687 != null && this.target != null && !this.rotationType.is("NoRotate")) {
         float correctionYaw = this.getCorrectionYaw();
         if (this.moveFix.getIndex() == 2) {
            event.setYaw(correctionYaw, mc.field_1724.method_36454());
         } else if (this.moveFix.getIndex() == 3) {
            event.setYaw(correctionYaw, this.getTargetDirectionYaw());
         }
      }
   }

   private float getCorrectionYaw() {
      return RotationStorage.instance != null && RotationStorage.instance.targetRotation() != null
         ? RotationStorage.instance.targetRotation().getYaw()
         : mc.field_1724.method_36454();
   }

   private float getTargetDirectionYaw() {
      return RotationUtils.getRotations(this.target.method_5829().method_1005()).field_1343;
   }

   private void applyMovementCorrection(EventMoveInput event, float yaw, float directionYaw) {
      float forward = event.getForward();
      float strafe = event.getStrafe();
      if (forward != 0.0F || strafe != 0.0F) {
         double angle = class_3532.method_15338(Math.toDegrees(MovingUtil.direction(directionYaw, forward, strafe)));
         float closestForward = 0.0F;
         float closestStrafe = 0.0F;
         float closestDifference = Float.MAX_VALUE;

         for (float predictedForward = -1.0F; predictedForward <= 1.0F; predictedForward++) {
            for (float predictedStrafe = -1.0F; predictedStrafe <= 1.0F; predictedStrafe++) {
               if (predictedForward != 0.0F || predictedStrafe != 0.0F) {
                  double predictedAngle = class_3532.method_15338(Math.toDegrees(MovingUtil.direction(yaw, predictedForward, predictedStrafe)));
                  double difference = Math.abs(angle - predictedAngle);
                  if (difference < closestDifference) {
                     closestDifference = (float)difference;
                     closestForward = predictedForward;
                     closestStrafe = predictedStrafe;
                  }
               }
            }
         }

         event.setForward(closestForward);
         event.setStrafe(closestStrafe);
      }
   }

   @EventLink
   private void onGameUpdate(EventGameUpdate e) {
      if (mc.field_1724 != null && mc.field_1687 != null && this.target != null) {
         this.rotate();
      }
   }

   @EventLink
   public void onTick(EventUpdate e) {
      if (mc.field_1724 != null && mc.field_1687 != null) {
         if (this.ticksToAttack > 0) {
            this.ticksToAttack--;
         }

         if (this.sprintResetDone) {
            this.sprintResetTicks++;
         }

         boolean packetCrits = ModuleClass.packetCriticals.isEnable() && mc.field_1724.method_6059(class_1294.field_5906);
         if (!packetCrits) {
            this.processAttack();
         }

         if (this.dataSystem.isShowStats() && mc.field_1724.field_6012 % 40 == 0 && (this.dataSystem.isRecording() || this.dataSystem.isUsingNeuro())) {
            mc.field_1724.method_7353(class_2561.method_43470(this.dataSystem.getStatusString()), true);
         }
      }
   }

   @EventLink
   public void onPost(EventUpdatePost e) {
      if (mc.field_1724 != null && mc.field_1687 != null) {
         boolean packetCrits = ModuleClass.packetCriticals.isEnable() && mc.field_1724.method_6059(class_1294.field_5906);
         if (packetCrits && mc.field_1724.field_6017 > 0.0F && mc.field_1724.field_6017 < 1.0F) {
            this.processAttack();
         }
      }
   }

   private class_1309 findTargetForRecording() {
      class_1309 bestTarget = null;
      double bestDistance = 100.0;
      class_243 eyePos = mc.field_1724.method_33571();

      for (class_1297 entity : mc.field_1687.method_18112()) {
         if (entity instanceof class_1309 living
            && living != mc.field_1724
            && living.method_5805()
            && !(living.method_6032() <= 0.0F)
            && !(living instanceof class_1531)) {
            double distance = eyePos.method_1025(living.method_5829().method_1005());
            if (!(distance > bestDistance)) {
               bestDistance = distance;
               bestTarget = living;
            }
         }
      }

      return bestTarget;
   }

   private void processAttack() {
      this.updateTarget();
      if (this.target != null) {
         if ((this.shouldAttack() || this.shouldUseQueuedSnapAttack()) && this.cps <= System.currentTimeMillis()) {
            if (this.attackOnEating.isState() && (this.shouldBlockAttackWhileUsingItem() || AutoEat.shouldSuppressCombat())) {
               return;
            }

            if (this.sprintReset.isState()
               && mc.field_1724.method_5624()
               && !this.sprintResetDone
               && !this.shouldSkipSprintResetInWater()
               && !IdealHitUtils.isNoJumpDelayJumpCritWindow()) {
               this.needSprintReset = true;
               return;
            }

            if (this.sprintReset.isState() && this.sprintResetDone && this.sprintResetTicks < 1) {
               return;
            }

            if (this.isSnapRotationActive() && !this.prepareSnapAttack()) {
               return;
            }

            this.attack();
            this.resetSnapAttack();
            this.sprintResetDone = false;
            this.sprintResetTicks = 0;
         }
      } else {
         this.cps = System.currentTimeMillis();
         this.backTimer.reset();
         adjPitch = 0.0F;
         adjYaw = 0.0F;
         this.wellMineRotation.reset();
         this.testRotation.reset();
         this.slothRotation.reset();
         this.whiteRiseRotation.reset();
         this.spookyDuelRotation.reset();
         this.spookyTimeRotation.reset();
         this.funTimeRotation.reset();
         this.dataSystem.resetState();
         this.lastDataTarget = null;
         this.sprintResetDone = false;
         this.sprintResetTicks = 0;
         this.ticksToAttack = 0;
         this.resetSnapAttack();
      }
   }

   public void Rotate() {
      this.rotate();
   }

   private void rotate() {
      if (ModuleClass.elytraresolver != null && ModuleClass.elytraresolver.isEscaping()) {
         class_243 targetPos = ModuleClass.elytraresolver.getEscapePos();
         double diffX = targetPos.field_1352 - mc.field_1724.method_23317();
         double diffY = targetPos.field_1351 - (mc.field_1724.method_23318() + mc.field_1724.method_18381(mc.field_1724.method_18376()));
         double diffZ = targetPos.field_1350 - mc.field_1724.method_23321();
         double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
         float yaw = (float)(Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0);
         float pitch = (float)(-Math.toDegrees(Math.atan2(diffY, dist)));
         float gcd = ((Double)mc.field_1690.method_42495().method_41753()).floatValue() * 0.6F + 0.2F;
         gcd = gcd * gcd * gcd * 1.2F;
         yaw -= yaw % gcd;
         pitch -= pitch % gcd;
         RotationStorage.update(new Rotation(yaw, pitch), 180.0F, 180.0F, 25.0F, 20.0F, 1, 1, clientLook.isState());
      } else if (this.target != null) {
         if (this.rotationType.is("Data") && this.target != this.lastDataTarget) {
            this.dataSystem.resetState();
            this.lastDataTarget = this.target;
         }

         if (this.isSnapRotationActive()) {
            this.updateSnapRotation(this.target);
         } else {
            RotationsSystem system;
            if (this.rotationType.is("Smooth")) {
               system = new RotationsSystem() {
                  @Override
                  public void updateRotations(class_1309 target) {
                     if (!mc.field_1724.method_6128()) {
                        class_243 relativePos = target.method_19538()
                           .method_1031(0.0, target.method_17682() * 0.6F, 0.0)
                           .method_1020(mc.field_1724.method_33571());
                        float yaw = (float)class_3532.method_15338(Math.toDegrees(Math.atan2(relativePos.field_1350, relativePos.field_1352)) - 90.0);
                        float pitch = (float)(-Math.toDegrees(Math.atan2(relativePos.field_1351, Math.hypot(relativePos.field_1352, relativePos.field_1350))));
                        RotationStorage.update(new Rotation(yaw, pitch), 360.0F, 360.0F, 360.0F, 360.0F, 1, 1, Aura.clientLook.isState());
                     } else {
                        class_243 interpolatedRotation = class_243.method_1030(target.method_53829(), target.method_53831());
                        class_243 rotationVector = target.method_5720();
                        class_243 relativePos = target.method_19538()
                           .method_1031(0.0, target.method_17682() * 0.6F, 0.0)
                           .method_1020(mc.field_1724.method_33571());
                        class_243 blendedDirection = interpolatedRotation.method_1029().method_35590(rotationVector, interpolatedRotation.method_1033());
                        if (mc.field_1724.method_6128() && target.method_6128() && ModuleClass.elytraTarget.isEnable()) {
                           relativePos = relativePos.method_1019(
                              blendedDirection.method_1029().method_1021(ModuleClass.elytraTarget.forward.getValue().floatValue())
                           );
                        }

                        float yaw = (float)class_3532.method_15338(Math.toDegrees(Math.atan2(relativePos.field_1350, relativePos.field_1352)) - 90.0);
                        float pitch = (float)(-Math.toDegrees(Math.atan2(relativePos.field_1351, Math.hypot(relativePos.field_1352, relativePos.field_1350))));
                        RotationStorage.update(new Rotation(yaw, pitch), 360.0F, 360.0F, 360.0F, 360.0F, 1, 1, Aura.clientLook.isState());
                     }
                  }
               };
            } else if (this.rotationType.is("WellMine")) {
               system = this.wellMineRotation;
            } else if (this.rotationType.is("Test")) {
               system = this.testRotation;
            } else if (this.rotationType.is("Sloth")) {
               system = this.whiteRiseRotation;
            } else if (this.rotationType.is("Predict")) {
               system = this.predictRots;
            } else if (this.rotationType.is("LegitRotation")) {
               system = this.legitRotation;
            } else if (this.rotationType.is("SpookyDuel")) {
               system = this.spookyDuelRotation;
            } else if (this.rotationType.is("SpookyTime")) {
               system = this.spookyTimeRotation;
            } else if (this.rotationType.is("FunTime")) {
               system = this.funTimeRotation;
            } else if (this.rotationType.is("NoRotate")) {
               system = new RotationsSystem() {
                  @Override
                  public void updateRotations(class_1309 target) {
                     RotationStorage.update(
                        new Rotation(FreeLookStorage.getFreeYaw(), FreeLookStorage.getFreePitch()),
                        MathUtils.random(100.0F, 170.0F),
                        MathUtils.random(100.0F, 170.0F),
                        MathUtils.random(100.0F, 170.0F),
                        MathUtils.random(100.0F, 170.0F),
                        1,
                        6,
                        false
                     );
                  }
               };
            } else if (this.rotationType.is("Data")) {
               system = new RotationsSystem() {
                  @Override
                  public void updateRotations(class_1309 target) {
                     boolean focusRotation = Aura.this.shouldFocusDataRotation();
                     class_243 fallbackPoint = Aura.this.getDataRotationPoint(target);
                     class_241 fallbackRot = RotationUtils.getRotations(fallbackPoint);
                     float currentYaw = mc.field_1724.method_36454();
                     float currentPitch = mc.field_1724.method_36455();
                     float yawDelta = Math.abs(class_3532.method_15393(fallbackRot.field_1343 - currentYaw));
                     float pitchDelta = Math.abs(fallbackRot.field_1342 - currentPitch);
                     boolean hardAcquire = yawDelta > 70.0F || yawDelta > 42.0F && mc.field_1724.method_5858(target) < 9.0;
                     Rotation rotation = null;
                     if (this.shouldUseElytraPredict(target)) {
                        class_241 rot = RotationUtils.getRotations(this.getPredictedPoint(target, fallbackPoint));
                        rotation = new Rotation(rot.field_1343, rot.field_1342);
                     } else if (!hardAcquire) {
                        rotation = Aura.this.dataSystem.getNeuroRotation(target, currentYaw, currentPitch, focusRotation);
                     }

                     if (rotation == null || hardAcquire) {
                        if (hardAcquire) {
                           Aura.this.dataSystem.resetState();
                        }

                        rotation = new Rotation(fallbackRot.field_1343, fallbackRot.field_1342);
                     }

                     Aura.this.targetRotations = new class_241(rotation.getYaw(), rotation.getPitch());
                     Aura.this.currentRotations = new class_241(currentYaw, currentPitch);
                     float yawSpeed = hardAcquire ? Math.max(95.0F, Math.min(180.0F, yawDelta * 1.45F)) : (focusRotation ? 24.0F : 11.5F);
                     float pitchSpeed = hardAcquire ? Math.max(55.0F, Math.min(110.0F, Math.max(18.0F, pitchDelta * 1.35F))) : (focusRotation ? 18.0F : 9.0F);
                     float yawReturnSpeed = hardAcquire ? yawSpeed : (focusRotation ? 18.0F : 9.0F);
                     float pitchReturnSpeed = hardAcquire ? pitchSpeed : (focusRotation ? 14.0F : 7.0F);
                     RotationStorage.update(rotation, yawSpeed, pitchSpeed, yawReturnSpeed, pitchReturnSpeed, 1, 1, Aura.clientLook.isState());
                  }
               };
            } else {
               final class_241 targetRot = RotationUtils.getRotations(this.getPredictedRotationPoint(this.target, this.target.method_30951(1.0F)));
               system = new RotationsSystem() {
                  @Override
                  public void updateRotations(class_1309 target) {
                     Aura.this.currentRotations = new class_241(mc.field_1724.method_36454(), mc.field_1724.method_36455());
                     RotationStorage.update(
                        new Rotation(targetRot.field_1343, targetRot.field_1342), 360.0F, 360.0F, 360.0F, 360.0F, 1, 1, Aura.clientLook.isState()
                     );
                  }
               };
            }

            system.updateRotations(this.target);
         }
      }
   }

   private void updateSnapRotation(class_1309 target) {
      class_243 point = MultipointUtils.getClosestPoint(target);
      if (point == null) {
         point = target.method_5829().method_1005();
      }

      class_243 predicted = this.getPredictedRotationPoint(target, point);
      class_241 targetRot = RotationUtils.getRotations(predicted);
      this.targetRotations = targetRot;
      this.currentRotations = new class_241(mc.field_1724.method_36454(), mc.field_1724.method_36455());
      boolean isAttackSnapTick = mc.field_1724.field_6012 <= this.snapAttackAge;
      float finalYaw = targetRot.field_1343;
      float finalPitch = targetRot.field_1342;
      if (!isAttackSnapTick) {
         if (this.shouldKeepRwWallPitchDown()) {
            finalPitch = 90.0F;
         } else {
            finalYaw = FreeLookStorage.getFreeYaw();
            finalPitch = FreeLookStorage.getFreePitch();
         }
      }

      RotationStorage.update(new Rotation(finalYaw, finalPitch), 360.0F, 360.0F, 360.0F, 360.0F, 0, 6, clientLook.isState());
   }

   private boolean isSnapRotationActive() {
      return this.rotationType.is("Snap") || this.isUsingRwWallSnap();
   }

   private boolean prepareSnapAttack() {
      if (!this.snapAttackQueued) {
         this.snapAttackQueued = true;
         this.snapAttackAge = mc.field_1724.field_6012 + 1;
         this.snapAttackTarget = this.target;
         return false;
      } else if (mc.field_1724.field_6012 > this.snapAttackAge) {
         this.resetSnapAttack();
         return false;
      } else {
         return this.isSnapAimReadyForAttack();
      }
   }

   private boolean shouldUseQueuedSnapAttack() {
      if (!this.snapAttackQueued || mc.field_1724 == null || this.target == null || this.target != this.snapAttackTarget) {
         return false;
      } else if (mc.field_1724.field_6012 > this.snapAttackAge + 1) {
         this.resetSnapAttack();
         return false;
      } else {
         return mc.field_1724.field_6012 >= this.snapAttackAge;
      }
   }

   private boolean isSnapAimReadyForAttack() {
      if (this.target != null && mc.field_1724 != null) {
         float yawDiff = Math.abs(class_3532.method_15393(this.targetRotations.field_1343 - mc.field_1724.method_36454()));
         float pitchDiff = Math.abs(this.targetRotations.field_1342 - mc.field_1724.method_36455());
         boolean onTarget = this.isUsingRwWallSnap() || mc.field_1724.method_6128() && this.target.method_6128();
         if (!onTarget) {
            class_3966 result = this.getAttackRaycastResult();
            onTarget = result != null && result.method_17782() == this.target;
         }

         return yawDiff <= 3.0F && pitchDiff <= 2.5F && onTarget;
      } else {
         return false;
      }
   }

   private boolean isUsingRwWallSnap() {
      return this.rwWallBypass.isState() && this.target != null && this.isTargetBehindWall(this.target);
   }

   private boolean shouldKeepRwWallPitchDown() {
      return this.isUsingRwWallSnap() && this.rwWallLookDown.isState();
   }

   private boolean shouldSkipSprintResetInWater() {
      return mc.field_1724 != null
         && (mc.field_1724.method_5799() || mc.field_1724.method_5869())
         && Sprint.INSTANCE != null
         && Sprint.INSTANCE.shouldKeepSprintInWater();
   }

   private class_3966 getAttackRaycastResult() {
      class_243 eyePos = mc.field_1724.method_5836(1.0F);
      class_243 lookVec = mc.field_1724.method_5828(1.0F);
      float reach = this.range.getValue().floatValue() * 2.0F;
      class_243 reachVec = eyePos.method_1019(lookVec.method_1021(reach));
      return class_1675.method_18075(
         mc.field_1724, eyePos, reachVec, mc.field_1724.method_5829().method_1014(reach), ex -> ex != mc.field_1724 && ex.method_5805(), reach * reach
      );
   }

   private boolean isTargetBehindWall(class_1309 entity) {
      return entity != null && mc.field_1724 != null && mc.field_1687 != null ? !mc.field_1724.method_6057(entity) || this.hasNarrowRwWallGap(entity) : false;
   }

   private boolean hasNarrowRwWallGap(class_1309 entity) {
      if (entity != null && mc.field_1724 != null && mc.field_1687 != null && this.rwWallBypass.isState()) {
         class_243 eyePos = mc.field_1724.method_33571();
         class_238 box = entity.method_5829();
         double centerX = box.method_1005().field_1352;
         double centerZ = box.method_1005().field_1350;
         class_243[] points = new class_243[]{
            box.method_1005(),
            this.getStableBodyPoint(entity),
            new class_243(centerX, box.field_1325 - 0.08, centerZ),
            new class_243(centerX, box.field_1322 + 0.12, centerZ),
            new class_243(box.field_1323 + 0.04, box.field_1322 + box.method_17940() * 0.55, centerZ),
            new class_243(box.field_1320 - 0.04, box.field_1322 + box.method_17940() * 0.55, centerZ),
            new class_243(centerX, box.field_1322 + box.method_17940() * 0.55, box.field_1321 + 0.04),
            new class_243(centerX, box.field_1322 + box.method_17940() * 0.55, box.field_1324 - 0.04)
         };
         int blocked = 0;
         int clear = 0;

         for (class_243 point : points) {
            class_3965 hit = mc.field_1687.method_17742(new class_3959(eyePos, point, class_3960.field_17558, class_242.field_1348, mc.field_1724));
            if (hit != null && hit.method_17783() == class_240.field_1332) {
               blocked++;
            } else {
               clear++;
            }
         }

         return clear > 0 && blocked >= clear;
      } else {
         return false;
      }
   }

   private class_243 getPredictedRotationPoint(class_1309 target, class_243 point) {
      return mc.field_1724 != null
            && target != null
            && mc.field_1724.method_6128()
            && target.method_6128()
            && ModuleClass.elytraTarget != null
            && ModuleClass.elytraTarget.isEnable()
         ? PredictUtils.bypasselytrahacking(target)
         : point;
   }

   private class_243 getDataRotationPoint(class_1309 target) {
      if (this.isInsideOrNearHitbox(target)) {
         return this.getPredictedRotationPoint(target, this.getStableBodyPoint(target));
      } else {
         class_243 point = BestPoint.getNearestPoint(target);
         if (point == null) {
            point = MultipointUtils.getClosestPoint(target);
         }

         if (point == null) {
            point = this.getStableBodyPoint(target);
         }

         return this.getPredictedRotationPoint(target, point);
      }
   }

   private boolean isInsideOrNearHitbox(class_1309 target) {
      if (mc.field_1724 != null && target != null) {
         class_243 eyePos = mc.field_1724.method_33571();
         class_238 box = target.method_5829();
         if (box.method_1014(0.12).method_1006(eyePos)) {
            return true;
         } else {
            class_243 stablePoint = this.getStableBodyPoint(target);
            float rangeSquared = this.range.getValue().floatValue() * this.range.getValue().floatValue();
            return eyePos.method_1025(stablePoint) <= rangeSquared;
         }
      } else {
         return false;
      }
   }

   private class_243 getStableBodyPoint(class_1309 target) {
      class_238 box = target.method_5829();
      return new class_243(box.method_1005().field_1352, box.field_1322 + box.method_17940() * 0.72, box.method_1005().field_1350);
   }

   private class_1309 findTarget() {
      List<class_1309> entities = new ArrayList<>();

      for (class_1297 entity : mc.field_1687.method_18112()) {
         if (entity instanceof class_1309 living && this.isValidTarget(living)) {
            entities.add(living);
         }
      }

      if (!entities.isEmpty() && this.isEnable()) {
         String var5 = this.priority.getCurrent();
         switch (var5) {
            case "Дистанция":
               entities.sort(Comparator.comparingDouble(entityx -> entityx.method_5829().method_1005().method_1025(mc.field_1724.method_33571())));
               break;
            case "Здоровье":
               entities.sort(Comparator.comparingDouble(class_1309::method_6032));
               break;
            case "Угол":
               entities.sort(Comparator.comparingDouble(entityx -> {
                  class_241 vec = RotationUtils.getRotations(entityx.method_5829().method_1005());
                  double dy = Math.abs(class_3532.method_15393(vec.field_1343 - mc.field_1724.method_36454()));
                  double dp = Math.abs(class_3532.method_15393(vec.field_1342 - mc.field_1724.method_36455()));
                  return dy + dp;
               }));
            case "Никакой":
         }

         return entities.isEmpty() ? null : entities.get(0);
      } else {
         return null;
      }
   }

   private void updateTarget() {
      if (!this.isEnable()) {
         this.target = null;
      } else if (this.target == null || !this.isValidTarget(this.target)) {
         this.target = this.findTarget();
      }
   }

   private boolean shouldFocusDataRotation() {
      float cooldown = mc.field_1724.method_7261(1.5F);
      float focusThreshold = Math.max(0.82F, IdealHitUtils.getAICooldown() - 0.08F);
      boolean readyByCooldown = cooldown >= focusThreshold;
      boolean fallingForCrit = !mc.field_1724.method_24828() && mc.field_1724.method_18798().field_1351 < 0.0 && mc.field_1724.field_6017 > 0.0F;
      return readyByCooldown || fallingForCrit;
   }

   private void attack() {
      class_1268 blockedShieldHand = null;
      if (this.unpressShield.isState()) {
         blockedShieldHand = this.getBlockingShieldHand();
         if (blockedShieldHand != null) {
            mc.field_1761.method_2897(mc.field_1724);
         }
      }

      this.tryBreakRwWallBlockPacket();
      boolean attacked = false;
      if (this.target instanceof class_1657 player && player.method_6039() && this.breakShield.isState()) {
         attacked = this.shieldBreak(player);
      }

      if (!attacked) {
         mc.field_1761.method_2918(mc.field_1724, this.target);
         if (ModuleClass.elytraresolver != null) {
            ModuleClass.elytraresolver.onAuraAttack();
         }
      }

      mc.field_1724.method_6104(class_1268.field_5808);
      if (blockedShieldHand != null) {
         this.restoreShieldBlocking(blockedShieldHand);
      }

      if (this.rotationType.is("WhiteRise")) {
         this.slothRotation.onAttack();
      }

      if (this.rotationType.is("Sloth")) {
         this.whiteRiseRotation.onAttack();
      }

      long cooldown = 467L;
      if (this.tpsSync != null && this.tpsSync.isEnable()) {
         cooldown = (long)((float)this.tpsSync.getAdjustedCooldown(cooldown) * 1.1F);
      }

      this.cps = System.currentTimeMillis() + cooldown;
      this.ticksToAttack = 10;
      this.attackTimer.reset();
   }

   private void tryBreakRwWallBlockPacket() {
      if (this.rwWallBypass.isState() && this.target != null && mc.field_1724 != null && mc.field_1687 != null) {
         if (!mc.field_1724.method_6057(this.target)) {
            if (mc.field_1724.field_3944 != null) {
               class_243 start = mc.field_1724.method_33571();
               class_243 end = this.target.method_5829().method_1005();
               class_3965 hit = mc.field_1687.method_17742(new class_3959(start, end, class_3960.field_17558, class_242.field_1348, mc.field_1724));
               if (hit != null && hit.method_17783() == class_240.field_1332) {
                  class_2338 blockPos = hit.method_17777();
                  if (!mc.field_1687.method_8320(blockPos).method_26215()) {
                     if (!(mc.field_1687.method_8320(blockPos).method_26214(mc.field_1687, blockPos) < 0.0F)) {
                        class_2350 direction = hit.method_17780() == null ? class_2350.field_11036 : hit.method_17780();
                        mc.field_1724.field_3944.method_52787(new class_2846(class_2847.field_12968, blockPos, direction));
                        mc.field_1724.field_3944.method_52787(new class_2846(class_2847.field_12973, blockPos, direction));
                     }
                  }
               }
            }
         }
      }
   }

   private boolean shieldBreak(class_1657 entity) {
      SlotSearchResult axeSlot = HotbarUtil.getAxe();
      if (!axeSlot.found()) {
         return false;
      } else {
         int previousSlot = mc.field_1724.method_31548().field_7545;
         if (axeSlot.slot() == previousSlot) {
            mc.field_1761.method_2918(mc.field_1724, entity);
            if (ModuleClass.elytraresolver != null) {
               ModuleClass.elytraresolver.onAuraAttack();
            }

            return true;
         } else if (axeSlot.isInHotBar()) {
            return this.attackWithSilentHotbarSlot(entity, axeSlot.slot(), previousSlot);
         } else if (mc.field_1724.field_7512.field_7763 != 0) {
            return false;
         } else {
            int swapHotbarSlot = this.findSilentSwapHotbarSlot(previousSlot);
            if (swapHotbarSlot == -1) {
               return false;
            } else {
               this.swapInventoryIntoHotbar(axeSlot.slot(), swapHotbarSlot);
               boolean attacked = false;

               try {
                  attacked = this.attackWithSilentHotbarSlot(entity, swapHotbarSlot, previousSlot);
               } finally {
                  this.swapInventoryIntoHotbar(axeSlot.slot(), swapHotbarSlot);
               }

               return attacked;
            }
         }
      }
   }

   private boolean attackWithSilentHotbarSlot(class_1657 entity, int attackSlot, int previousSlot) {
      if (mc.field_1724 != null && mc.field_1724.field_3944 != null && mc.field_1761 != null) {
         mc.field_1724.field_3944.method_52787(new class_2868(attackSlot));

         boolean var4;
         try {
            mc.field_1761.method_2918(mc.field_1724, entity);
            if (ModuleClass.elytraresolver != null) {
               ModuleClass.elytraresolver.onAuraAttack();
            }

            var4 = true;
         } finally {
            mc.field_1724.field_3944.method_52787(new class_2868(previousSlot));
         }

         return var4;
      } else {
         return false;
      }
   }

   private void swapInventoryIntoHotbar(int inventorySlot, int hotbarSlot) {
      if (mc.field_1724 != null && mc.field_1761 != null && mc.field_1724.field_3944 != null) {
         int syncId = mc.field_1724.field_7512.field_7763;
         mc.field_1761.method_2906(syncId, inventorySlot, hotbarSlot, class_1713.field_7791, mc.field_1724);
         mc.field_1724.field_3944.method_52787(new class_2815(syncId));
      }
   }

   private int findSilentSwapHotbarSlot(int previousSlot) {
      for (int slot = 8; slot >= 0; slot--) {
         if (slot != previousSlot) {
            return slot;
         }
      }

      return previousSlot >= 0 && previousSlot < 9 ? previousSlot : -1;
   }

   private class_1268 getBlockingShieldHand() {
      if (mc.field_1724 != null && mc.field_1724.method_6039()) {
         class_1268 activeHand = mc.field_1724.method_6058();
         if (activeHand == null) {
            return null;
         } else {
            return this.isShieldStack(mc.field_1724.method_5998(activeHand)) ? activeHand : null;
         }
      } else {
         return null;
      }
   }

   private boolean shouldBlockAttackWhileUsingItem() {
      return mc.field_1724 != null && mc.field_1724.method_6115() ? !this.unpressShield.isState() || this.getBlockingShieldHand() == null : false;
   }

   private void restoreShieldBlocking(class_1268 hand) {
      if (mc.field_1724 != null && mc.field_1761 != null && hand != null) {
         if (this.isShieldStack(mc.field_1724.method_5998(hand)) && !mc.field_1724.method_6115()) {
            mc.field_1761.method_2919(mc.field_1724, hand);
         }
      }
   }

   private boolean isShieldStack(class_1799 stack) {
      return !stack.method_7960() && stack.method_7909() instanceof class_1819;
   }

   private boolean isWeapon() {
      class_1792 item = mc.field_1724.method_6047().method_7909();
      return item != class_1802.field_8162
         && (
            item instanceof class_1829
               || item instanceof class_1810
               || item instanceof class_1743
               || item instanceof class_1794
               || item instanceof class_1821
               || item instanceof class_9362
               || item == class_1802.field_49814
         );
   }

   private boolean isValidTarget(class_1309 entity) {
      if (entity != null && entity != mc.field_1724) {
         if (!entity.method_5805() || entity.method_6032() <= 0.0F) {
            return false;
         } else if (entity instanceof class_1531) {
            return false;
         } else if (entity instanceof class_1439 || entity instanceof class_1420) {
            return false;
         } else if (AntiBot.checkBot(entity)) {
            return false;
         } else {
            if (entity instanceof class_1657 player) {
               if (!this.targets.is("Игроки")) {
                  return false;
               }

               if (this.isNaked(player) && !this.targets.is("Голые")) {
                  return false;
               }

               if (player.method_6059(class_1294.field_5905) && !this.targets.is("Невидимки")) {
                  return false;
               }

               if (Soltise.INSTANCE.friendStorage.isFriend(entity.method_5477().getString())) {
                  return false;
               }
            } else if (!(entity instanceof class_1296) && !(entity instanceof class_1431)) {
               if (!(entity instanceof class_1588)) {
                  return false;
               }

               if (!this.targets.is("Мобы")) {
                  return false;
               }
            } else if (!this.targets.is("Мирные")) {
               return false;
            }

            class_243 nearestPoint = BestPoint.getNearestPoint(entity);
            if (nearestPoint == null) {
               nearestPoint = MultipointUtils.getClosestPoint(entity);
            }

            return mc.field_1724.method_33571().method_1022(nearestPoint) > this.getMaxAimRange()
               ? false
               : this.throughWalls.isState() || this.rwWallBypass.isState() || mc.field_1724.method_6057(entity);
         }
      } else {
         return false;
      }
   }

   private boolean isNaked(class_1657 player) {
      for (class_1799 armorStack : player.method_5661()) {
         if (!armorStack.method_7960()) {
            return false;
         }
      }

      return true;
   }

   private boolean shouldAttack() {
      if (mc.field_1724.method_7261(1.5F) < IdealHitUtils.getAICooldown()) {
         return false;
      } else {
         class_3966 result = this.getAttackRaycastResult();
         if (!this.raycast.isState()
            || this.isUsingRwWallSnap()
            || result != null && result.method_17782() != null && result.method_17782() == this.target
            || mc.field_1724.method_6128() && this.target.method_6128()) {
            if (this.rotationType.is("Data") && !this.isUsingRwWallSnap() && !this.isDataAimReady(result)) {
               return false;
            } else {
               if (mc.field_1724.method_6128() && this.target.method_6128()) {
                  class_243 targetPos = this.target.method_19538().method_1031(0.0, this.target.method_17682() / 2.0, 0.0);
                  int predict = 0;
                  if (ModuleClass.elytraTarget != null && ModuleClass.elytraTarget.isEnable()) {
                     predict = ModuleClass.elytraTarget.forward.getValue().intValue();
                  }

                  class_243 predictedPos = PredictUtils.predict(this.target, targetPos, predict);
                  float maxRange = this.elytraAimRange.getValue().floatValue();
                  if (mc.field_1724.method_33571().method_1022(predictedPos) > maxRange) {
                     return false;
                  }
               } else {
                  class_243 checkPoint = BestPoint.getNearestPoint(this.target);
                  if (checkPoint == null) {
                     checkPoint = MultipointUtils.getClosestPoint(this.target);
                  }

                  if (checkPoint == null) {
                     checkPoint = this.target.method_5829().method_1005();
                  }

                  double actualDistance = mc.field_1724.method_33571().method_1022(checkPoint);
                  float maxRange = this.range.getValue().floatValue();
                  if (actualDistance > maxRange) {
                     return false;
                  }
               }

               return IdealHitUtils.canCritical(this.target);
            }
         } else {
            return false;
         }
      }
   }

   public int getWhiteRiseTicksToAttack() {
      return this.ticksToAttack;
   }

   private boolean isDataAimReady(class_3966 result) {
      float yawDiff = Math.abs(class_3532.method_15393(this.targetRotations.field_1343 - mc.field_1724.method_36454()));
      float pitchDiff = Math.abs(this.targetRotations.field_1342 - mc.field_1724.method_36455());
      boolean closeToAim = yawDiff <= 1.15F && pitchDiff <= 0.9F;
      boolean onTarget = result != null && result.method_17782() == this.target;
      return closeToAim && onTarget;
   }

   public boolean isAboveWater() {
      class_2338 pos = class_2338.method_49638(mc.field_1724.method_19538().method_1031(0.0, -0.4, 0.0));
      return !mc.field_1724.method_5869() && mc.field_1687.method_8320(pos).method_27852(class_2246.field_10382);
   }

   public float getAttackCooldown() {
      return class_3532.method_15363(((ILivingEntity)mc.field_1724).getLastAttackedTicks() / this.getAttackCooldownProgressPerTick(), 0.0F, 1.0F);
   }

   public float getAttackCooldownProgressPerTick() {
      return (float)(1.0 / mc.field_1724.method_45325(class_5134.field_23723) * 20.0);
   }

   private float getMaxAimRange() {
      return mc.field_1724.method_6128()
         ? this.elytraAimRange.getValue().floatValue()
         : this.range.getValue().floatValue() + this.aimRange.getValue().floatValue();
   }

   @Override
   public void onDisable() {
      super.onDisable();
      if (this.target != null) {
         this.backTimer.reset();
      }

      this.target = null;
      this.wellMineRotation.reset();
      this.testRotation.reset();
      this.slothRotation.reset();
      this.whiteRiseRotation.reset();
      this.spookyDuelRotation.reset();
      this.spookyTimeRotation.reset();
      this.funTimeRotation.reset();
      this.dataSystem.resetState();
      this.lastDataTarget = null;
      this.needSprintReset = false;
      this.sprintResetDone = false;
      this.sprintResetTicks = 0;
      this.ticksToAttack = 0;
      this.resetSnapAttack();
   }

   @Override
   public void onEnable() {
      super.onEnable();
      this.wellMineRotation.reset();
      this.testRotation.reset();
      this.slothRotation.reset();
      this.whiteRiseRotation.reset();
      this.spookyDuelRotation.reset();
      this.spookyTimeRotation.reset();
      this.funTimeRotation.reset();
      this.dataSystem.resetState();
      this.lastDataTarget = null;
      this.needSprintReset = false;
      this.sprintResetDone = false;
      this.sprintResetTicks = 0;
      this.ticksToAttack = 0;
      this.resetSnapAttack();
      if (mc.field_1724 != null) {
         this.currentRotations = new class_241(mc.field_1724.method_36454(), mc.field_1724.method_36455());
      }
   }

   private void resetSnapAttack() {
      this.snapAttackAge = -1;
      this.snapAttackQueued = false;
      this.snapAttackTarget = null;
   }

   @Generated
   public class_1309 getTarget() {
      return this.target;
   }

   @Generated
   public class_241 getCurrentRotations() {
      return this.currentRotations;
   }

   @Generated
   public class_241 getTargetRotations() {
      return this.targetRotations;
   }

   @Generated
   public NeuroAuraStorage getDataSystem() {
      return this.dataSystem;
   }

   @Generated
   public TimerUtils getAttackTimer() {
      return this.attackTimer;
   }
}
