package painting.compostimprove.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES;
import static net.minecraft.world.level.block.ComposterBlock.LEVEL;

@Mixin(ComposterBlock.class)
public abstract class CompostMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void onUseWithItem(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        BlockState currentState = getCurrentComposterState(world, pos, state);
        if (currentState == null) {
            return;
        }
        int level = currentState.getValue(LEVEL);
        if (level <= 0) {
            return;
        }
        boolean shouldKeepVanillaInsert = level < 8 && COMPOSTABLES.containsKey(stack.getItem());
        if (shouldKeepVanillaInsert) {
            return;
        }
        if (isClientLevel(world)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        Vec3 outputPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.15, pos.getZ() + 0.5).offsetRandom(world.getRandom(), 0.25f);
        ItemEntity boneMeal = new ItemEntity(world, outputPos.x, outputPos.y, outputPos.z, new ItemStack(Items.BONE_MEAL));
        boneMeal.setDefaultPickUpDelay();
        world.addFreshEntity(boneMeal);
        int nextLevel = level == 8 ? 6 : level - 1;
        world.setBlock(pos, currentState.setValue(LEVEL, nextLevel), 3);
        world.playSound(null, pos, SoundEvents.COMPOSTER_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);
        cir.setReturnValue(InteractionResult.CONSUME);
    }

    @Inject(method = "extractProduce", at = @At("HEAD"), cancellable = true)
    private static void emptyComposter(Entity user, BlockState state, Level world, BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        int level = state.getValue(LEVEL);
        if (level <= 0) {
            cir.setReturnValue(state);
            return;
        }
        cir.setReturnValue(decreaseCompostLevel(user, state, world, pos));
    }

    @Inject(method = "getContainer", at = @At("HEAD"), cancellable = true)
    private void getInventory(BlockState state, LevelAccessor world, BlockPos pos, CallbackInfoReturnable<WorldlyContainer> cir) {
        int level = state.getValue(LEVEL);
        if (level > 0) {
            cir.setReturnValue(new LayeredOutputInventory(state, world, pos));
        }
    }

    @Invoker("addItem")
    private static BlockState invokeAddToComposter(Entity user, BlockState state, LevelAccessor world, BlockPos pos, ItemStack stack) {
        throw new UnsupportedOperationException();
    }

    private static BlockState decreaseCompostLevel(Entity user, BlockState state, LevelAccessor world, BlockPos pos) {
        BlockState currentState = getCurrentComposterState(world, pos, state);
        if (currentState == null) {
            return state;
        }
        int currentLevel = currentState.getValue(LEVEL);
        if (currentLevel <= 0) {
            return currentState;
        }
        int nextLevel = currentLevel == 8 ? 6 : currentLevel - 1;
        BlockState nextState = currentState.setValue(LEVEL, nextLevel);
        world.setBlock(pos, nextState, 3);
        world.gameEvent(user, GameEvent.BLOCK_CHANGE, pos);
        return nextState;
    }

    private static BlockState getCurrentComposterState(LevelAccessor world, BlockPos pos, BlockState fallback) {
        BlockState currentState = world.getBlockState(pos);
        if (currentState.is(fallback.getBlock())) {
            return currentState;
        }
        return null;
    }

    private static boolean isClientLevel(LevelAccessor world) {
        return world instanceof Level level && level.isClientSide();
    }

    private static final class LayeredOutputInventory extends SimpleContainer implements WorldlyContainer {
        private static final int[] BOTTOM_SLOT = new int[]{0};
        private static final int[] TOP_SLOT = new int[]{1};
        private static final int[] EMPTY_SLOT = new int[0];
        private final BlockState state;
        private final LevelAccessor world;
        private final BlockPos pos;
        private boolean processingInsert;
        private boolean processingExtract;

        private LayeredOutputInventory(BlockState state, LevelAccessor world, BlockPos pos) {
            super(new ItemStack(Items.BONE_MEAL), ItemStack.EMPTY);
            this.state = state;
            this.world = world;
            this.pos = pos;
        }

        @Override
        public int[] getSlotsForFace(Direction side) {
            if (side == Direction.DOWN) {
                return BOTTOM_SLOT;
            }
            if (side == Direction.UP) {
                return TOP_SLOT;
            }
            return EMPTY_SLOT;
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction dir) {
            BlockState currentState = getCurrentComposterState(this.world, this.pos, this.state);
            return currentState != null
                    && currentState.getValue(LEVEL) < 8
                    && slot == 1
                    && dir == Direction.UP
                    && this.getItem(1).isEmpty()
                    && COMPOSTABLES.containsKey(stack.getItem());
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
            BlockState currentState = getCurrentComposterState(this.world, this.pos, this.state);
            return currentState != null
                    && currentState.getValue(LEVEL) > 0
                    && slot == 0
                    && dir == Direction.DOWN
                    && !this.getItem(0).isEmpty()
                    && stack.is(Items.BONE_MEAL);
        }

        @Override
        public void setChanged() {
            if (this.processingInsert) {
                return;
            }
            if (isClientLevel(this.world)) {
                return;
            }
            this.processingInsert = true;
            try {
                ItemStack inputStack = this.getItem(1);
                if (!inputStack.isEmpty()) {
                    BlockState currentState = getCurrentComposterState(this.world, this.pos, this.state);
                    if (currentState == null) {
                        return;
                    }
                    BlockState after = invokeAddToComposter(null, currentState, this.world, this.pos, inputStack);
                    this.world.levelEvent(1500, this.pos, after != currentState ? 1 : 0);
                    this.removeItemNoUpdate(1);
                }
            } finally {
                this.processingInsert = false;
            }
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack removed = super.removeItem(slot, amount);
            if (slot == 0 && !removed.isEmpty()) {
                this.onBoneMealExtracted();
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack removed = super.removeItemNoUpdate(slot);
            if (slot == 0 && !removed.isEmpty()) {
                this.onBoneMealExtracted();
            }
            return removed;
        }

        private void onBoneMealExtracted() {
            if (this.processingExtract) {
                return;
            }
            if (isClientLevel(this.world)) {
                return;
            }
            this.processingExtract = true;
            try {
                BlockState currentState = getCurrentComposterState(this.world, this.pos, this.state);
                if (currentState == null) {
                    return;
                }
                if (currentState.getValue(LEVEL) > 0) {
                    decreaseCompostLevel(null, currentState, this.world, this.pos);
                }
            } finally {
                this.processingExtract = false;
            }
        }
    }
}
