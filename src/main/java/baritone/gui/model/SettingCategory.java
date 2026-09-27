/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.gui.model;

/**
 * Sidebar categories of the Ostinato settings screen, in display order. Pure Java: the item icon for each
 * category is resolved on the Minecraft side.
 */
public enum SettingCategory {
    MOVEMENT("Movement"),
    WATER_AIR("Water & Air"),
    MINING("Mining"),
    BUILDING("Building"),
    INVENTORY("Inventory"),
    PATHING("Pathing"),
    ELYTRA("Elytra"),
    RENDER("Render"),
    CHAT("Chat"),
    SWARM("Swarm"),
    INTERFACE("Interface"),
    ADVANCED("Advanced"),
    /** Fallback; only shown when something could not be categorized. */
    OTHER("Other");

    public final String displayName;

    SettingCategory(String displayName) {
        this.displayName = displayName;
    }
}
