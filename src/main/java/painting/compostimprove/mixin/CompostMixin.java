package painting.compostimprove.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.ComposterBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static net.minecraft.block.ComposterBlock.LEVEL;
import static net.minecraft.block.ComposterBlock.ITEM_TO_LEVEL_INCREASE_CHANCE;

@Mixin(ComposterBlock.class)
public abstract class CompostMixin {
    @Inject(method = "onUse", at = @At("HEAD"), cancellable = true)
    private void onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir){
        int i = state.get(LEVEL);
        ItemStack itemStack = player.getStackInHand(hand);
        if (ITEM_TO_LEVEL_INCREASE_CHANCE.containsKey(itemStack.getItem())) {
            if (i < 8 && !world.isClient) {
                boolean addToComposter = CompostMixin.addToComposter(state, world, pos, itemStack);
                world.syncWorldEvent(100, pos, addToComposter ? 1 : 0);
                if (!player.getAbilities().creativeMode) {
                    itemStack.decrement(1);
                }
            }
            cir.setReturnValue(ActionResult.SUCCESS);
        } else if (i > 0) {
            if (!world.isClient) {
                Vec3d vec3d = Vec3d.add(pos, 0.5, 1.01, 0.5).addRandom(world.random, 0.7f);
                ItemEntity itemEntity = new ItemEntity(world, vec3d.getX(), vec3d.getY(), vec3d.getZ(), new ItemStack(Items.BONE_MEAL));
                itemEntity.setToDefaultPickupDelay();
                world.spawnEntity(itemEntity);
            }
            world.setBlockState(pos, state.with(LEVEL, i - 1), 3);
            world.playSound(null, pos, SoundEvents.BLOCK_COMPOSTER_EMPTY, SoundCategory.BLOCKS, 1.0f, 1.0f);
            cir.setReturnValue(ActionResult.SUCCESS);
        } else {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    @Unique
    private static boolean addToComposter(BlockState state, WorldAccess world, BlockPos pos, ItemStack stack) {
        int j = state.get(LEVEL);
        float f = ITEM_TO_LEVEL_INCREASE_CHANCE.getFloat(stack.getItem());
        if ((j != 0 || f <= 0.0f) && world.getRandom().nextDouble() >= (double) f) {
            return false;
        } else {
            int i = j + 1;
            world.setBlockState(pos, state.with(LEVEL, i), 3);
            if (i == 7) {
                world.scheduleBlockTick(pos, state.getBlock(), 20);
            }
            return true;
        }
    }
}
