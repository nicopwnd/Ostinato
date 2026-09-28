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

package baritone.launch.mixins;

import baritone.behavior.FreecamBehavior;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public class MixinPlayerRenderer {

    /** Render state of the bot's ghost this frame; MixinLivingEntityRenderer tints only this one. */
    public static PlayerRenderState GHOST;

    // While freecam is on, draw the bot as a translucent ghost of its skin (vanilla's invisible-but-seen path)
    // with a tag saying what it is doing.
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
            at = @At("TAIL")
    )
    private void ghost(AbstractClientPlayer player, PlayerRenderState state, float partialTicks, CallbackInfo ci) {
        if (FreecamBehavior.activeCamera() == null || player != Minecraft.getInstance().player) {
            return;
        }
        GHOST = state;
        state.isInvisible = true;
        state.isInvisibleToPlayer = false;
        state.nameTag = Component.literal(FreecamBehavior.botStatus());
        state.nameTagAttachment = new Vec3(0, player.getBbHeight() + 0.5, 0);
    }
}
