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

import baritone.utils.accessor.IFireworkRocketEntity;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.OptionalInt;

@Mixin(FireworkRocketEntity.class)
public abstract class MixinFireworkRocketEntity extends Entity implements IFireworkRocketEntity {

    @Shadow
    @Final
    private static DataParameter<OptionalInt> BOOSTED_ENTITY_ID;

    @Shadow
    private LivingEntity boostedEntity;

    @Shadow
    protected abstract boolean isAttachedToEntity();

    private MixinFireworkRocketEntity(World level) {
        super(EntityType.FIREWORK_ROCKET, level);
    }

    @Override
    public LivingEntity getBoostedEntity() {
        if (this.isAttachedToEntity() && this.boostedEntity == null) { // isAttachedToEntity checks if the optional is present
            final Entity entity = this.world.getEntityByID(this.dataManager.get(BOOSTED_ENTITY_ID).getAsInt());
            if (entity instanceof LivingEntity) {
                this.boostedEntity = (LivingEntity) entity;
            }
        }
        return this.boostedEntity;
    }
}
