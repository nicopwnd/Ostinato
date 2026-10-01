package baritone.process;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Records every PvP fight as one gzipped text file under {@code pvplogs/}, one line per tick, so a fight against a real
 * person (or a bot) can be read back and used to tune the combat. Disable with -Dostinato.record=false.
 * <pre>
 * #fight  header: label, version, both loadouts
 * t | me: x y z vx vy vz yaw pitch hp abs gnd item flags | tg: same | dist | state | events
 * #end    result, ticks, damage dealt/taken, attacks
 * </pre>
 * Flags: B blocking, U using item, S swinging, P sprinting, F fall distance&gt;1.5, W wet. Events: A=we attacked, D=damage taken, H=damage dealt.
 */
public final class PvpRecorder {
    public static final boolean ENABLED = !"false".equals(System.getProperty("ostinato.record"));
    private BufferedWriter out;
    private Path file;
    private int ticks;
    private float dealt, taken, lastTargetHp, lastMyHp;
    private int attacks, lastAttacks;
    private String targetName;
    private boolean targetDied;

    public void markWin() {
        targetDied = true;
    }

    public boolean active() {
        return out != null;
    }

    public void begin(Player me, LivingEntity target, String label) {
        if (!ENABLED || out != null) return;
        try {
            Path dir = Paths.get("pvplogs");
            Files.createDirectories(dir);
            targetName = target.getName().getString();
            file = dir.resolve("fight-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + targetName.replaceAll("[^A-Za-z0-9_]", "") + ".log.gz");
            out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file), true), StandardCharsets.UTF_8));
            ticks = attacks = lastAttacks = 0;
            dealt = taken = 0;
            targetDied = false;
            lastTargetHp = target.getHealth() + target.getAbsorptionAmount();
            lastMyHp = me.getHealth() + me.getAbsorptionAmount();
            line("#fight v1 label=" + label + " target=" + targetName);
            line("#me " + gear(me));
            line("#tg " + gear(target));
        } catch (Exception e) {
            out = null;
            System.out.println("PvpRecorder: " + e);
        }
    }

    public void tick(Player me, LivingEntity target, double dist, String state, int totalAttacks) {
        if (out == null) return;
        try {
            ticks++;
            float myHp = me.getHealth() + me.getAbsorptionAmount(), tHp = target.getHealth() + target.getAbsorptionAmount();
            StringBuilder ev = new StringBuilder();
            if (totalAttacks > lastAttacks) {
                ev.append('A');
                attacks += totalAttacks - lastAttacks;
            }
            lastAttacks = totalAttacks;
            if (myHp < lastMyHp - 0.01f) {
                ev.append("D").append(f(lastMyHp - myHp));
                taken += lastMyHp - myHp;
            }
            if (tHp < lastTargetHp - 0.01f) {
                ev.append("H").append(f(lastTargetHp - tHp));
                dealt += lastTargetHp - tHp;
            }
            lastMyHp = myHp;
            lastTargetHp = tHp;
            line(ticks + "|" + ent(me) + "|" + ent(target) + "|" + f(dist) + "|" + state + "|" + ev);
            if (ticks % 100 == 0) out.flush();
        } catch (Exception e) {
            out = null;
        }
    }

    public void end(Player me, String reason) {
        if (out == null) return;
        try {
            String result = targetDied ? "win" : me.isDeadOrDying() || me.getHealth() <= 0 ? "death" : reason;
            line("#end result=" + result + " ticks=" + ticks + " dealt=" + f(dealt) + " taken=" + f(taken) + " attacks=" + attacks);
            out.close();
            System.out.println("PvpRecorder: " + file + " (" + result + ", " + ticks + " ticks)");
        } catch (Exception e) {
            System.out.println("PvpRecorder: " + e);
        }
        out = null;
    }

    private void line(String s) throws java.io.IOException {
        out.write(s);
        out.write('\n');
    }

    private static String f(double v) {
        return String.format("%.2f", v);
    }

    private static String ent(LivingEntity e) {
        StringBuilder fl = new StringBuilder();
        if (e.isBlocking()) fl.append('B');
        if (e.isUsingItem()) fl.append('U');
        if (e.swinging) fl.append('S');
        if (e.isSprinting()) fl.append('P');
        if (e.fallDistance > 1.5) fl.append('F');
        if (e.isInWater()) fl.append('W');
        var v = e.getDeltaMovement();
        return f(e.getX()) + "," + f(e.getY()) + "," + f(e.getZ()) + "," + f(v.x) + "," + f(v.y) + "," + f(v.z) + ","
                + f(e.getYRot()) + "," + f(e.getXRot()) + "," + f(e.getHealth()) + "," + f(e.getAbsorptionAmount()) + ","
                + (e.onGround() ? 1 : 0) + "," + e.getMainHandItem().getItem().toString().replace("minecraft:", "") + "," + fl;
    }

    private static String gear(LivingEntity e) {
        StringBuilder sb = new StringBuilder();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            ItemStack st = e.getItemBySlot(s);
            sb.append(s.getName()).append('=').append(st.isEmpty() ? "-" : st.getItem().toString().replace("minecraft:", "")).append(' ');
        }
        if (e instanceof Player p)
            for (int i = 0; i < 9; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (!st.isEmpty()) sb.append("h").append(i).append('=').append(st.getCount()).append('x').append(st.getItem().toString().replace("minecraft:", "")).append(' ');
            }
        return sb.toString().trim();
    }
}
