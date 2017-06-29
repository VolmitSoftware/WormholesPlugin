/**
 * This file is part of PacketWrapper.
 * Copyright (C) 2012-2015 Kristian S. Strangeland
 * Copyright (C) 2015 dmulloy2
 *
 * PacketWrapper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PacketWrapper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with PacketWrapper.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.volmit.wormholes.wrapper;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;

public class WrapperPlayServerUpdateHealth18 extends AbstractPacket18 {
    public static final PacketType TYPE = PacketType.Play.Server.UPDATE_HEALTH;
    
    public WrapperPlayServerUpdateHealth18() {
        super(new PacketContainer(TYPE), TYPE);
        handle.getModifier().writeDefaults();
    }
    
    public WrapperPlayServerUpdateHealth18(PacketContainer packet) {
        super(packet, TYPE);
    }
    
    /**
     * Retrieve Health.
     * <p>
     * Notes: 0 or less = dead, 20 = full HP
     * @return The current Health
     */
    public float getHealth() {
        return handle.getFloat().read(0);
    }
    
    /**
     * Set Health.
     * @param value - new value.
     */
    public void setHealth(float value) {
        handle.getFloat().write(0, value);
    }
    
    /**
     * Retrieve Food.
     * <p>
     * Notes: 0 - 20
     * @return The current Food
     */
    public int getFood() {
        return handle.getIntegers().read(0);
    }
    
    /**
     * Set Food.
     * @param value - new value.
     */
    public void setFood(int value) {
        handle.getIntegers().write(0, value);
    }
    
    /**
     * Retrieve Food Saturation.
     * <p>
     * Notes: seems to vary from 0.0 to 5.0 in integer increments
     * @return The current Food Saturation
     */
    public float getFoodSaturation() {
        return handle.getFloat().read(1);
    }
    
    /**
     * Set Food Saturation.
     * @param value - new value.
     */
    public void setFoodSaturation(float value) {
        handle.getFloat().write(1, value);
    }
    
}
