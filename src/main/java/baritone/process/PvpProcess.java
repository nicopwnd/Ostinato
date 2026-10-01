package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Melee PvP. Far away we path to the target; inside {@link #DRIVE} we pause pathing and steer
 * ourselves: crit chaining (jump, drop sprint near the apex, hit falling), sprint hits with a
 * W-tap, hit select (no swings into hurt immunity), jump resets on knockback, random strafing,
 * axe against a raised shield, shield against drawn bows and incoming arrows, a bow at range,
 * golden apples and a totem in the offhand when low.
 */
public final class PvpProcess extends BaritoneProcessHelper {

    private static final double REACH = 3.0, DRIVE = 7, BOW_MIN = 10, CHASE = 48;
    private static final Item[] SWORDS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD};
    private static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE};

    private Predicate<LivingEntity> filter;
    private String label;
    private LivingEntity target;
    private final Random rng = new Random(7);
    private int strafeDir = 1, strafeLeft, wtap, eatTicks, groundedJumps, blockTicks;
    private boolean crystalFight;
    private int backingOff;
    private int targetSwingTick, lastAxeTick = -1000;
    private boolean critArmed;
    private float lastHealth = -1;

    public int attacks, crits, sprintHits, axeHits, blocks, gapples;
    public float damageTaken;

    public PvpProcess(Baritone baritone) {
        super(baritone);
    }

    public void attack(Predicate<LivingEntity> filter, String label) {
        this.filter = filter;
        this.label = label;
        target = null;
        lastHealth = -1;
        attacks = crits = sprintHits = axeHits = blocks = gapples = 0;
        damageTaken = 0;
    }

    public void attackPlayer(String name) {
        attack(e -> e instanceof Player && e.getName().getString().equalsIgnoreCase(name), name);
    }

    public void attackPlayers() {
        attack(e -> e instanceof Player, "players");
    }

    public void attackHostiles() {
        attack(e -> e instanceof Enemy, "hostiles");
    }

    public LivingEntity getTarget() {
        return target;
    }

    public String stats() {
        return String.format("attacks=%d crits=%d sprintHits=%d axeHits=%d blocks=%d gapples=%d dmgTaken=%.1f",
                attacks, crits, sprintHits, axeHits, blocks, gapples, damageTaken);
    }

    @Override
    public boolean isActive() {
        return filter != null && ctx.player() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Player me = ctx.player();
        float hp = me.getHealth() + me.getAbsorptionAmount();
        if (lastHealth >= 0 && hp < lastHealth) damageTaken += lastHealth - hp;
        lastHealth = hp;

        if (target == null || !target.isAlive() || target.isRemoved() || me.distanceTo(target) > CHASE) target = pick(me);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (target == null) {
            use(false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        keepTotem(me);

        boolean targetEating = target.isUsingItem() && target.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD);
        boolean safe = eyeToBox(me, target) > 4.5 || targetEating;
        if (eatTicks > 0 || (me.getHealth() <= 5 || me.getHealth() <= 11 && safe || crystalFight && me.getAbsorptionAmount() == 0 && me.getHealth() <= (slotOf(me, Items.RESPAWN_ANCHOR) >= 0 ? 19 : 16)) && !me.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)
                && (slotOf(me, Items.GOLDEN_APPLE) >= 0 || slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0)) {
            if (eat(me)) return pause();
        }

        double dist = eyeToBox(me, target);
        boolean los = me.hasLineOfSight(target);

        if (pearlCool > 0) pearlCool--;
        if (fireCool > 0) fireCool--;
        if (spearCool > 0) spearCool--;
        if (hp <= 6 && !canHeal(me) && target.getHealth() + target.getAbsorptionAmount() > 6 && !targetEating) {
            return flee(me, dist);
        }

        if (shouldBlock(me, dist)) {
            if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
            look(target.getEyePosition());
            use(true);
            if (blockTicks++ == 0) blocks++;
            return pause();
        }
        if (blockTicks > 0) {
            use(false);
            blockTicks = 0;
        }

        // crystals and anchors reach further than a sword, and blowing them is also how we clear a wall of them
        if (crystal(me)) {
            if (dist <= DRIVE) steer(me, dist);
            return pause();
        }
        PathingCommand sp = special(me, dist, los);
        if (sp != null) return sp;
        if (!los && dist <= 3) { // right there but walled off (a crawl gap under our feet, a hole): dig through
            BlockHitResult wall = ctx.world().clip(new net.minecraft.world.level.ClipContext(me.getEyePosition(), target.getEyePosition(),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me));
            if (wall.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                look(wall.getLocation());
                ctx.minecraft().gameMode.continueDestroyBlock(wall.getBlockPos(), wall.getDirection());
                me.swing(InteractionHand.MAIN_HAND);
                return pause();
            }
        }
        if (dist > DRIVE || !los) {
            if (los && dist > BOW_MIN && slotOf(me, Items.BOW) >= 0 && slotOf(me, Items.ARROW) >= 0) return bow(me);
            use(false);
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if (me.isUsingItem()) use(false);

        boolean inReach = exactReach(me, target) <= REACH - 0.05;
        // a shield being raised blocks before isBlocking() shows it; only swap in reach, since any swap drains the charge
        boolean shieldUp = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
        // attribute swap: a spear's longer reach on a target that just slipped out of sword range
        int spear = spearSlot(me);
        if (spear >= 0 && !inReach && dist <= 4.2 && los && me.getAttackStrengthScale(0.5f) >= 0.95f) {
            select(me, spear);
            look(aimPoint(me, target));
            hit(me);
            return pause();
        }
        // a disabled shield stays "raised" for its 5s cooldown; don't keep throwing uncharged axe swings at it
        boolean axeTime = shieldUp && inReach && me.tickCount - lastAxeTick > 60 && best(me, AXES) >= 0;
        select(me, axeTime ? best(me, AXES) : weapon(me));
        look(aimPoint(me, target));

        float cd = me.getAttackStrengthScale(0.5f);
        if (target.swinging && target.swingTime == 0) targetSwingTick = me.tickCount;
        boolean targetReady = me.tickCount - targetSwingTick >= 10; // its sword is charged: whoever swings first wins the exchange
        boolean immune = target.hurtTime > 1;
        boolean falling = !me.onGround() && me.getDeltaMovement().y < -0.05;
        boolean canJump = me.onGround() && !me.isInWater() && !me.isInLava() && !me.onClimbable();

        steer(me, dist);
        if (me.hurtTime == me.hurtDuration - 1 && canJump) me.jumpFromGround(); // jump reset

        if (axeTime && inReach) { // an axe disables a raised shield whatever the charge
            hit(me);
            axeHits++;
            lastAxeTick = me.tickCount;
            return pause();
        }
        if (!me.onGround() && !falling && targetReady && inReach && cd >= 0.95f && !immune) {
            hit(me); // don't hang in the air waiting for a crit while it swings first
            critArmed = false;
            return pause();
        }
        if (critArmed && falling && inReach && cd >= 0.9f && !immune) {
            hit(me);
            crits++;
            critArmed = false;
            return pause();
        }
        if (!me.onGround() && me.getDeltaMovement().y < 0.08 && dist <= REACH + 0.6 && cd >= 0.75f) {
            me.setSprinting(false); // a sprinting hit is never a crit
            critArmed = true;
        }
        if (!me.onGround() && !critArmed && inReach && cd >= 0.95f && !immune) {
            hit(me); // knocked airborne without a crit set up: don't waste the cooldown
            return pause();
        }
        if (me.onGround()) critArmed = false;
        else groundedJumps = 0;

        if (canJump && dist <= REACH + 0.8 && cd >= 0.55f && !immune && groundedJumps < 4) {
            me.jumpFromGround();
            groundedJumps++;
            return pause();
        }
        if (me.onGround() && inReach && cd >= 0.95f && !immune && (!canJump || groundedJumps >= 4)) {
            boolean sprint = me.isSprinting();
            hit(me);
            if (sprint) {
                sprintHits++;
                wtap = 2;
            }
            groundedJumps = 0;
        }
        return pause();
    }

    private static final boolean HUMANIZE = !"false".equals(System.getProperty("ostinato.humanize"));
    private static final boolean KINEMATIC = !"false".equals(System.getProperty("ostinato.kinematic"));
    private baritone.pathing.kinematic.KinematicController kin;
    private double wanderY, wanderP, wanderVy, wanderVp;
    private int pearlStage, pearlTicks;
    private Vec3 pearlFrom, pearlLast;
    private int fireCool, fireStage, fireTicks, fleeTicks;
    private BlockPos firePos;
    private int pearlCool, spearCool, webCool, potCool, windCool, macePhase, maceTicks, maceCool, chargeTicks;

    /** Mace, crossbow and trident play; null when the kit has none of them or they don't apply right now. */
    private PathingCommand special(Player me, double dist, boolean los) {
        if (maceCool > 0) maceCool--;
        int mace = slotOf(me, Items.MACE), wind = slotOf(me, Items.WIND_CHARGE);
        if (windCool > 0) windCool--;
        boolean overhead = !target.onGround() && target.getY() > me.getY() + 3;
        // arrows, tridents, fireballs, potions: a wind charge on the projectile's path deflects it
        if (wind >= 0 && macePhase == 0 && windCool == 0) {
            for (net.minecraft.world.entity.projectile.Projectile pr : ctx.world().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    me.getBoundingBox().inflate(14), e -> e.getOwner() != me && !e.onGround() && e.getDeltaMovement().lengthSqr() > 0.09)) {
                Vec3 v = pr.getDeltaMovement(), rel = me.getEyePosition().subtract(pr.position());
                double d = rel.length();
                if (d < 3.5 || d > 13 || v.dot(rel) <= 0 || v.normalize().dot(rel.normalize()) < 0.85) continue;
                Vec3 at = pr.position().add(v.scale(d / (v.length() + 1.5)));
                Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
                select(me, wind);
                me.setYRot(r.getYaw());
                me.setXRot(r.getPitch());
                ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
                me.swing(InteractionHand.MAIN_HAND);
                windCool = 8;
                return pause();
            }
        }
        // an airborne opponent diving at us: a wind charge on its predicted path knocks it off the smash
        if (wind >= 0 && macePhase == 0 && windCool == 0 && !target.onGround() && dist < 12 && dist > 2
                && (target.getDeltaMovement().y < -0.1 || target.getY() > me.getY() + 2)) {
            Vec3 at = target.getBoundingBox().getCenter().add(target.getDeltaMovement().scale(dist / 1.5));
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
            select(me, wind);
            me.setYRot(r.getYaw());
            me.setXRot(r.getPitch());
            ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            windCool = 12;
            return pause();
        }
        // a diver still coming (no charge, or too close to counter): block the smash with the shield
        if (macePhase == 0 && !target.onGround() && target.getDeltaMovement().y < -0.3 && dist < 11 && target.getY() > me.getY() + 1
                && (me.getOffhandItem().getItem() == Items.SHIELD || slotOf(me, Items.SHIELD) >= 0)) {
            if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
            look(target.getEyePosition());
            use(true);
            return pause();
        }
        // pearl strike: lob a pearl so it peaks above the target, pop it mid-air with a wind charge to teleport there, then drop the mace
        if (mace >= 0 && wind >= 0 && macePhase == 0 && (pearlStage > 0 || pearlCool == 0 && maceCool == 0 && me.onGround() && los && dist > 7 && dist < 22
                && slotOf(me, Items.ENDER_PEARL) >= 0 && target.onGround() && !overhead)) {
            if (pearlStage == 0) {
                float bestPitch = 0;
                double bestErr = 1e9;
                Vec3 eye = me.getEyePosition();
                Vec3 flat = new Vec3(target.getX() - me.getX(), 0, target.getZ() - me.getZ()).normalize();
                for (float pitch = -80; pitch <= -25; pitch += 1.5f) {
                    double pr = Math.toRadians(pitch);
                    Vec3 v = new Vec3(flat.x * Math.cos(pr), -Math.sin(pr), flat.z * Math.cos(pr)).scale(1.5);
                    Vec3 p = eye;
                    for (int t = 0; t < 80; t++) {
                        p = p.add(v);
                        v = v.scale(0.99).add(0, -0.03, 0);
                        double h = Math.hypot(p.x - target.getX(), p.z - target.getZ());
                        if (p.y > target.getY() + 5 && p.y < target.getY() + 14 && h < bestErr) {
                            bestErr = h;
                            bestPitch = pitch;
                        }
                        if (p.y < eye.y - 2 && v.y < 0) break;
                    }
                }
                if (bestErr > 2.0) {
                    pearlCool = 80;
                    return null;
                }
                select(me, slotOf(me, Items.ENDER_PEARL));
                Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), eye.add(flat.scale(10)), ctx.playerRotations());
                me.setYRot(r.getYaw());
                me.setXRot(bestPitch);
                ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
                me.swing(InteractionHand.MAIN_HAND);
                pearlStage = 1;
                pearlTicks = 0;
                pearlFrom = me.position();
                return pause();
            }
            pearlTicks++;
            if (pearlStage == 1) {
                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl = null;
                for (net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl e : ctx.world().getEntitiesOfClass(net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl.class, me.getBoundingBox().inflate(60), x -> x.getOwner() == me)) pearl = e;
                if (pearl != null) pearlLast = pearl.position();
                if (pearl == null || pearlTicks > 90) {
                   
                    pearlStage = 0;
                    pearlCool = pearl == null && pearlTicks <= 3 ? 0 : 120;
                    return null;
                }
                select(me, wind);
                Vec3 pv = pearl.getDeltaMovement();
                Vec3 pp = pearl.position(), vv = pv;
                int n = 1;
                boolean ok = false;
                for (; n < 80; n++) { // first tick the pearl is over the target, high enough
                    pp = pp.add(vv);
                    vv = vv.scale(0.99).add(0, -0.03, 0);
                    Vec3 tpn = target.position().add(target.getDeltaMovement().scale(n));
                    if (Math.hypot(pp.x - tpn.x, pp.z - tpn.z) < 1.3 && pp.y > tpn.y + 4) {
                        ok = true;
                        break;
                    }
                    if (pp.y < me.getY() - 3) break;
                }
                look(pearl.position());
                if (ok && pp.distanceTo(me.getEyePosition()) / 1.5 >= n - 1) { // the charge needs about as long to arrive as the pearl does
                    Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), pp, ctx.playerRotations());
                    me.setYRot(r.getYaw());
                    me.setXRot(r.getPitch());
                    ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
                    me.swing(InteractionHand.MAIN_HAND);
                    pearlStage = 2;
                    pearlTicks = 0;
                }
                return pause();
            }
            // stage 2: wait for the teleport, then fall on it
            if (me.position().distanceTo(pearlFrom) > 5) {
                pearlStage = 0;
                pearlCool = 200;
                macePhase = 2;
                maceTicks = 0;
            } else if (pearlTicks > 40) {
                pearlStage = 0;
                pearlCool = 200;
            }
            return pause();
        }
        int rocket = slotOf(me, Items.FIREWORK_ROCKET);
        if (mace >= 0 && rocket >= 0 && me.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() == Items.ELYTRA
                && (macePhase >= 5 || macePhase == 0 && me.onGround() && maceCool == 0 && !overhead && target.getDeltaMovement().y > -0.3 && dist > 3 && dist < 40 && los)) {
            // elytra mace: take off, rocket up above the target, dive and smash
            maceTicks++;
            if (macePhase == 0) {
                me.jumpFromGround();
                macePhase = 5;
                maceTicks = 0;
                return pause();
            }
            Vec3 tp = aimPoint(me, target);
            if (macePhase == 5) { // rising: open the wings once falling
                if (me.getDeltaMovement().y < 0 && !me.onGround()) {
                    ctx.minecraft().getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(me,
                            net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    me.startFallFlying();
                    macePhase = 6;
                    maceTicks = 0;
                } else if (maceTicks > 30) {
                    macePhase = 0;
                    maceCool = 40;
                }
                return pause();
            }
            // macePhase 6: gliding
            boolean climbing = me.getY() < target.getY() + 14 && maceTicks < 70;
            Vec3 aim = climbing ? new Vec3(tp.x, me.getEyeY() + 30, tp.z).add(tp.subtract(me.position()).multiply(0.0, 0, 0)) : tp;
            if (climbing) {
                Vec3 flat = new Vec3(tp.x - me.getX(), 0, tp.z - me.getZ());
                flat = flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
                aim = me.getEyePosition().add(flat.scale(12)).add(0, 14, 0); // ~50 degrees up
            }
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations());
            me.setYRot(r.getYaw());
            me.setXRot(r.getPitch());
            double speed = me.getDeltaMovement().length();
            if (climbing && speed < 1.2 && maceTicks % 12 == 3) {
                select(me, rocket);
                ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
            } else if (!climbing) {
                select(me, mace);
                if (exactReach(me, target) <= REACH - 0.05) {
                    hit(me);
                    macePhase = 0;
                    maceCool = 10;
                }
            } else {
                select(me, mace);
            }
            if (me.onGround() || !me.isFallFlying() && maceTicks > 6 || maceTicks > 200) {
                macePhase = 0;
                maceCool = 40;
            }
            return pause();
        }
        if (mace >= 0) {
            boolean canJump = me.onGround() && !me.isInWater();
            if (macePhase == 0 && canJump && maceCool == 0 && !overhead && dist > 2.5 && dist < 24 && los && wind >= 0) {
                select(me, wind);
                me.jumpFromGround();
                macePhase = 1;
                maceTicks = 0;
            }
            if (macePhase == 1) { // rising: throw the charge under our feet near the apex
                maceTicks++;
                select(me, wind);
                if (maceTicks >= 2) {
                    me.setXRot(90f); // the look behavior is smoothed; the charge must leave straight down this tick
                    ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
                    me.swing(InteractionHand.MAIN_HAND);
                    macePhase = 2;
                    maceTicks = 0;
                } else {
                    look(target.getEyePosition());
                    key(Input.MOVE_FORWARD);
                    me.setSprinting(true);
                }
                return pause();
            }
            if (macePhase == 2) { // flying: steer to the target, smash while falling
                maceTicks++;
                select(me, mace);
                look(aimPoint(me, target));
                key(Input.MOVE_FORWARD);
                me.setSprinting(true);
                int sp = spearSlot(me), axe = best(me, AXES);
                boolean shielded = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
                if (shielded && axe >= 0 && me.fallDistance > 1.5 && me.tickCount - lastAxeTick > 20 && exactReach(me, target) <= REACH - 0.05) {
                    select(me, axe); // breach slam: the axe drops the shield, the mace lands on the next tick
                    hit(me);
                    axeHits++;
                    lastAxeTick = me.tickCount;
                    return pause();
                }
                if (sp >= 0 && spearCool == 0 && !me.onGround() && dist > 4 && dist < 20 && me.getFoodData().getFoodLevel() >= 7) {
                    select(me, sp); // spear lunge: horizontal momentum in the air, at the cost of hunger
                    net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(0));
                    spearCool = 30;
                    return pause();
                }
                if (me.onGround() && maceTicks > 3 || maceTicks > 120) {
                    macePhase = 0;
                    maceCool = 25;
                } else if (me.fallDistance > 1.5 && exactReach(me, target) <= REACH - 0.05
                        && (me.fallDistance >= 3 || !ctx.world().noCollision(me, me.getBoundingBox().move(0, -1.3, 0)))) {
                    hit(me);
                    macePhase = 0;
                    maceCool = 14;
                }
                return pause();
            }
            if (wind < 0 || maceCool > 0 || dist <= 3) {
                int alt = weapon(me);
                if (alt < 0) alt = mace;
                select(me, alt);
            }
            return null;
        }
        if (webCool > 0) webCool--;
        // a web in the target's feet slows it into our hits
        if (webCool == 0 && los && dist > 2.4 && dist < 5 && target.onGround() && slotOf(me, Items.COBWEB) >= 0
                && ctx.world().getBlockState(target.blockPosition()).isAir()) {
            webCool = 60;
            place(me, Items.COBWEB, target.blockPosition().below());
            return pause();
        }
        if (potCool > 0) potCool--;
        if (potCool == 0) {
            int heal = potion(me, MobEffects.INSTANT_HEALTH), harm = potion(me, MobEffects.INSTANT_DAMAGE);
            if (heal >= 0 && me.getHealth() <= 9) {
                select(me, heal);
                me.setXRot(90f);
                ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
                potCool = 12;
                return pause();
            }
            if (harm >= 0 && los && dist > 3 && dist < 12) {
                Vec3 at = target.position().add(target.getDeltaMovement().scale(dist / 0.5)).add(0, 0.2, 0);
                Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at.add(0, dist * 0.12, 0), ctx.playerRotations());
                select(me, harm);
                me.setYRot(r.getYaw());
                me.setXRot(r.getPitch());
                ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
                me.swing(InteractionHand.MAIN_HAND);
                potCool = 25;
                return pause();
            }
        }
        // soul sand + flint and steel + any bow: shoot through the fire to set the target alight
        if (slotOf(me, Items.SOUL_SAND) >= 0 && slotOf(me, Items.FLINT_AND_STEEL) >= 0 && slotOf(me, Items.BOW) >= 0
                && slotOf(me, Items.ARROW) >= 0 && los && dist > (fireStage > 0 ? 6 : 11) && dist < 22 && (fireStage > 0 || (fireCool == 0 && target.onGround() && me.onGround() && !target.isOnFire()))) {
            if (fireStage == 0) {
                Vec3 dir = new Vec3(target.getX() - me.getX(), 0, target.getZ() - me.getZ()).normalize();
                BlockPos g = BlockPos.containing(me.getX() + dir.x * 2, me.getY() - 1, me.getZ() + dir.z * 2);
                if (!ctx.world().getBlockState(g).isSolid() || !ctx.world().getBlockState(g.above()).isAir() || !ctx.world().getBlockState(g.above(2)).isAir()) {
                    fireStage = -1;
                } else {
                    firePos = g;
                    fireStage = 1;
                    fireTicks = 0;
                }
            }
            if (fireStage > 0) {
                if (++fireTicks > 80) {
                    fireStage = 0;
                    fireCool = 400;
                } else if (fireStage == 1) {
                    if (place(me, Items.SOUL_SAND, firePos)) fireStage = 2;
                    return pause();
                } else if (fireStage == 2) {
                    if (place(me, Items.FLINT_AND_STEEL, firePos.above())) fireStage = 3;
                    return pause();
                } else {
                    if (!ctx.world().getBlockState(firePos.above(2)).isAir() && ctx.world().getBlockState(firePos.above(2)).getBlock() != net.minecraft.world.level.block.Blocks.FIRE
                            && ctx.world().getBlockState(firePos.above()).getBlock() != net.minecraft.world.level.block.Blocks.SOUL_FIRE
                            && ctx.world().getBlockState(firePos.above()).getBlock() != net.minecraft.world.level.block.Blocks.FIRE) fireStage = 0;
                    if (target.isOnFire() || fireTicks > 70) { fireStage = 0; fireCool = 400; }
                    return bow(me);
                }
            }
        } else if (fireStage < 0 && (!target.onGround() || dist < 5)) {
            fireStage = 0;
        } else if (fireStage > 0) {
            fireStage = 0;
        }
        int xb = slotOf(me, Items.CROSSBOW);
        if (xb >= 0 && los && dist > 5 && slotOf(me, Items.ARROW) >= 0) {
            select(me, xb);
            look(target.getEyePosition().add(target.getDeltaMovement().scale(Math.min(dist, 30) / 3.0)));
            if (net.minecraft.world.item.CrossbowItem.isCharged(me.getMainHandItem())) {
                use(false);
                ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
            } else {
                use(true);
            }
            return pause();
        }
        int tr = slotOf(me, Items.TRIDENT);
        if (tr >= 0 && los && dist > 5 && dist < 40) {
            select(me, tr);
            look(target.getEyePosition().add(0, dist * 0.04, 0));
            if (++chargeTicks > 14) {
                use(false);
                chargeTicks = -8;
            } else if (chargeTicks > 0) {
                use(true);
            }
            return pause();
        }
        return null;
    }

    /** Hotbar slot of a splash potion carrying the effect, or -1. */
    private int potion(Player me, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() != Items.SPLASH_POTION) continue;
            net.minecraft.world.item.alchemy.PotionContents pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (pc == null) continue;
            for (net.minecraft.world.effect.MobEffectInstance ei : pc.getAllEffects()) if (ei.getEffect().equals(effect)) return i;
        }
        return -1;
    }

    /** Hotbar slot of a spear (anything with a piercing attack), or -1. */
    private int spearSlot(Player me) {
        for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON) != null) return i;
        return -1;
    }

    private int blockSlot(Player me) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() != net.minecraft.world.level.block.Blocks.SOUL_SAND
                    && bi.getBlock().defaultBlockState().isSolid() && !(bi.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                    && bi.getBlock() != net.minecraft.world.level.block.Blocks.TNT) return i;
        }
        return -1;
    }

    private boolean canHeal(Player me) {
        return me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING || slotOf(me, Items.GOLDEN_APPLE) >= 0
                || slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0 || potion(me, MobEffects.INSTANT_HEALTH) >= 0;
    }

    /** Low on health with nothing to heal: pearl away from the target, else run. */
    private PathingCommand flee(Player me, double dist) {
        use(false);
        if (dist < 10 && pearlCool == 0 && slotOf(me, Items.ENDER_PEARL) >= 0) {
            Vec3 away = new Vec3(me.getX() - target.getX(), 0, me.getZ() - target.getZ());
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            Vec3 at = me.getEyePosition().add(away.scale(24)).add(0, 7, 0);
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
            select(me, slotOf(me, Items.ENDER_PEARL));
            me.setYRot(r.getYaw());
            me.setXRot(r.getPitch());
            ctx.minecraft().gameMode.useItem(me, InteractionHand.MAIN_HAND);
            me.swing(InteractionHand.MAIN_HAND);
            pearlCool = 160;
            return pause();
        }
        if (dist > 16) {
            fleeTicks = 0;
            return pause(); // clear of it: stand and regenerate
        }
        int blk = blockSlot(me);
        if (++fleeTicks > 40 && dist < 6 && blk >= 0 && me.getY() - target.getY() < 5) {
            // can't shake it: tower up out of melee
            select(me, blk);
            me.setXRot(90f);
            if (me.onGround()) me.jumpFromGround();
            else if (me.getDeltaMovement().y < 0.1 && ctx.world().getBlockState(me.blockPosition().below()).isAir()) click(me, me.blockPosition().below().below());
            return pause();
        }
        return new PathingCommand(new baritone.api.pathing.goals.GoalRunAway(18, target.blockPosition()), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void steer(Player me, double dist) {
        if (--strafeLeft <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeLeft = 10 + rng.nextInt(20);
        }
        if (wtap > 0) {
            wtap--;
            me.setSprinting(false);
        } else if (dist > 2.4) {
            key(Input.MOVE_FORWARD);
            if (!critArmed && me.getFoodData().getFoodLevel() > 6) me.setSprinting(true);
        } else if (dist < 1.2) {
            key(Input.MOVE_BACK);
        }
        if (dist > 3.5) {
            // it's backing off to heal: run it down in a straight line, sprint-jumping for speed
            if (me.onGround() && me.isSprinting() && !me.isInWater() && (!KINEMATIC || (kin != null ? kin : (kin = new baritone.pathing.kinematic.KinematicController(ctx))).jumpHelps(target.getX(), target.getZ()))) me.jumpFromGround();
            return;
        }
        key(strafeDir > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT);
    }

    private boolean shouldBlock(Player me, double dist) {
        if (me.getOffhandItem().getItem() != Items.SHIELD && slotOf(me, Items.SHIELD) < 0) return false;
        ItemStack using = target.getUseItem();
        if (target.isUsingItem() && (using.getItem() == Items.BOW || using.getItem() == Items.CROSSBOW) && dist > 4) return true;
        AABB around = me.getBoundingBox().inflate(6);
        for (AbstractArrow a : ctx.world().getEntitiesOfClass(AbstractArrow.class, around, x -> true)) {
            Vec3 v = a.getDeltaMovement();
            if (v.lengthSqr() < 0.25) continue;
            Vec3 to = me.position().add(0, 1, 0).subtract(a.position());
            if (to.normalize().dot(v.normalize()) > 0.9) return true;
        }
        return false;
    }

    private PathingCommand bow(Player me) {
        select(me, slotOf(me, Items.BOW));
        if (me.getMainHandItem().getItem() != Items.BOW) return pause();
        // lead: arrow ~3 b/t at full draw, gravity 0.05
        double d = me.distanceTo(target), t = d / 3.0;
        Vec3 at = target.getEyePosition().add(target.getDeltaMovement().scale(t)).add(0, 0.5 * 0.05 * t * t, 0);
        look(at);
        if (me.isUsingItem() && me.getTicksUsingItem() >= 21) {
            use(false);
            attacks++;
        } else {
            use(true);
        }
        return pause();
    }

    private boolean eat(Player me) {
        Item apple = me.getHealth() <= 6 && slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
        if (slotOf(me, apple) < 0) apple = Items.ENCHANTED_GOLDEN_APPLE;
        if (slotOf(me, apple) < 0) {
            eatTicks = 0;
            return false;
        }
        select(me, slotOf(me, apple));
        if (me.getMainHandItem().getItem() != apple) return true;
        if (eatTicks++ == 0) gapples++;
        key(Input.MOVE_BACK); // back off while chewing
        me.setSprinting(false);
        use(true);
        look(target.getEyePosition());
        if (eatTicks > 36) {
            use(false);
            eatTicks = 0;
        }
        return true;
    }

    private void keepTotem(Player me) {
        if (me.getHealth() > 8 && !crystalFight || me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;
        toOffhand(me, Items.TOTEM_OF_UNDYING);
    }

    /** Swap an inventory item into the offhand (button 40 = offhand swap). */
    private void toOffhand(Player me, Item item) {
        int slot = -1;
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).getItem() == item) { slot = i; break; }
        if (slot < 0) return;
        int menuSlot = slot < 9 ? 36 + slot : slot;
        ctx.playerController().windowClick(me.inventoryMenu.containerId, menuSlot, 40, ClickType.SWAP, me);
    }

    /** Hotbar slot of the item, pulling it into the hotbar (slot 8) if it's only in the main inventory. */
    private int slotOf(Player me, Item item) {
        for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == item) return i;
        for (int i = 9; i < 36; i++) {
            if (me.getInventory().getItem(i).getItem() == item) {
                ctx.playerController().windowClick(me.inventoryMenu.containerId, i, 8, ClickType.SWAP, me);
                return 8;
            }
        }
        return -1;
    }

    private int best(Player me, Item[] tiers) {
        for (Item it : tiers) {
            for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == it) return i;
        }
        return -1;
    }

    /** Crit play wants damage per swing: a sword, else an axe. */
    private int weapon(Player me) {
        int s = best(me, SWORDS);
        return s >= 0 ? s : best(me, AXES);
    }

    private void select(Player me, int slot) {
        if (slot >= 0) me.getInventory().setSelectedSlot(slot);
    }

    private void hit(Player me) {
        ctx.minecraft().gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND);
        attacks++;
    }

    private void key(Input in) {
        baritone.getInputOverrideHandler().setInputForceState(in, true);
    }

    private void use(boolean down) {
        ctx.minecraft().options.keyUse.setDown(down);
    }

    private void look(Vec3 at) {
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
        if (HUMANIZE) {
            // a hand on a mouse never tracks perfectly: a slow wander around the aim point, bigger when the target moves fast
            double energy = 0.5 + Math.min(1.0, target == null ? 0 : target.getDeltaMovement().horizontalDistance() * 3);
            wanderVy = (wanderVy + rng.nextGaussian() * 0.12 * energy) * 0.82;
            wanderVp = (wanderVp + rng.nextGaussian() * 0.07 * energy) * 0.82;
            wanderY = Mth.clamp((wanderY + wanderVy) * 0.96, -1.8, 1.8);
            wanderP = Mth.clamp((wanderP + wanderVp) * 0.96, -1.0, 1.0);
            r = new Rotation(r.getYaw() + (float) wanderY, Mth.clamp(r.getPitch() + (float) wanderP, -90f, 90f));
        }
        baritone.getLookBehavior().updateTarget(r, true);
    }

    private LivingEntity pick(Player me) {
        return ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(CHASE),
                        e -> e != me && e.isAlive() && !e.isRemoved() && filter.test(e))
                .stream().filter(e -> me.distanceTo(e) <= CHASE)
                .min(Comparator.comparingDouble(me::distanceToSqr)).orElse(null);
    }

    private static Vec3 aimPoint(Player me, LivingEntity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox().deflate(0.05);
        return new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY + 0.2, b.maxY - 0.1), Mth.clamp(eye.z, b.minZ, b.maxZ));
    }

    /**
     * Crystal PvP: break the crystal that hurts the target most, else put a crystal on obsidian where it does,
     * else lay obsidian beside the target's feet. Anything that would hurt us more than it, or pop us, is skipped.
     */
    private boolean crystal(Player me) {
        crystalFight = slotOf(me, Items.END_CRYSTAL) >= 0 || slotOf(me, Items.RESPAWN_ANCHOR) >= 0 || !ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(8)).isEmpty();
        if (anchor(me)) return true;
        if (slotOf(me, Items.END_CRYSTAL) < 0 || me.distanceTo(target) > 7) return false;
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        EndCrystal hitIt = null;
        float best = 0;
        for (EndCrystal c : ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(6))) {
            if (exactReach(me, c) > REACH) continue;
            float score = worth(me, c.position(), myHp);
            if (score > best) {
                best = score;
                hitIt = c;
            }
        }
        if (hitIt == null && slotOf(me, Items.OBSIDIAN) >= 0) {
            // a crystal that would hurt us and that we won't pop: wall it off at leg height
            EndCrystal danger = null;
            float worst = 6;
            for (EndCrystal c : ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(6))) {
                float d = blast(me, c.position(), 12);
                if (d >= worst) { worst = d; danger = c; }
            }
            if (danger != null && shield(me, danger.blockPosition())) return true;
        }
        if (hitIt != null) {
            look(hitIt.position());
            ctx.minecraft().gameMode.attack(me, hitIt);
            me.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        Level w = ctx.world();
        BlockPos base = null;
        best = 0;
        BlockPos t = target.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -2, -3), t.offset(3, 1, 3))) {
            if (!w.getBlockState(p).is(Blocks.OBSIDIAN) && !w.getBlockState(p).is(Blocks.BEDROCK)) continue;
            if (!w.isEmptyBlock(p.above()) || !w.getEntities(null, new AABB(p.above())).isEmpty()) continue;
            Vec3 at = Vec3.atBottomCenterOf(p.above());
            if (me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5 || me.getEyePosition().distanceTo(at.add(0, 1, 0)) > REACH + 0.8) continue;
            float score = worth(me, at, myHp);
            if (score > best) {
                best = score;
                base = p.immutable();
            }
        }
        if (base != null) return place(me, Items.END_CRYSTAL, base);
        if (slotOf(me, Items.OBSIDIAN) < 0) return false;
        BlockPos floor = null;
        best = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (BlockPos p : new BlockPos[]{t.relative(d), t.relative(d).below()}) {
                if (!w.getBlockState(p).canBeReplaced() || w.getBlockState(p.below()).canBeReplaced()) continue;
                if (!w.getEntities(null, new AABB(p)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
                float score = worth(me, Vec3.atBottomCenterOf(p.above()), myHp);
                if (score > best) {
                    best = score;
                    floor = p.below();
                }
            }
        }
        return floor != null && place(me, Items.OBSIDIAN, floor);
    }

    /**
     * Anchor PvP (overworld): blow a charged anchor that hurts the target, else charge an anchor near it with
     * glowstone, else put an anchor down beside its feet.
     */
    private boolean anchor(Player me) {
        if (slotOf(me, Items.RESPAWN_ANCHOR) < 0 && slotOf(me, Items.GLOWSTONE) < 0 || me.distanceTo(target) > 7) return false;
        Level w = ctx.world();
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        BlockPos t = target.blockPosition(), boom = null, charge = null, backOff = null;
        float bestBoom = 0, bestCharge = 0, bestBack = 0;
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -1, -3), t.offset(3, 2, 3))) {
            if (!w.getBlockState(p).is(Blocks.RESPAWN_ANCHOR) || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
            Vec3 at = Vec3.atCenterOf(p);
            float score = worth(me, at, myHp, 10), dmg = blast(target, at, 10);
            boolean charged = w.getBlockState(p).getValue(RespawnAnchorBlock.CHARGE) > 0;
            if (!charged && score > 0) {
                // charging hurts nobody, so charge anything that would hurt the target
                if (dmg > bestCharge) { bestCharge = dmg; charge = p.immutable(); }
            } else if (charged && score > bestBoom) {
                bestBoom = score;
                boom = p.immutable();
            } else if (score <= 0 && blast(me, at, 10) >= 8 && blast(me, at, 10) > bestBack) {
                bestBack = blast(me, at, 10); // theirs or ours, it can go off in our face
                backOff = p.immutable();
            }
        }
        if (boom != null) {
            int slot = -1;
            for (int i = 0; i < 9; i++) {
                Item it = me.getInventory().getItem(i).getItem();
                if (it != Items.GLOWSTONE && it != Items.RESPAWN_ANCHOR) { slot = i; break; }
            }
            if (slot < 0) return false;
            select(me, slot);
            return click(me, boom);
        }
        if (charge != null && slotOf(me, Items.GLOWSTONE) >= 0) {
            select(me, slotOf(me, Items.GLOWSTONE));
            return me.getMainHandItem().getItem() == Items.GLOWSTONE && click(me, charge);
        }
        // a charged anchor that would hurt us: wall it off at leg height, which is where most of the blast lands
        if (backOff != null && shield(me, backOff)) return true;
        backingOff = backOff == null ? 0 : backingOff + 1;
        // a charged anchor that would hurt us too much from here: step away, then blow it (unless a wall keeps us pinned)
        if (backOff != null && backingOff < 40) {
            look(Vec3.atCenterOf(backOff));
            key(Input.MOVE_BACK);
            return true;
        }
        if (slotOf(me, Items.RESPAWN_ANCHOR) < 0 || slotOf(me, Items.GLOWSTONE) < 0) return false;
        BlockPos spot = null;
        float best = 0;
        // not just beside them: a target down a one-wide hole has no free side, only the rim
        for (BlockPos q : BlockPos.betweenClosed(t.offset(-2, -1, -2), t.offset(2, 2, 2))) {
            {
                BlockPos p = q.immutable();
                if (!w.getBlockState(p).canBeReplaced() || w.getBlockState(p.below()).canBeReplaced()) continue;
                if (!w.getEntities(null, new AABB(p)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
                float score = worth(me, Vec3.atCenterOf(p), myHp, 10);
                if (score > best) { best = score; spot = p; }
            }
        }
        return spot != null && place(me, Items.RESPAWN_ANCHOR, spot.below());
    }

    /** Put a block in the cell between our feet and {@code threat} so the explosion's rays hit it instead of our legs. */
    private boolean shield(Player me, BlockPos threat) {
        Item block = slotOf(me, Items.OBSIDIAN) >= 0 ? Items.OBSIDIAN : slotOf(me, Items.COBBLESTONE) >= 0 ? Items.COBBLESTONE
                : slotOf(me, Items.RESPAWN_ANCHOR) >= 0 ? Items.RESPAWN_ANCHOR : null;
        if (block == null) return false;
        BlockPos feet = me.blockPosition();
        int dx = Integer.signum(threat.getX() - feet.getX()), dz = Integer.signum(threat.getZ() - feet.getZ());
        Level w = ctx.world();
        for (BlockPos c : new BlockPos[]{feet.offset(dx, 0, dz), feet.offset(dx, 0, 0), feet.offset(0, 0, dz)}) {
            if (c.equals(feet) || c.equals(threat) || !w.getBlockState(c).canBeReplaced() || w.getBlockState(c.below()).canBeReplaced()) continue;
            if (!w.getEntities(null, new AABB(c)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(c)) > 4.5) continue;
            return place(me, block, c.below());
        }
        return false;
    }

    /** Right-click the top face of a block with whatever is in hand. */
    private boolean click(Player me, BlockPos on) {
        Vec3 face = Vec3.atCenterOf(on).add(0, 0.5, 0);
        look(face);
        ctx.minecraft().gameMode.useItemOn(ctx.minecraft().player, InteractionHand.MAIN_HAND, new BlockHitResult(face, Direction.UP, on, false));
        me.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Right-click the top of {@code on} with {@code item}. */
    private boolean place(Player me, Item item, BlockPos on) {
        select(me, slotOf(me, item));
        if (me.getMainHandItem().getItem() != item) return false;
        return click(me, on);
    }

    /** How good a crystal blowing up at {@code at} is for us: its damage to the target minus ours, 0 if not worth it. */
    private float worth(Player me, Vec3 at, float myHp) {
        return worth(me, at, myHp, 12);
    }

    private float worth(Player me, Vec3 at, float myHp, double size) {
        float dmg = blast(target, at, size), self = blast(me, at, size);
        boolean totem = me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
        if (self >= myHp - (totem ? 0 : 2) && dmg < target.getHealth() + target.getAbsorptionAmount()) return 0;
        // once they're low an even trade wins the race
        if (dmg < 3 || dmg < self * (size == 10 ? (target.getHealth() + target.getAbsorptionAmount() > 10 ? 1.5f : 1) : (target.getHealth() + target.getAbsorptionAmount() <= 10 ? 0.8f : 1))) return 0;
        if (size == 10 && self >= myHp - 4 && dmg < target.getHealth() + target.getAbsorptionAmount()) return 0; // don't pop our own totem
        return dmg - self * 0.6f;
    }

    /** Vanilla end crystal (power 6) damage to {@code e} after armour. */
    private static float blast(LivingEntity e, Vec3 at, double size) {
        double d = Math.sqrt(e.distanceToSqr(at)) / size;
        if (d > 1) return 0;
        // an anchor is removed before it blows, but would block its own rays here, so take it as fully exposed
        double impact = (1 - d) * (size == 12 ? ServerExplosion.getSeenPercent(at, e) : 1);
        float raw = (float) ((impact * impact + impact) / 2 * 7 * size + 1);
        return CombatRules.getDamageAfterAbsorb(e, raw, e.damageSources().generic(), e.getArmorValue(), (float) e.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }

    private static double exactReach(Player me, Entity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox();
        return eye.distanceTo(new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY, b.maxY), Mth.clamp(eye.z, b.minZ, b.maxZ)));
    }

    private static double eyeToBox(Player me, LivingEntity t) {
        return me.getEyePosition().distanceTo(aimPoint(me, t));
    }

    @Override
    public void onLostControl() {
        filter = null;
        target = null;
        eatTicks = blockTicks = 0;
        if (ctx.minecraft().options != null) use(false);
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        return "PvP " + label + (target == null ? "" : " -> " + target.getName().getString());
    }

    @Override
    public double priority() {
        return 2;
    }
}
