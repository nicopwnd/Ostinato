package baritone.utils;

import baritone.api.utils.IPlayerContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.BoatEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.item.BoatItem;
import net.minecraft.util.NonNullList;
import net.minecraft.item.ItemStack;

/** Finds a boat anywhere in the inventory, borrowing a hotbar slot for it while it's placed. */
public final class BoatUtil {

    private static int[] borrowed; // {inventory slot, hotbar slot}

    private BoatUtil() {}

    public static boolean hasBoat(NonNullList<ItemStack> inv) {
        for (int i = 0; i < 36; i++) {
            if (inv.get(i).getItem() instanceof BoatItem) return true;
        }
        return false;
    }

    /** Hotbar slot holding a boat, swapping one in from the main inventory if needed; -1 if none. */
    public static int hotbarBoat(IPlayerContext ctx) {
        NonNullList<ItemStack> inv = ctx.player().inventory.mainInventory;
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).getItem() instanceof BoatItem) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (!(inv.get(i).getItem() instanceof BoatItem)) continue;
            int hb = 8;
            for (int j = 0; j < 9; j++) {
                if (inv.get(j).isEmpty()) { hb = j; break; }
            }
            ctx.playerController().windowClick(ctx.player().container.windowId, i, hb, ClickType.SWAP, ctx.player());
            borrowed = new int[]{i, hb};
            return hb;
        }
        return -1;
    }

    /** Put back whatever the borrowed hotbar slot held, once the boat has been placed. */
    public static void restore(IPlayerContext ctx) {
        if (borrowed == null || ctx.player() == null) return;
        if (!(ctx.player().inventory.mainInventory.get(borrowed[1]).getItem() instanceof BoatItem)) {
            ctx.playerController().windowClick(ctx.player().container.windowId, borrowed[0], borrowed[1], ClickType.SWAP, ctx.player());
        }
        borrowed = null;
    }

    /** A boat nobody sits in: whoever boards first drives it, so only these are ours to take. */
    public static boolean free(Entity e) {
        return e instanceof BoatEntity && e.isAlive() && e.getPassengers().isEmpty();
    }

    /** True if we sit in a boat and are its first passenger, the one that steers. */
    public static boolean isDriver(PlayerEntity p) {
        Entity v = p.getRidingEntity();
        return v instanceof BoatEntity && v.getControllingPassenger() == p;
    }

    /**
     * A mob shares our boat. Vanilla seats a boarding player in front of a mob, so we'd steer it, but
     * the mob came along uninvited (it hopped in first, or climbed into our seat's spare place):
     * get out and break the boat, which throws the mob out, rather than ferry it around.
     */
    public static boolean mobAboard(PlayerEntity p) {
        Entity v = p.getRidingEntity();
        if (!(v instanceof BoatEntity)) return false;
        for (Entity e : v.getPassengers()) {
            if (e != p && !(e instanceof PlayerEntity)) return true;
        }
        return false;
    }

    /** Block positions (as longs) of free boats within r of the player, snapshot for path costs. */
    public static Set<Long> freeBoats(World w, PlayerEntity p, double r) {
        Set<Long> out = new HashSet<>();
        if (w == null || p == null || !(w instanceof net.minecraft.client.world.ClientWorld)) return out;
        for (Entity e : ((net.minecraft.client.world.ClientWorld) w).getAllEntities()) {
            if (free(e) && e.getDistance(p) < r) out.add(new BlockPos(e.getPosX(), e.getPosY() + 0.1, e.getPosZ()).toLong());
        }
        return out;
    }
}
