package mcinterfacefabric1201;

import minecrafttransportsimulator.blocks.tileentities.components.ATileEntityBase;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityFluidTankProvider;
import minecrafttransportsimulator.blocks.tileentities.instances.TileEntityFluidLoader;
import minecrafttransportsimulator.entities.instances.EntityFluidTank;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

/**
 * Builder for tile entities that contain fluids.  This builder ticks.
 * <p>
 * On Fabric the Forge fluid capability is replaced with the Transfer API: the tank is
 * exposed as a {@link Storage} of {@link FluidVariant} on the {@link Direction#DOWN} face
 * (matching the Forge facing gate).  IV's {@link EntityFluidTank} works in millibuckets,
 * so all amounts crossing the Transfer API boundary are converted using
 * {@link #DROPLETS_PER_MB} (1 mB = 81 droplets).
 *
 * @author don_bruce
 */
public class BuilderTileEntityFluidTank extends BuilderTileEntity {
    public static BlockEntityType<BuilderTileEntityFluidTank> TE_TYPE2;

    private static final long DROPLETS_PER_MB = 81L;

    private EntityFluidTank tank;
    private final FluidTankStorage fluidStorage = new FluidTankStorage();

    public BuilderTileEntityFluidTank(BlockPos pos, BlockState state) {
        super(TE_TYPE2, pos, state);
    }

    @Override
    protected void setTileEntity(ATileEntityBase<?> tile) {
        super.setTileEntity(tile);
        this.tank = ((ITileEntityFluidTankProvider) tile).getTank();
    }

    @Override
    public void tick() {
        super.tick();
        if (tank != null) {
            if (tileEntity instanceof TileEntityFluidLoader && ((TileEntityFluidLoader) tileEntity).isUnloader()) {
                if (tank.getFluidLevel() > 0) {
                    //Pump out fluid to the storage below, if we have one.
                    Storage<FluidVariant> below = FluidStorage.SIDED.find(level, getBlockPos().below(), Direction.UP);
                    if (below != null) {
                        StorageUtil.move(fluidStorage, below, variant -> true, Long.MAX_VALUE, null);
                    }
                }
            }
        }
    }

    /**
     * Returns the Transfer API storage for this tank, but only for the {@link Direction#DOWN}
     * face, matching the Forge {@code getCapability(FLUID_HANDLER, DOWN)} gate.  Returns null
     * for every other side.
     */
    public Storage<FluidVariant> getFluidStorage(Direction side) {
        return side == Direction.DOWN ? fluidStorage : null;
    }

    /**
     * Registers the fluid Transfer API lookup for this block-entity type.  Must be called from
     * the main mod initializer after {@link #TE_TYPE2} has been assigned.
     */
    public static void registerFluidLookup() {
        FluidStorage.SIDED.registerForBlockEntity((be, dir) -> be.getFluidStorage(dir), TE_TYPE2);
    }

    /**
     * Resolves the vanilla {@link Fluid} for whatever the IV tank currently holds.  IV stores
     * fluids by path (+ optional mod namespace), so we rebuild the {@link ResourceLocation} and,
     * if no namespace is recorded, fall back to a path search over the fluid registry (matching
     * the old Forge behaviour).
     */
    private Fluid resolveFluid() {
        if (tank == null) {
            return null;
        }
        String path = tank.getFluid();
        if (path.isEmpty()) {
            return null;
        }
        String namespace = tank.getFluidMod();
        if (namespace != null && !namespace.isEmpty()) {
            ResourceLocation direct = new ResourceLocation(namespace, path);
            if (BuiltInRegistries.FLUID.containsKey(direct)) {
                return BuiltInRegistries.FLUID.get(direct);
            }
        }
        for (ResourceLocation fluidKey : BuiltInRegistries.FLUID.keySet()) {
            if (fluidKey.getPath().equals(path)) {
                return BuiltInRegistries.FLUID.get(fluidKey);
            }
        }
        return null;
    }

    /**
     * Single-tank Transfer API view over the IV {@link EntityFluidTank}.  Snapshots the tank's
     * (fluid, mod, level) state for transaction rollback via {@link EntityFluidTank#manuallySet}.
     */
    private class FluidTankStorage extends SnapshotParticipant<FluidTankStorage.SavedState> implements SingleSlotStorage<FluidVariant> {

        @Override
        public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount);
            if (tank == null) {
                return 0;
            }
            ResourceLocation loc = BuiltInRegistries.FLUID.getKey(resource.getFluid());
            if (loc == null) {
                return 0;
            }
            long mb = maxAmount / DROPLETS_PER_MB;
            if (mb <= 0) {
                return 0;
            }
            double filled = tank.fill(loc.getPath(), loc.getNamespace(), mb, false);
            if (filled > 0) {
                updateSnapshots(transaction);
                tank.fill(loc.getPath(), loc.getNamespace(), filled, true);
            }
            return (long) filled * DROPLETS_PER_MB;
        }

        @Override
        public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount);
            if (tank == null) {
                return 0;
            }
            ResourceLocation loc = BuiltInRegistries.FLUID.getKey(resource.getFluid());
            if (loc == null) {
                return 0;
            }
            long mb = maxAmount / DROPLETS_PER_MB;
            if (mb <= 0) {
                return 0;
            }
            double drained = tank.drain(loc.getPath(), loc.getNamespace(), mb, false);
            if (drained > 0) {
                updateSnapshots(transaction);
                tank.drain(loc.getPath(), loc.getNamespace(), drained, true);
            }
            return (long) drained * DROPLETS_PER_MB;
        }

        @Override
        public boolean isResourceBlank() {
            return getResource().isBlank();
        }

        @Override
        public FluidVariant getResource() {
            Fluid fluid = resolveFluid();
            return fluid == null ? FluidVariant.blank() : FluidVariant.of(fluid);
        }

        @Override
        public long getAmount() {
            return tank == null ? 0 : (long) tank.getFluidLevel() * DROPLETS_PER_MB;
        }

        @Override
        public long getCapacity() {
            return tank == null ? 0 : (long) tank.getMaxLevel() * DROPLETS_PER_MB;
        }

        @Override
        protected SavedState createSnapshot() {
            return new SavedState(tank.getFluid(), tank.getFluidMod(), tank.getFluidLevel());
        }

        @Override
        protected void readSnapshot(SavedState snapshot) {
            tank.manuallySet(snapshot.fluid, snapshot.mod, snapshot.level);
        }

        private final class SavedState {
            private final String fluid;
            private final String mod;
            private final double level;

            private SavedState(String fluid, String mod, double level) {
                this.fluid = fluid;
                this.mod = mod;
                this.level = level;
            }
        }
    }
}
