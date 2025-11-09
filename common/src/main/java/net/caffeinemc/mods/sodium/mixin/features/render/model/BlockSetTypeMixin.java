package net.caffeinemc.mods.sodium.mixin.features.render.model;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Objects;

@Mixin(BlockSetType.class)
public abstract class BlockSetTypeMixin {

    // Add new fields to the class for caching the hash code.
    // The @Unique annotation ensures they don't conflict with existing fields.
    @Unique
    private int cachedHashCode;
    @Unique
    private boolean isHashCodeCalculated = false;

    // Use @Shadow to get access to the record's components, which are needed for the calculation.
    @Shadow public abstract String name();
    @Shadow public abstract boolean canOpenByHand();
    @Shadow public abstract boolean canOpenByWindCharge();
    @Shadow public abstract boolean canButtonBeActivatedByArrows();
    @Shadow public abstract BlockSetType.PressurePlateSensitivity pressurePlateSensitivity();
    @Shadow public abstract SoundType soundType();
    @Shadow public abstract SoundEvent doorClose();
    @Shadow public abstract SoundEvent doorOpen();
    @Shadow public abstract SoundEvent trapdoorClose();
    @Shadow public abstract SoundEvent trapdoorOpen();
    @Shadow public abstract SoundEvent pressurePlateClickOff();
    @Shadow public abstract SoundEvent pressurePlateClickOn();
    @Shadow public abstract SoundEvent buttonClickOff();
    @Shadow public abstract SoundEvent buttonClickOn();

    /**
     * @author YourName
     * @reason Caches the hash code for BlockSetType to improve performance.
     * The default record implementation recalculates it on every call, which is expensive.
     */
    @Overwrite
    public final int hashCode() {
        // If the hash code has not been calculated yet, calculate and cache it.
        if (!this.isHashCodeCalculated) {
            // This logic mimics the standard hash code implementation for records.
            this.cachedHashCode = Objects.hash(
                    this.name(),
                    this.canOpenByHand(),
                    this.canOpenByWindCharge(),
                    this.canButtonBeActivatedByArrows(),
                    this.pressurePlateSensitivity(),
                    this.soundType(),
                    this.doorClose(),
                    this.doorOpen(),
                    this.trapdoorClose(),
                    this.trapdoorOpen(),
                    this.pressurePlateClickOff(),
                    this.pressurePlateClickOn(),
                    this.buttonClickOff(),
                    this.buttonClickOn()
            );
            this.isHashCodeCalculated = true;
        }
        // Return the cached value on all subsequent calls.
        return this.cachedHashCode;
    }
}