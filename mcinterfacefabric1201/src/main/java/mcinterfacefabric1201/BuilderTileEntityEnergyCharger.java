package mcinterfacefabric1201;

import minecrafttransportsimulator.blocks.tileentities.components.ATileEntityBase;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityEnergyCharger;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * Builder for tile entities that transform MC energy into power for other entities.
 *
 * @author don_bruce
 */
public class BuilderTileEntityEnergyCharger extends BuilderTileEntity {
    protected static BlockEntityType<BuilderTileEntityEnergyCharger> TE_TYPE2;

    private ITileEntityEnergyCharger charger;
    private static final int MAX_BUFFER = 1000;
    /**
     * Receive-only energy buffer; maxExtract of 0 blocks external extraction.
     **/
    protected final SimpleEnergyStorage energyStorage = new SimpleEnergyStorage(MAX_BUFFER, MAX_BUFFER, 0);

    public BuilderTileEntityEnergyCharger(BlockPos pos, BlockState state) {
        super(TE_TYPE2, pos, state);
    }

    @Override
    protected void setTileEntity(ATileEntityBase<?> tile) {
        super.setTileEntity(tile);
        this.charger = (ITileEntityEnergyCharger) tile;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level.isClientSide && charger != null) {
            //Try and charge the internal TE.
            if (energyStorage.amount > 0) {
                double amountToCharge = charger.getChargeAmount();
                if (amountToCharge != 0) {
                    int amountToRemoveFromBuffer = (int) (amountToCharge / ConfigSystem.settings.general.rfToElectricityFactor.value);
                    if (amountToRemoveFromBuffer > energyStorage.amount) {
                        amountToRemoveFromBuffer = (int) energyStorage.amount;
                        amountToCharge = amountToRemoveFromBuffer * ConfigSystem.settings.general.rfToElectricityFactor.value;
                    }
                    charger.chargeEnergy(amountToCharge);
                    energyStorage.amount -= amountToRemoveFromBuffer;
                }
            }
        }
    }

    /**
     * Registers the energy API lookup for this TE type.  Must be called after {@link #TE_TYPE2} is registered.
     **/
    public static void registerEnergyLookup() {
        EnergyStorage.SIDED.registerForBlockEntity((be, dir) -> dir != null ? be.energyStorage : null, TE_TYPE2);
    }
}
