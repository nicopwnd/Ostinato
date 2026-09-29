package baritone.process;

import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
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
        if (eatTicks > 0 || (me.getHealth() <= 5 || me.getHealth() <= 11 && safe) && !me.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)
                && (slotOf(me, Items.GOLDEN_APPLE) >= 0 || slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0)) {
            if (eat(me)) return pause();
        }

        double dist = eyeToBox(me, target);
        boolean los = me.hasLineOfSight(target);

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

        if (dist > DRIVE || !los) {
            if (los && dist > BOW_MIN && slotOf(me, Items.BOW) >= 0 && slotOf(me, Items.ARROW) >= 0) return bow(me);
            use(false);
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if (me.isUsingItem()) use(false);

        boolean inReach = exactReach(me, target) <= REACH - 0.05;
        // a shield being raised blocks before isBlocking() shows it; only swap in reach, since any swap drains the charge
        boolean shieldUp = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
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
            if (me.onGround() && me.isSprinting() && !me.isInWater()) me.jumpFromGround();
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
        if (me.getHealth() > 8 || me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;
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
        if (slot >= 0) me.getInventory().selected = slot;
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

    private static double exactReach(Player me, LivingEntity t) {
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
