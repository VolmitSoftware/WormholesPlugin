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
import com.comphenix.protocol.wrappers.WrappedChatComponent;

public class WrapperPlayServerPlayerListHeaderFooter18 extends AbstractPacket18 {
    public static final PacketType TYPE = PacketType.Play.Server.PLAYER_LIST_HEADER_FOOTER;
    
    public WrapperPlayServerPlayerListHeaderFooter18() {
        super(new PacketContainer(TYPE), TYPE);
        handle.getModifier().writeDefaults();
    }
    
    public WrapperPlayServerPlayerListHeaderFooter18(PacketContainer packet) {
        super(packet, TYPE);
    }
    
    /**
     * Retrieve Header.
     * @return The current Header
     */
    public WrappedChatComponent getHeader() {
        return handle.getChatComponents().read(0);
    }
    
    /**
     * Set Header.
     * @param value - new value.
     */
    public void setHeader(WrappedChatComponent value) {
        handle.getChatComponents().write(0, value);
    }
    
    /**
     * Retrieve Footer.
     * @return The current Footer
     */
    public WrappedChatComponent getFooter() {
        return handle.getChatComponents().read(1);
    }
    
    /**
     * Set Footer.
     * @param value - new value.
     */
    public void setFooter(WrappedChatComponent value) {
        handle.getChatComponents().write(1, value);
    }
    
}

