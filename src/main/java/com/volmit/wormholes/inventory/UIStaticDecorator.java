package com.volmit.wormholes.inventory;

import org.bukkit.Material;

import com.volmit.wormholes.util.MaterialBlock;

public class UIStaticDecorator implements WindowDecorator
{
	private Element element;

	public UIStaticDecorator(Element element)
	{
		this.element = element == null ? new UIElement("bg").setMaterial(new MaterialBlock(Material.AIR)) : element;
	}

	@Override
	public Element onDecorateBackground(Window window, int position, int row)
	{
		return element;
	}
}
