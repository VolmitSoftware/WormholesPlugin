package com.volmit.wormholes.inventory;

import org.bukkit.inventory.ItemStack;

import com.volmit.wormholes.util.Callback;
import com.volmit.wormholes.util.GList;
import com.volmit.wormholes.util.MaterialBlock;

public interface Element
{
	public MaterialBlock getMaterial();

	public Element setMaterial(MaterialBlock b);

	public boolean isEnchanted();

	public Element setEnchanted(boolean enchanted);

	public String getId();

	public String getName();

	public Element setProgress(double progress);

	public double getProgress();

	public short getEffectiveDurability();

	public Element setCount(int c);

	public int getCount();

	public ItemStack computeItemStack();

	public Element setBackground(boolean bg);

	public boolean isBackgrond();

	public Element setName(String name);

	public Element addLore(String loreLine);

	public GList<String> getLore();

	public Element call(ElementEvent event, Element context);

	public Element onLeftClick(Callback<Element> clicked);

	public Element onRightClick(Callback<Element> clicked);

	public Element onShiftLeftClick(Callback<Element> clicked);

	public Element onShiftRightClick(Callback<Element> clicked);

	public Element onDraggedInto(Callback<Element> into);

	public Element onOtherDraggedInto(Callback<Element> other);
}
