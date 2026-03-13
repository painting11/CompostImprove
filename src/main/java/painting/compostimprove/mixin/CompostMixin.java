package painting.compostimprove.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.ComposterBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.event.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static net.minecraft.block.ComposterBlock.LEVEL;
import static net.minecraft.block.ComposterBlock.ITEM_TO_LEVEL_INCREASE_CHANCE;

@Mixin(ComposterBlock.class)
public abstract class CompostMixin {
    @Inject(method = "onUseWithItem", at = @At("HEAD"), cancellable = true)
    private void onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, net.minecraft.entity.player.PlayerEntity player, net.minecraft.util.Hand hand, net.minecraft.util.hit.BlockHitResult hit, CallbackInfoReturnable<ItemActionResult> cir) {
        int level = state.get(LEVEL);
        if (level <= 0) {
            return;
        }
        boolean shouldKeepVanillaInsert = level < 8 && ITEM_TO_LEVEL_INCREASE_CHANCE.containsKey(stack.getItem());
        if (shouldKeepVanillaInsert) {
            return;
        }
        if (world.isClient) {
            cir.setReturnValue(ItemActionResult.success(true));
            return;
        }
        Vec3d outputPos = Vec3d.add(pos, 0.5, 0.15, 0.5).addRandom(world.random, 0.25f);
        ItemEntity boneMeal = new ItemEntity(world, outputPos.getX(), outputPos.getY(), outputPos.getZ(), new ItemStack(Items.BONE_MEAL));
        boneMeal.setToDefaultPickupDelay();
        world.spawnEntity(boneMeal);
        int nextLevel = level == 8 ? 6 : level - 1;
        world.setBlockState(pos, state.with(LEVEL, nextLevel), 3);
        world.playSound(null, pos, SoundEvents.BLOCK_COMPOSTER_EMPTY, SoundCategory.BLOCKS, 1.0f, 1.0f);
        cir.setReturnValue(ItemActionResult.success(false));
    }

    @Inject(method = "emptyComposter", at = @At("HEAD"), cancellable = true)
    private static void emptyComposter(Entity user, BlockState state, WorldAccess world, BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        int level = state.get(LEVEL);
        if (level <= 0) {
            cir.setReturnValue(state);
            return;
        }
        cir.setReturnValue(decreaseCompostLevel(user, state, world, pos));
    }

    @Inject(method = "getInventory", at = @At("HEAD"), cancellable = true)
    private void getInventory(BlockState state, WorldAccess world, BlockPos pos, CallbackInfoReturnable<SidedInventory> cir) {
        int level = state.get(LEVEL);
        if (level > 0) {
            cir.setReturnValue(new LayeredOutputInventory(state, world, pos));
        }
    }

    @Invoker("addToComposter")
    private static BlockState invokeAddToComposter(Entity user, BlockState state, WorldAccess world, BlockPos pos, ItemStack stack) {
        throw new UnsupportedOperationException();
    }

    private static BlockState decreaseCompostLevel(Entity user, BlockState state, WorldAccess world, BlockPos pos) {
        BlockState currentState = getCurrentComposterState(world, pos, state);
        if (currentState == null) {
            return state;
        }
        int currentLevel = currentState.get(LEVEL);
        if (currentLevel <= 0) {
            return currentState;
        }
        int nextLevel = currentLevel == 8 ? 6 : currentLevel - 1;
        BlockState nextState = currentState.with(LEVEL, nextLevel);
        world.setBlockState(pos, nextState, 3);
        world.emitGameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Emitter.of(user, nextState));
        return nextState;
    }

    private static BlockState getCurrentComposterState(WorldAccess world, BlockPos pos, BlockState fallback) {
        BlockState currentState = world.getBlockState(pos);
        if (currentState.isOf(fallback.getBlock())) {
            return currentState;
        }
        return null;
    }

    private static final class LayeredOutputInventory extends SimpleInventory implements SidedInventory {
        private static final int[] BOTTOM_SLOT = new int[]{0};
        private static final int[] TOP_SLOT = new int[]{1};
        private static final int[] EMPTY_SLOT = new int[0];
        private final BlockState state;
        private final WorldAccess world;
        private final BlockPos pos;
        private boolean processingInsert;
        private boolean processingExtract;

        private LayeredOutputInventory(BlockState state, WorldAccess world, BlockPos pos) {
            super(new ItemStack(Items.BONE_MEAL), ItemStack.EMPTY);
            this.state = state;
            this.world = world;
            this.pos = pos;
        }

        @Override
        public int getMaxCountPerStack() {
            return 1;
        }

        @Override
        public int[] getAvailableSlots(Direction side) {
            if (side == Direction.DOWN) {
                return BOTTOM_SLOT;
            }
            if (side == Direction.UP) {
                return TOP_SLOT;
            }
            return EMPTY_SLOT;
        }

        @Override
        public boolean canInsert(int slot, ItemStack stack, Direction dir) {
            BlockState currentState = getCurrentComposterState(this.world, this.pos, this.state);
            return currentState != null
                    && currentState.get(LEVEL) < 8
                    && slot == 1
                    && dir == Direction.UP
                    && this.getStack(1).isEmpty()
                    && ITEM_TO_LEVEL_INCREASE_CHANCE.containsKey(stack.getItem());
        }

        @Override
        public boolean canExtract(int slot, ItemStack stack, Direction dir) {
            BlockState currentState = getCurrentComposterState(this.world, this.pos, this.state);
            return currentState != null
                    && currentState.get(LEVEL) > 0
                    && slot == 0
                    && dir == Direction.DOWN
                    && !this.getStack(0).isEmpty()
                    && stack.isOf(Items.BONE_MEAL);
        }

        @Override
        public void markDirty() {
            if (this.processingInsert) {
                return;
            }
            if (this.world instanceof World world && world.isClient) {
                return;
            }
            this.processingInsert = true;
            try {
                ItemStack inputStack = this.getStack(1);
                if (!inputStack.isEmpty()) {
                    BlockState currentState = getCurrentComposterState(this.world, this.pos, this.state);
                    if (currentState == null) {
                        return;
                    }
                    BlockState after = invokeAddToComposter(null, currentState, this.world, this.pos, inputStack);
                    this.world.syncWorldEvent(1500, this.pos, after != currentState ? 1 : 0);
                    this.removeStack(1);
                }
            } finally {
                this.processingInsert = false;
            }
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            ItemStack removed = super.removeStack(slot, amount);
            if (slot == 0 && !removed.isEmpty()) {
                this.onBoneMealExtracted();
            }
            return removed;
        }

        @Override
        public ItemStack removeStack(int slot) {
            ItemStack removed = super.removeStack(slot);
            if (slot == 0 && !removed.isEmpty()) {
                this.onBoneMealExtracted();
            }
            return removed;
        }

        private void onBoneMealExtracted() {
            if (this.processingExtract) {
                return;
            }
            if (this.world instanceof World world && world.isClient) {
                return;
            }
            this.processingExtract = true;
            try {
                BlockState currentState = getCurrentComposterState(this.world, this.pos, this.state);
                if (currentState == null) {
                    return;
                }
                if (currentState.get(LEVEL) > 0) {
                    decreaseCompostLevel(null, currentState, this.world, this.pos);
                }
            } finally {
                this.processingExtract = false;
            }
        }
    }
}
