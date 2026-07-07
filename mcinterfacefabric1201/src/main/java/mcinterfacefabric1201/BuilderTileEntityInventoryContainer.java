package mcinterfacefabric1201;

import minecrafttransportsimulator.blocks.tileentities.components.ATileEntityBase;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityInventoryProvider;
import minecrafttransportsimulator.entities.instances.EntityInventoryContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builder for tile entities that contain inventories.  This builder ticks.
 *
 * @author don_bruce
 */
public class BuilderTileEntityInventoryContainer extends BuilderTileEntity implements WorldlyContainer {
    protected static BlockEntityType<BuilderTileEntityInventoryContainer> TE_TYPE2;

    private EntityInventoryContainer inventory;

    public BuilderTileEntityInventoryContainer(BlockPos pos, BlockState state) {
        super(TE_TYPE2, pos, state);
    }

    @Override
    protected void setTileEntity(ATileEntityBase<?> tile) {
        super.setTileEntity(tile);
        this.inventory = ((ITileEntityInventoryProvider) tile).getInventory();
    }

    @Override
    public int getContainerSize() {
        return inventory != null ? inventory.getSize() : 0;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < getContainerSize(); ++i) {
            if (!getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        return inventory != null ? ((WrapperItemStack) inventory.getStack(index)).stack : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (inventory != null) {
            ItemStack stack = getItem(slot);
            if (stack.getCount() < amount) {
                amount = stack.getCount();
            }
            ItemStack extracted = stack.copy();
            extracted.setCount(amount);
            stack.setCount(stack.getCount() - amount);
            inventory.setStack(new WrapperItemStack(stack), slot);
            setChanged();
            return extracted;
        } else {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (inventory != null) {
            ItemStack stack = getItem(slot);
            ItemStack extracted = stack.copy();
            stack.setCount(0);
            inventory.setStack(new WrapperItemStack(stack), slot);
            return extracted;
        } else {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (inventory != null) {
            inventory.setStack(new WrapperItemStack(stack), slot);
            setChanged();
        }
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public boolean stillValid(Player player) {
        //Only accessed by automation; players use IV's own GUI system.
        return true;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        //Mirrors the old Forge insertItem validity: only allow insertion into slots
        //that already contain a matching, non-full stack.
        if (inventory != null) {
            ItemStack existingStack = getItem(slot);
            return !stack.isEmpty() && ItemStack.isSameItemSameTags(stack, existingStack) && existingStack.getCount() < existingStack.getMaxStackSize();
        }
        return false;
    }

    @Override
    public int[] getSlotsForFace(Direction facing) {
        //Only expose the inventory on the top/bottom faces, as was done with the Forge capability.
        if (facing == Direction.UP || facing == Direction.DOWN) {
            int[] slots = new int[getContainerSize()];
            for (int i = 0; i < slots.length; ++i) {
                slots[i] = i;
            }
            return slots;
        }
        return new int[0];
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction facing) {
        return (facing == Direction.UP || facing == Direction.DOWN) && canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction facing) {
        //Extraction was always allowed on the Forge handler, so just gate on the exposed faces.
        return facing == Direction.UP || facing == Direction.DOWN;
    }

    @Override
    public void clearContent() {
        if (inventory != null) {
            for (int i = 0; i < inventory.getSize(); ++i) {
                ItemStack stack = getItem(i);
                stack.setCount(0);
                inventory.setStack(new WrapperItemStack(stack), i);
            }
            setChanged();
        }
    }
}
