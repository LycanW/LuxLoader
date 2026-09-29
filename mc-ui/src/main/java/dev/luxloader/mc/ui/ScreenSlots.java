package dev.luxloader.mc.ui;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;

/**
 * Find a free button position from the initialized screen's child rectangles. Vanilla layouts change
 * between versions, so fixed offsets can overlap controls. Ignore narrow corner icons when locating
 * the bottom of the main button column.
 */
public final class ScreenSlots {

    /** Ignore controls narrower than this when measuring the main button column. */
    private static final int ICON_WIDTH_THRESHOLD = 60;

    /** Gap between the new button and existing controls. */
    private static final int GAP = 6;

    /** Reserve bottom space for copyright text and other footer content. */
    private static final int BOTTOM_MARGIN = 28;

    private ScreenSlots() {
    }

    /**
     * Find space below the initialized screen's button column. Returns centered [x, y] coordinates for the
     * requested button dimensions.
     */
    public static int[] belowExistingWidgets(Screen screen, int buttonWidth, int buttonHeight) {
        int centerX = screen.width / 2;

        // If no controls can be measured, start near the upper quarter of the screen.
        int lowest = screen.height / 4;
        try {
            for (GuiEventListener child : screen.children()) {
                if (child == null) {
                    continue;
                }
                ScreenRectangle rectangle = child.getRectangle();
                if (rectangle == null || rectangle.width() < ICON_WIDTH_THRESHOLD) {
                    continue;
                }
                int bottom = rectangle.top() + rectangle.height();
                lowest = Math.max(lowest, bottom);
            }
        } catch (RuntimeException e) {
            // Use fallback coordinates if measurement fails so the entry remains accessible.
        }

        int y = lowest + GAP;
        int maxY = screen.height - BOTTOM_MARGIN - buttonHeight;
        if (y > maxY) {
            y = Math.max(screen.height / 4, maxY);
        }
        return new int[] {centerX - buttonWidth / 2, y};
    }
}
