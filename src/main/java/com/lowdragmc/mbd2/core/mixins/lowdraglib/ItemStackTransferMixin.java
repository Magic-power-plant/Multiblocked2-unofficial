package com.lowdragmc.mbd2.core.mixins.lowdraglib;

import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.item.ItemTransferHelper;
import com.lowdragmc.mbd2.utils.ItemStackTransferLimits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nonnull;

/**
 * Extends LDLib item-transfer storage behavior for oversized virtual stacks.
 *
 * <p>Slot capacity scales with each item's native stack size, including a native limit of one.
 * Extraction uses requested item counts. Persistence preserves existing counts without loss,
 * and mutations still use LDLib's slot limits and change notifications. Serialized stacks keep
 * vanilla's byte count field valid and store the real count in {@code CountInt} for round-tripping.</p>
 */
@Mixin(ItemStackTransfer.class)
public abstract class ItemStackTransferMixin {
    @Shadow(remap = false)
    protected abstract void onLoad();

    /**
     * Extracts the requested item count without applying the storage capacity formula again.
     *
     * @param slot          slot index in the LDLib transfer
     * @param amount        requested item count
     * @param simulate      whether to compute the result without changing storage
     * @param notifyChanges whether to fire LDLib content-change callbacks after mutation
     * @return extracted stack, or {@link ItemStack#EMPTY} when nothing can be extracted
     * @author pingsu
     * @reason Preserve oversized storage semantics for MBD machine traits.
     */
    @Overwrite(remap = false)
    @Nonnull
    public ItemStack extractItem(int slot, int amount, boolean simulate, boolean notifyChanges) {
        if (amount <= 0)
            return ItemStack.EMPTY;

        ItemStackTransfer self = (ItemStackTransfer) (Object) this;
        int slots = self.getSlots();
        if (slot < 0 || slot >= slots) {
            throw new RuntimeException("Slot " + slot + " not in valid range - [0," + slots + ")");
        }

        ItemStack existing = self.getStackInSlot(slot);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int toExtract = amount;

        if (existing.getCount() <= toExtract) {
            if (!simulate) {
                self.setStackInSlot(slot, ItemStack.EMPTY);
                if (notifyChanges) {
                    self.onContentsChanged(slot);
                }
                return existing;
            } else {
                return existing.copy();
            }
        } else {
            if (!simulate) {
                ItemStack newStack = ItemTransferHelper.copyStackWithSize(existing, existing.getCount() - toExtract);
                self.setStackInSlot(slot, newStack);
                if (notifyChanges) {
                    self.onContentsChanged(slot);
                }
            }
            return ItemTransferHelper.copyStackWithSize(existing, toExtract);
        }
    }

    /**
     * Scales the configured capacity by the candidate item's native stack size, rounding down.
     *
     * @param slot  slot index
     * @param stack stack being inserted
     * @return non-negative capacity measured in items of the candidate type
     * @author pingsu
     * @reason Apply proportional capacity consistently to machine, recipe and pipe insertion.
     */
    @Overwrite(remap = false)
    protected int getStackLimit(int slot, @Nonnull ItemStack stack) {
        ItemStackTransfer self = (ItemStackTransfer) (Object) this;
        return ItemStackTransferLimits.effectiveStackLimit(self.getSlotLimit(slot), stack.getMaxStackSize());
    }

    /**
     * Serializes oversized item counts with an auxiliary integer count tag.
     *
     * @return NBT payload containing all non-empty transfer slots
     * @author pingsu
     * @reason Preserve large stack counts during NBT serialization.
     */
    @Overwrite(remap = false)
    public CompoundTag serializeNBT() {
        ItemStackTransfer self = (ItemStackTransfer) (Object) this;
        ListTag itemList = new ListTag();

        for (int i = 0; i < self.getSlots(); i++) {
            ItemStack stack = self.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }

            CompoundTag itemTag = new CompoundTag();
            itemTag.putInt("Slot", i);

            ItemStack serializedStack = stack.copy();
            serializedStack.setCount(Math.min(Math.max(stack.getCount(), 1), 127));
            serializedStack.save(itemTag);
            itemTag.putInt("CountInt", stack.getCount());

            itemList.add(itemTag);
        }

        CompoundTag nbt = new CompoundTag();
        nbt.put("Items", itemList);
        nbt.putInt("Size", self.getSlots());
        return nbt;
    }

    /**
     * Restores oversized item counts from {@code CountInt} while accepting older payloads.
     *
     * @param nbt serialized LDLib transfer payload
     * @author pingsu
     * @reason Restore large stack counts from custom NBT field while keeping backward compatibility.
     */
    @Overwrite(remap = false)
    public void deserializeNBT(CompoundTag nbt) {
        ItemStackTransfer self = (ItemStackTransfer) (Object) this;
        self.setSize(nbt.contains("Size", Tag.TAG_INT) ? nbt.getInt("Size") : self.getSlots());

        ListTag itemList = nbt.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < itemList.size(); i++) {
            CompoundTag itemTag = itemList.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot < 0 || slot >= self.getSlots()) {
                continue;
            }

            ItemStack stack = ItemStack.of(itemTag);
            if (!stack.isEmpty() && itemTag.contains("CountInt", Tag.TAG_INT)) {
                stack.setCount(itemTag.getInt("CountInt"));
            }
            self.setStackInSlot(slot, stack);
        }

        onLoad();
    }
}
