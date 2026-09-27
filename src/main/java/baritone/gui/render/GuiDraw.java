/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.gui.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.vector.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Every draw call of the Ostinato GUI goes through here, and only vanilla 1.16.1 facilities are used: coloured
 * quads through the Tessellator, the vanilla font, item icons and GL scissor. Rounded corners are stepped fills
 * at screen-pixel resolution (no textures, no anti-aliasing). Coordinates are GUI units and may be fractional.
 */
public final class GuiDraw {

    /** Multiplies the alpha of everything drawn; used for the open fade. */
    public static float alpha = 1f;

    private GuiDraw() {}

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static FontRenderer font() {
        return mc().fontRenderer;
    }

    public static double guiScale() {
        return mc().getMainWindow().getGuiScaleFactor();
    }

    public static int withAlpha(int argb) {
        if (alpha >= 1f) {
            return argb;
        }
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, alpha));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int s = 0; s <= 24; s += 8) {
            int x = (a >>> s) & 0xFF, y = (b >>> s) & 0xFF;
            out |= (Math.round(x + (y - x) * t) & 0xFF) << s;
        }
        return out;
    }

    public static int alphaOf(int argb, int newAlpha) {
        return (newAlpha << 24) | (argb & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- quads

    private static BufferBuilder begin() {
        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableAlphaTest(); // keep very faint shadow alphas (vanilla fillGradient does the same)
        RenderSystem.shadeModel(GL11.GL_SMOOTH);
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        return bb;
    }

    private static void end(BufferBuilder bb) {
        bb.finishDrawing();
        WorldVertexBufferUploader.draw(bb);
        RenderSystem.shadeModel(GL11.GL_FLAT);
        RenderSystem.disableBlend();
        RenderSystem.enableAlphaTest();
        RenderSystem.enableTexture();
    }

    private static void vtx(BufferBuilder bb, Matrix4f m, float x, float y, int argb) {
        int c = withAlpha(argb);
        bb.pos(m, x, y, 0f).color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >>> 24) & 0xFF).endVertex();
    }

    /** Quad with per-corner colours: top-left, top-right, bottom-right, bottom-left. */
    private static void quad(BufferBuilder bb, Matrix4f m, float x1, float y1, float x2, float y2, int tl, int tr, int br, int bl) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        vtx(bb, m, x1, y2, bl);
        vtx(bb, m, x2, y2, br);
        vtx(bb, m, x2, y1, tr);
        vtx(bb, m, x1, y1, tl);
    }

    public static void rect(MatrixStack ms, float x1, float y1, float x2, float y2, int argb) {
        gradient(ms, x1, y1, x2, y2, argb, argb, argb, argb);
    }

    public static void gradV(MatrixStack ms, float x1, float y1, float x2, float y2, int top, int bottom) {
        gradient(ms, x1, y1, x2, y2, top, top, bottom, bottom);
    }

    public static void gradH(MatrixStack ms, float x1, float y1, float x2, float y2, int left, int right) {
        gradient(ms, x1, y1, x2, y2, left, right, right, left);
    }

    private static void gradient(MatrixStack ms, float x1, float y1, float x2, float y2, int tl, int tr, int br, int bl) {
        BufferBuilder bb = begin();
        quad(bb, ms.getLast().getMatrix(), x1, y1, x2, y2, tl, tr, br, bl);
        end(bb);
    }

    /** Rounded rectangle, solid. */
    public static void round(MatrixStack ms, float x1, float y1, float x2, float y2, float r, int argb) {
        round(ms, x1, y1, x2, y2, r, argb, argb);
    }

    /**
     * Rounded rectangle with a vertical gradient. Corners are cut per screen-pixel row, so they stay crisp at
     * any GUI scale.
     */
    public static void round(MatrixStack ms, float x1, float y1, float x2, float y2, float r, int top, int bottom) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        double scale = guiScale();
        float px = (float) (1.0 / scale);
        int rp = (int) Math.round(Math.min(r, Math.min(x2 - x1, y2 - y1) / 2f) * scale);
        Matrix4f m = ms.getLast().getMatrix();
        BufferBuilder bb = begin();
        float h = y2 - y1;
        for (int i = 0; i < rp; i++) {
            double dy = rp - i - 0.5;
            float ins = (float) Math.round(rp - Math.sqrt(Math.max(0, rp * rp - dy * dy))) * px;
            float ta = y1 + i * px, tb = ta + px;
            int c1 = lerp(top, bottom, (ta - y1) / h), c2 = lerp(top, bottom, (tb - y1) / h);
            quad(bb, m, x1 + ins, ta, x2 - ins, tb, c1, c1, c2, c2);
            float ba = y2 - (i + 1) * px, bbv = ba + px;
            int c3 = lerp(top, bottom, (ba - y1) / h), c4 = lerp(top, bottom, (bbv - y1) / h);
            quad(bb, m, x1 + ins, ba, x2 - ins, bbv, c3, c3, c4, c4);
        }
        float my1 = y1 + rp * px, my2 = y2 - rp * px;
        int cm1 = lerp(top, bottom, (my1 - y1) / h), cm2 = lerp(top, bottom, (my2 - y1) / h);
        quad(bb, m, x1, my1, x2, my2, cm1, cm1, cm2, cm2);
        end(bb);
    }

    /** 1-screen-pixel-ish border (half a GUI unit) around a filled rounded rect. */
    public static void roundBorder(MatrixStack ms, float x1, float y1, float x2, float y2, float r, int border, int top, int bottom) {
        round(ms, x1, y1, x2, y2, r, border);
        round(ms, x1 + 0.5f, y1 + 0.5f, x2 - 0.5f, y2 - 0.5f, Math.max(0, r - 0.5f), top, bottom);
    }

    public static void roundBorder(MatrixStack ms, float x1, float y1, float x2, float y2, float r, int border, int fill) {
        roundBorder(ms, x1, y1, x2, y2, r, border, fill, fill);
    }

    /** Soft drop shadow from stacked low-alpha rounded rects. */
    public static void shadow(MatrixStack ms, float x1, float y1, float x2, float y2, float r, int size, int maxAlpha) {
        for (int i = size; i > 0; i--) {
            float f = 1f - i / (float) (size + 1);
            int a = Math.round(maxAlpha * f * f / 2.2f);
            round(ms, x1 - i, y1 - i + 2, x2 + i, y2 + i + 3, r + i, a << 24);
        }
    }

    public static void icon(MatrixStack ms, String[] rows, float x, float y, int argb, float k) {
        Matrix4f m = ms.getLast().getMatrix();
        BufferBuilder bb = begin();
        for (int j = 0; j < rows.length; j++) {
            String row = rows[j];
            for (int i = 0; i < row.length(); i++) {
                if (row.charAt(i) == '#') {
                    quad(bb, m, x + i * k, y + j * k, x + (i + 1) * k, y + (j + 1) * k, argb, argb, argb, argb);
                }
            }
        }
        end(bb);
    }

    // ---------------------------------------------------------------- text

    private static final String BOLD = "\u00a7l";

    public static int width(String s) {
        return font().getStringWidth(s);
    }

    public static float width(String s, float k, boolean bold) {
        return font().getStringWidth(bold ? BOLD + s : s) * k;
    }

    /** Draws text; returns its width in GUI units. */
    public static float text(MatrixStack ms, String s, float x, float y, int argb, boolean shadow) {
        return text(ms, s, x, y, 1f, argb, shadow, false);
    }

    public static float text(MatrixStack ms, String s, float x, float y, float k, int argb, boolean shadow, boolean bold) {
        int c = withAlpha(argb);
        String str = bold ? BOLD + s : s;
        if (((c >>> 24) & 0xFF) >= 6) { // the font renders alpha < 4 as opaque
            ms.push();
            ms.translate(x, y, 0);
            if (k != 1f) {
                ms.scale(k, k, 1f);
            }
            if (shadow) {
                font().drawStringWithShadow(ms, str, 0, 0, c);
            } else {
                font().drawString(ms, str, 0, 0, c);
            }
            ms.pop();
        }
        return font().getStringWidth(str) * k;
    }

    /** Per-character colours (used for the gradient logo). */
    public static float textColors(MatrixStack ms, String s, float x, float y, float k, boolean bold, int[] colors, boolean shadow) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            int col = colors[Math.min(colors.length - 1, i)];
            cx += text(ms, ch, cx, y, k, col, shadow, bold);
        }
        return cx - x;
    }

    public static String trim(String s, float maxW, float k) {
        if (width(s, k, false) <= maxW) {
            return s;
        }
        String t = s;
        while (!t.isEmpty() && width(t + "...", k, false) > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "...";
    }

    public static List<String> wrap(String s, float maxW, float k) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : s.split(" ")) {
            String t = line.length() == 0 ? w : line + " " + w;
            if (width(t, k, false) > maxW && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                line.append(w);
            } else {
                line.setLength(0);
                line.append(t);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    // ---------------------------------------------------------------- items + scissor

    /** Item icon at (x, y) drawn {@code size} GUI units wide. */
    public static void item(ItemStack stack, float x, float y, float size) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef(x, y, 0);
        RenderSystem.scalef(size / 16f, size / 16f, 1f);
        mc().getItemRenderer().renderItemIntoGUI(stack, 0, 0);
        RenderSystem.popMatrix();
    }

    /** Clip to a GUI-space rectangle (raw GL: RenderSystem.enableScissor does not exist on 1.16.1). */
    public static void scissor(float x1, float y1, float x2, float y2) {
        double s = guiScale();
        int fbh = mc().getMainWindow().getFramebufferHeight();
        int sx = (int) Math.floor(x1 * s), sy = (int) Math.floor(fbh - y2 * s);
        int sw = (int) Math.ceil((x2 - x1) * s), sh = (int) Math.ceil((y2 - y1) * s);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, Math.max(0, sw), Math.max(0, sh));
    }

    public static void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
