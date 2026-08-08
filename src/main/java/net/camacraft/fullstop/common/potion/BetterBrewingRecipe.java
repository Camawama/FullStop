package net.camacraft.fullstop.common.potion;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.common.brewing.IBrewingRecipe;

public class BetterBrewingRecipe implements IBrewingRecipe {
    private final Potion input;
    private final Item ingredient;
    private final Potion output;

    public BetterBrewingRecipe(Potion input, Item ingredient, Potion output) {
        this.input = input;
        this.ingredient = ingredient;
        this.output = output;
    }

    @Override
    public boolean isInput(ItemStack input) {
        // PotionItem covers splash/lingering subclasses. Matching on the potion
        // NBT alone accepted anything carrying it — one echo shard could
        // "brew" a single tipped arrow straight to the upgraded arrow.
        return input.getItem() instanceof PotionItem && PotionUtils.getPotion(input) == this.input;
    }

    @Override
    public boolean isIngredient(ItemStack ingredient) {
        return ingredient.getItem() == this.ingredient;
    }

    @Override
    public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
        if (!isInput(input) || !isIngredient(ingredient)) {
            return ItemStack.EMPTY;
        }

        ItemStack itemStack = new ItemStack(input.getItem());
        // Preserve the input's custom NBT (names, etc.) before stamping the new potion.
        if (input.getTag() != null) {
            itemStack.setTag(input.getTag().copy());
        }
        PotionUtils.setPotion(itemStack, output);
        return itemStack;
    }
}
