package mcinterfacefabric1201;

import java.util.List;

import minecrafttransportsimulator.entities.instances.EntityFluidTank;
import minecrafttransportsimulator.items.components.AItemBase;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperItemStack;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.ResourceAmount;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;

public class WrapperItemStack implements IWrapperItemStack {

    protected final ItemStack stack;

    protected WrapperItemStack(ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public boolean isCompleteMatch(IWrapperItemStack other) {
        ItemStack otherStack = ((WrapperItemStack) other).stack;
        return !stack.isEmpty() && otherStack.is(stack.getItem()) && (otherStack.hasTag() ? otherStack.getTag().equals(stack.getTag()) : !stack.hasTag());
    }

    @Override
    public int getFurnaceFuelValue() {
        Integer burnTime = FuelRegistry.INSTANCE.get(stack.getItem());
        return burnTime != null ? burnTime : 0;
    }

    @Override
    public IWrapperItemStack getSmeltedItem(AWrapperWorld world) {
        Level mcWorld = ((WrapperWorld) world).world;
        List<SmeltingRecipe> results = mcWorld.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING);
        return new WrapperItemStack(results.isEmpty() ? ItemStack.EMPTY : results.get(0).getResultItem(((WrapperWorld) world).world.registryAccess()));
    }

    @Override
    public int getSmeltingTime(AWrapperWorld world) {
        Level mcWorld = ((WrapperWorld) world).world;
        return mcWorld.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING).get(0).getCookingTime();
    }

    @Override
    public boolean isBrewingFuel() {
        return stack.getItem() == Items.BLAZE_POWDER;
    }

    @Override
    public boolean isBrewingVessel() {
        //Same items the vanilla brewing stand accepts in its potion slots; Fabric has no brewing registry.
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.GLASS_BOTTLE);
    }

    @Override
    public boolean isBrewingModifier() {
        return PotionBrewing.isIngredient(stack);
    }

    @Override
    public IWrapperItemStack getBrewedItem(IWrapperItemStack modifierStack) {
        ItemStack modifier = ((WrapperItemStack) modifierStack).stack;
        //Vanilla mix returns the input stack un-changed if there's no valid mix, so check first to return empty like Forge did.
        return new WrapperItemStack(PotionBrewing.hasMix(stack, modifier) ? PotionBrewing.mix(modifier, stack).copy() : ItemStack.EMPTY);
    }

    @Override
    public AItemBase getItem() {
        Item item = stack.getItem();
        return item instanceof IBuilderItemInterface ? ((IBuilderItemInterface) item).getWrappedItem() : null;
    }

    @Override
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    @Override
    public int getSize() {
        return stack.getCount();
    }

    @Override
    public int getMaxSize() {
        return stack.getMaxStackSize();
    }

    @Override
    public int add(int qty) {
        if (qty < 0) {
            int amountToRemove = -qty;
            if (amountToRemove > getSize()) {
                amountToRemove = getSize();
            }
            stack.setCount(stack.getCount() - amountToRemove);
            return qty + amountToRemove;
        } else {
            int amountToAdd = qty;
            if (amountToAdd + getSize() > getMaxSize()) {
                amountToAdd = getMaxSize() - getSize();
            }
            stack.setCount(stack.getCount() + amountToAdd);
            return qty - amountToAdd;
        }
    }

    @Override
    public IWrapperItemStack copy() {
        return new WrapperItemStack(stack.copy());
    }

    @Override
    public IWrapperItemStack split(int qty) {
        return new WrapperItemStack(stack.split(qty));
    }

    @Override
    public boolean interactWith(EntityFluidTank tank, IWrapperPlayer player) {
        //This is always called with the player's held stack, so use the main-hand context.  Committed transactions
        //update the held stack in the player's inventory for us, so no setHeldStack calls are required.
        //Note that the tank works in mB, while Fabric works in droplets: 1mB = 81 droplets.
        Storage<FluidVariant> handler = FluidStorage.ITEM.find(stack, ContainerItemContext.ofPlayerHand(((WrapperPlayer) player).player, InteractionHand.MAIN_HAND));
        if (handler != null) {
            if (!player.isSneaking()) {
                //Item can provide fluid.  Check if the tank can accept it.
                ResourceAmount<FluidVariant> drainedContents = StorageUtil.findExtractableContent(handler, null);
                if (drainedContents != null) {
                    //Able to take fluid from item, attempt to do so.
                    ResourceLocation fluidLocation = BuiltInRegistries.FLUID.getKey(drainedContents.resource().getFluid());
                    int amountToDrain = (int) tank.fill(fluidLocation.getPath(), fluidLocation.getNamespace(), drainedContents.amount() / 81, false);
                    long amountDrained;
                    try (Transaction transaction = Transaction.openOuter()) {
                        amountDrained = handler.extract(drainedContents.resource(), amountToDrain * 81L, transaction);
                        if (!player.isCreative()) {
                            transaction.commit();
                        }
                    }
                    if (amountDrained > 0) {
                        //Was able to provide liquid from item.  Fill the tank.
                        tank.fill(fluidLocation.getPath(), fluidLocation.getNamespace(), amountDrained / 81, true);
                    }
                }
            } else {
                //Item can hold fluid.  Check if we can fill it.
                //Need to find the mod that registered this fluid, Forge is stupid and has them per-mod vs just all with a single name.
                for (ResourceLocation fluidKey : BuiltInRegistries.FLUID.keySet()) {
                    if ((tank.getFluidMod().equals(EntityFluidTank.WILDCARD_FLUID_MOD) || tank.getFluidMod().equals(fluidKey.getNamespace())) && fluidKey.getPath().equals(tank.getFluid())) {
                        FluidVariant containedFluid = FluidVariant.of(BuiltInRegistries.FLUID.get(fluidKey));
                        long amountFilled;
                        try (Transaction transaction = Transaction.openOuter()) {
                            amountFilled = handler.insert(containedFluid, (long) tank.getFluidLevel() * 81L, transaction);
                            if (!player.isCreative()) {
                                transaction.commit();
                            }
                        }
                        if (amountFilled > 0) {
                            //Were able to fill the item.  Apply state change to tank and item.
                            tank.drain(amountFilled / 81, true);
                        }
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public IWrapperNBT getData() {
        return stack.hasTag() ? new WrapperNBT(stack.getTag().copy()) : null;
    }

    @Override
    public void setData(IWrapperNBT data) {
        stack.setTag(data != null ? ((WrapperNBT) data).tag : null);
    }
}