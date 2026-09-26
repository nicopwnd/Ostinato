package baritone.utils;

import baritone.api.utils.IPlayerContext;
import net.minecraft.inventory.container.ClickType;
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
}
