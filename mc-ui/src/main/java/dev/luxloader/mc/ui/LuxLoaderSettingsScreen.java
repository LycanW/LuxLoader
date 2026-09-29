package dev.luxloader.mc.ui;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.config.ConfigOption;
import dev.luxloader.api.config.ConfigSchema;
import dev.luxloader.api.config.ConfigView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stage plugin settings here; Apply saves them and switches the pipeline. */
final class LuxLoaderSettingsScreen extends Screen {

    private final LuxLoaderPipelinesScreen parent;
    private final GpuId pipeline;
    private final ConfigSchema schema;
    private final ConfigView values;
    private final Map<String, Object> pending;

    LuxLoaderSettingsScreen(LuxLoaderPipelinesScreen parent, GpuId pipeline,
                            ConfigSchema schema, ConfigView values, Map<String, Object> pending) {
        super(Component.literal(tr("Plugin Settings")));
        this.parent = parent;
        this.pipeline = pipeline;
        this.schema = schema;
        this.values = values;
        this.pending = pending;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(width - 30, 540);
        SettingsList list = new SettingsList(minecraft, panelWidth,
                Math.max(50, height - 115), 50);
        list.setX((width - panelWidth) / 2);
        for (String group : schema.groups()) {
            List<ConfigOption<?>> options = schema.inGroup(group).stream()
                    .filter(ConfigOption::isVisible).toList();
            if (options.isEmpty()) {
                continue;
            }
            list.addGroup(group);
            for (ConfigOption<?> option : options) {
                list.addOption(option);
            }
        }
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(Component.literal(tr("Apply")), b -> parent.applySettings(pipeline))
                .bounds(width / 2 - 142, height - 29, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal(tr("Back to Plugins")), b -> onClose())
                .bounds(width / 2 - 28, height - 29, 170, 20).build());
    }

    private Object current(ConfigOption<?> option) {
        return pending.containsKey(option.path())
                ? pending.get(option.path()) : option.read(values);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, tr("Plugin Settings - ") + pipeline, width / 2, 14, 0xFFFFFFFF);
        graphics.centeredText(font, tr("Apply saves settings and switches pipelines; return to selection for the result"),
                width / 2, height - 53, 0xFFB8B8B8);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private final class SettingsList extends ObjectSelectionList<SettingsEntry> {
        SettingsList(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, 34);
        }

        void addGroup(String name) {
            addEntry(new GroupEntry(name), 22);
        }

        void addOption(ConfigOption<?> option) {
            addEntry(new OptionEntry(option));
        }

        @Override
        public int getRowWidth() {
            return Math.min(getWidth() - 24, 500);
        }
    }

    private abstract class SettingsEntry extends ObjectSelectionList.Entry<SettingsEntry> {
        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return false;
        }
    }

    private final class GroupEntry extends SettingsEntry {
        private final String name;

        GroupEntry(String name) {
            this.name = name;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            graphics.text(font, tr(name), getContentX() + 7, getContentY() + 5, 0xFF94CBAA);
        }

        @Override
        public Component getNarration() {
            return Component.literal(tr(name));
        }
    }

    private final class OptionEntry extends SettingsEntry {
        private final ConfigOption<?> option;

        OptionEntry(ConfigOption<?> option) {
            this.option = option;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            int left = getContentX();
            int top = getContentY();
            int right = getContentRight();
            graphics.fill(left, top, right, top + 29,
                    hovered ? 0x663F3F3F : 0x44000000);
            graphics.text(font, tr(option.displayName()), left + 8, top + 6, 0xFFFFFFFF);
            String text = displayValue();
            if (option instanceof ConfigOption.Numeric) {
                graphics.text(font, "−", right - 82, top + 6, 0xFFFFFFFF);
                graphics.text(font, "+", right - 16, top + 6, 0xFFFFFFFF);
                graphics.centeredText(font, text, right - 47, top + 6, 0xFFB8D5C1);
            } else {
                graphics.text(font, text, right - font.width(text) - 9, top + 6, 0xFFB8D5C1);
            }
            if (hovered && !option.description().isBlank()) {
                graphics.setTooltipForNextFrame(Component.literal(tr(option.description())), mouseX, mouseY);
            }
        }

        private String displayValue() {
            Object value = current(option);
            if (value instanceof Boolean b) {
                return b ? tr("On") : tr("Off");
            }
            if (value instanceof Number n) {
                return option instanceof ConfigOption.Numeric numeric && numeric.isInteger()
                        ? String.valueOf(n.intValue())
                        : String.format(Locale.ROOT, "%.2f", n.doubleValue());
            }
            String text = value instanceof List<?> list
                    ? String.join(", ", list.stream().map(String::valueOf).toList())
                    : String.valueOf(value);
            return text.length() > 24 ? text.substring(0, 21) + "..." : text;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.buttonInfo().button() != 1) {
                return false;
            }
            Object value = current(option);
            if (option instanceof ConfigOption.Bool) {
                pending.put(option.path(), !Boolean.TRUE.equals(value));
            } else if (option instanceof ConfigOption.EnumOption<?> enumeration) {
                List<String> choices = enumeration.allowedKeys();
                if (!choices.isEmpty()) {
                    int next = (choices.indexOf(String.valueOf(value)) + 1) % choices.size();
                    pending.put(option.path(), choices.get(next));
                }
            } else if (option instanceof ConfigOption.Numeric numeric) {
                double step = numeric.step() > 0 ? numeric.step()
                        : numeric.isInteger() ? 1d : (numeric.max() - numeric.min()) / 20d;
                double direction = event.x() < getContentRight() - 47 ? -1d : 1d;
                double changed = ((Number) value).doubleValue() + step * direction;
                pending.put(option.path(), numeric.coerce(changed));
            } else if (option instanceof ConfigOption.Text
                    || option instanceof ConfigOption.ListOption) {
                minecraft.setScreenAndShow(new TextOptionScreen(LuxLoaderSettingsScreen.this,
                        option, value instanceof List<?> list
                                ? String.join(", ", list.stream().map(String::valueOf).toList())
                                : String.valueOf(value)));
            }
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(tr(option.displayName()) + ": " + displayValue());
        }
    }

    private final class TextOptionScreen extends Screen {
        private final LuxLoaderSettingsScreen back;
        private final ConfigOption<?> option;
        private final String initial;
        private EditBox input;

        TextOptionScreen(LuxLoaderSettingsScreen back, ConfigOption<?> option, String initial) {
            super(Component.literal(tr(option.displayName())));
            this.back = back;
            this.option = option;
            this.initial = initial;
        }

        @Override
        protected void init() {
            input = new EditBox(font, width / 2 - 150, height / 2 - 10,
                    300, 20, Component.literal(tr(option.displayName())));
            input.setMaxLength(1024);
            input.setValue(initial);
            addRenderableWidget(input);
            addRenderableWidget(Button.builder(Component.literal(tr("Save")), b -> {
                        Object value = option instanceof ConfigOption.ListOption
                                && input.getValue().isBlank()
                                ? List.of() : option.coerce(input.getValue());
                        if (value != null) {
                            pending.put(option.path(), value);
                            onClose();
                        }
                    }).bounds(width / 2 - 104, height / 2 + 22, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal(tr("Cancel")), b -> onClose())
                    .bounds(width / 2 + 4, height / 2 + 22, 100, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       float partialTick) {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.centeredText(font, title, width / 2, height / 2 - 35, 0xFFFFFFFF);
        }

        @Override
        public void onClose() {
            minecraft.setScreenAndShow(back);
        }

        @Override
        public boolean isPauseScreen() {
            return true;
        }
    }
}
