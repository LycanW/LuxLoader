package dev.luxloader.mc.ui;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.plugin.PipelineInfo;
import dev.luxloader.api.plugin.RenderDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Select a plugin first; Apply switches pipelines at a frame boundary. */
public class LuxLoaderPipelinesScreen extends Screen {

    private final Screen parent;
    private final RenderDriver driver;
    private final Map<GpuId, Map<String, Object>> pendingOptions = new HashMap<>();
    private GpuId selected;
    private GpuId applied;
    private GpuId requestedChoice;
    private boolean watchingChoice;
    private boolean successShown;
    private PipelineList list;
    private List<PipelineInfo> shown = List.of();
    private Button settingsButton;
    private Button copyInfoButton;
    private String copyInfo;
    private volatile String notice = tr("Select a plugin and click Apply; disabling plugins restores vanilla rendering");

    public LuxLoaderPipelinesScreen(Screen parent, RenderDriver driver) {
        super(Component.literal(tr("LuxLoader Plugins")));
        this.parent = parent;
        this.driver = driver;
        this.applied = driver == null ? null : driver.configuredPipelineId().orElse(null);
        this.selected = applied;
    }

    private List<PipelineInfo> pipelines() {
        if (driver == null) {
            return List.of();
        }
        try {
            List<PipelineInfo> all = new ArrayList<>(driver.pipelines());
            all.sort(Comparator.comparing(PipelineInfo::displayName, String.CASE_INSENSITIVE_ORDER));
            return all;
        } catch (RuntimeException e) {
            notice = tr("Could not read plugin list: ") + e.getMessage();
            return List.of();
        }
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(this.width - 30, 540);
        int top = 43;
        int listHeight = Math.max(50, this.height - top - 76);
        list = new PipelineList(this.minecraft, panelWidth, listHeight, top);
        list.setX((this.width - panelWidth) / 2);
        list.add(null); // The first entry always disables plugin rendering.
        shown = pipelines();
        for (PipelineInfo info : shown) {
            list.add(info);
        }
        for (PipelineEntry entry : list.children()) {
            if ((entry.info == null && selected == null)
                    || (entry.info != null && entry.info.id().equals(selected))) {
                list.setSelected(entry);
                break;
            }
        }
        addRenderableWidget(list);

        int gap = 4;
        int[] widths = {136, 76, 76, 76};
        int total = widths[0] + widths[1] + widths[2] + widths[3] + gap * 3;
        int x = (this.width - total) / 2;
        int y = this.height - 30;

        Button folder = Button.builder(Component.literal(tr("Open Plugin Folder")), b -> openFolder())
                .bounds(x, y, widths[0], 20)
                .tooltip(Tooltip.create(Component.literal(tr("Open luxloader/pipelines/; add a plugin and click Apply to scan again"))))
                .build();
        folder.active = driver != null && driver.pipelineDirectory().isPresent();
        addRenderableWidget(folder);
        x += widths[0] + gap;

        Button apply = Button.builder(Component.literal(tr("Apply")), b -> applyChoice())
                .bounds(x, y, widths[1], 20).build();
        apply.active = driver != null;
        addRenderableWidget(apply);
        x += widths[1] + gap;

        settingsButton = Button.builder(Component.literal(tr("Settings")), b -> openSettings())
                .bounds(x, y, widths[2], 20)
                .tooltip(Tooltip.create(Component.literal(tr("Edit the selected plugin settings; changes take effect after Apply"))))
                .build();
        refreshSettingsButton();
        addRenderableWidget(settingsButton);
        x += widths[2] + gap;

        addRenderableWidget(Button.builder(Component.literal(tr("Done")), b -> {
                    if (!Objects.equals(selected, applied) || hasPendingOptions()) {
                        if (!applyChoice()) {
                            return;
                        }
                    }
                    onClose();
                }).bounds(x, y, widths[3], 20).build());

        Button diagnostics = Button.builder(Component.literal(tr("Copy Diagnostics")), b -> copyDiagnostics())
                .bounds(this.width / 2 - 110, this.height - 57, 96, 20)
                .tooltip(Tooltip.create(Component.literal(tr("Copy and save the full report, including per-stage CPU/GPU timings")))).build();
        diagnostics.active = driver != null;
        addRenderableWidget(diagnostics);
        copyInfoButton = Button.builder(Component.literal(tr("Copy Error")), b -> copyFailureInfo())
                .bounds(this.width / 2 - 10, this.height - 57, 118, 20)
                .build();
        addRenderableWidget(copyInfoButton);
        refreshCopyInfoButton();
    }

    @Override
    public void tick() {
        super.tick();
        if (list == null) {
            return;
        }
        List<PipelineInfo> current = pipelines();
        updateChoiceStatus(current);
        refreshCopyInfoButton();
        if (shown.equals(current)) {
            return;
        }
        shown = current;
        List<PipelineEntry> entries = new ArrayList<>(current.size() + 1);
        entries.add(new PipelineEntry(null));
        for (PipelineInfo info : current) {
            entries.add(new PipelineEntry(info));
        }
        list.replaceEntries(entries);
        for (PipelineEntry entry : entries) {
            if ((entry.info == null && selected == null)
                    || (entry.info != null && entry.info.id().equals(selected))) {
                list.setSelected(entry);
                break;
            }
        }
        refreshSettingsButton();
    }

    private void updateChoiceStatus(List<PipelineInfo> current) {
        if (!watchingChoice || driver == null) {
            return;
        }
        GpuId configured = driver.configuredPipelineId().orElse(null);
        if (!Objects.equals(configured, requestedChoice)) {
            PipelineInfo failed = current.stream()
                    .filter(info -> info.id().equals(requestedChoice))
                    .findFirst().orElse(null);
            copyInfo = failureInfo(failed, requestedChoice);
            notice = tr("Switch failed; vanilla rendering restored. Use Copy Error for details");
            watchingChoice = false;
        } else if (!successShown
                && Objects.equals(driver.activePipelineId().orElse(null), requestedChoice)) {
            notice = requestedChoice == null ? tr("Plugin rendering disabled") : tr("Plugin enabled");
            successShown = true;
        }
    }

    private static String failureInfo(PipelineInfo info, GpuId id) {
        return tr("LuxLoader pipeline switch failed\nPipeline: ") + id + "\n\n"
                + (info == null || info.detail().isBlank()
                ? tr("No exception was recorded. See the game's logs/latest.log.") : info.detail());
    }

    private static String stackTrace(Throwable cause) {
        StringWriter text = new StringWriter();
        cause.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private void refreshCopyInfoButton() {
        if (copyInfoButton != null) {
            copyInfoButton.visible = copyInfo != null && !copyInfo.isBlank();
            copyInfoButton.active = copyInfoButton.visible;
        }
    }

    private void copyDiagnostics() {
        if (driver == null) return;
        try {
            Path report = driver.diagnostics().exportReportToFile();
            LuxLoaderClipboard.copy(Files.readString(report));
            notice = tr("Diagnostics copied and saved to luxloader/reports");
        } catch (java.io.IOException | RuntimeException error) {
            copyInfo = stackTrace(error);
            refreshCopyInfoButton();
            notice = tr("Could not export diagnostics; error details are available to copy");
        }
    }

    private void copyFailureInfo() {
        if (copyInfo != null && this.minecraft != null) {
            try {
                LuxLoaderClipboard.copy(copyInfo);
                notice = tr("Full error details copied to clipboard");
            } catch (RuntimeException error) {
                notice = tr("Copy failed: ") + error.getMessage();
            }
        }
    }

    private void refreshSettingsButton() {
        if (settingsButton != null) {
            settingsButton.active = driver != null && selected != null
                    && driver.pipelineOptionsSchema(selected)
                    .map(schema -> !schema.options().isEmpty()).orElse(false);
        }
    }

    private boolean hasPendingOptions() {
        return selected != null && !pendingOptions.getOrDefault(selected, Map.of()).isEmpty();
    }

    Map<String, Object> pendingOptions(GpuId id) {
        return pendingOptions.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
    }

    void applySettings(GpuId pipeline) {
        selected = pipeline;
        applyChoice();
        minecraft.setScreenAndShow(this);
    }

    private boolean applyChoice() {
        if (driver == null) {
            notice = tr("Loader is not ready");
            return false;
        }
        Map<String, Object> changes = selected == null
                ? Map.of() : Map.copyOf(pendingOptions.getOrDefault(selected, Map.of()));
        try {
            if (!driver.applyPipelineChoice(selected, changes)) {
                copyInfo = tr("LuxLoader rejected the pipeline selection or settings.\nPipeline: ") + selected
                        + tr("\nSee the game's logs/latest.log.");
                refreshCopyInfoButton();
                notice = tr("Apply failed; use Copy Error for details");
                return false;
            }
            applied = selected;
            requestedChoice = selected;
            watchingChoice = true;
            successShown = false;
            copyInfo = null;
            refreshCopyInfoButton();
            if (selected != null) {
                pendingOptions.remove(selected);
            }
            notice = tr("Selection saved; waiting for the pipeline switch");
            return true;
        } catch (RuntimeException e) {
            copyInfo = tr("Exception applying LuxLoader pipeline\nPipeline: ") + selected + "\n\n"
                    + stackTrace(e);
            refreshCopyInfoButton();
            notice = tr("Apply failed; use Copy Error for details");
            return false;
        }
    }

    private void openSettings() {
        if (driver == null || selected == null) {
            return;
        }
        driver.pipelineOptionsSchema(selected).ifPresent(schema ->
                driver.pipelineOptions(selected).ifPresent(view ->
                        this.minecraft.setScreenAndShow(new LuxLoaderSettingsScreen(
                                this, selected, schema, view, pendingOptions(selected)))));
    }

    private void openFolder() {
        if (driver == null) {
            return;
        }
        Path folder = driver.pipelineDirectory().orElse(null);
        if (folder == null) {
            return;
        }
        try {
            Files.createDirectories(folder);
            if (!Desktop.isDesktopSupported()) {
                notice = tr("Plugin directory: ") + folder;
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    Desktop.getDesktop().open(folder.toFile());
                } catch (Exception e) {
                    notice = tr("Could not open directory: ") + folder;
                }
            }, "luxloader-open-plugin-folder");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            notice = tr("Could not open directory: ") + e.getMessage();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        int maxNoticeWidth = Math.max(20, this.width - 32);
        String displayed = this.font.width(notice) <= maxNoticeWidth ? notice
                : this.font.plainSubstrByWidth(notice,
                        Math.max(0, maxNoticeWidth - this.font.width("…"))) + "…";
        graphics.centeredText(this.font, displayed, this.width / 2,
                this.height - 71, 0xFFB8B8B8);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private final class PipelineList extends ObjectSelectionList<PipelineEntry> {
        PipelineList(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, 38);
        }

        void add(PipelineInfo info) {
            addEntry(new PipelineEntry(info));
        }

        @Override
        public int getRowWidth() {
            return Math.min(getWidth() - 24, 500);
        }
    }

    private final class PipelineEntry extends ObjectSelectionList.Entry<PipelineEntry> {
        private final PipelineInfo info;

        PipelineEntry(PipelineInfo info) {
            this.info = info;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            int left = getContentX();
            int top = getContentY();
            int right = getContentRight();
            boolean chosen = info == null ? selected == null : info.id().equals(selected);
            graphics.fill(left, top, right, top + 32,
                    chosen ? 0x884F7A63 : hovered ? 0x663F3F3F : 0x44000000);
            String name = info == null ? tr("Vanilla: Disable Plugins") : tr(info.displayName());
            String state = info == null ? tr("Use vanilla game rendering") : stateText(info);
            graphics.text(font, name, left + 8, top + 4, 0xFFFFFFFF);
            graphics.text(font, state, left + 8, top + 18, 0xFFB4B4B4);
            if (hovered && info != null) {
                graphics.setTooltipForNextFrame(Component.literal(tooltipFor(info)), mouseX, mouseY);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.buttonInfo().button() != 1 || (info != null && info.state() == PipelineInfo.State.ACTIVATING)) {
                return false;
            }
            selected = info == null ? null : info.id();
            list.setSelected(this);
            refreshSettingsButton();
            copyInfo = info != null && (info.state() == PipelineInfo.State.FAILED
                    || info.state() == PipelineInfo.State.REJECTED || info.state() == PipelineInfo.State.DISABLED)
                    ? failureInfo(info, info.id()) : null;
            refreshCopyInfoButton();
            notice = copyInfo == null ? tr("Click Apply to enable the selected mode")
                    : tr("This plugin failed previously; copy the error or apply again to retry");
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(info == null ? tr("Vanilla: Disable Plugins") : tr(info.displayName()));
        }
    }

    private static boolean unusable(PipelineInfo info) {
        return info.state() == PipelineInfo.State.REJECTED
                || info.state() == PipelineInfo.State.ACTIVATING;
    }

    private static String stateText(PipelineInfo info) {
        return switch (info.state()) {
            case ACTIVE -> tr("Active");
            case REGISTERED -> tr("Available");
            case ACTIVATING -> tr("Activating");
            case REJECTED -> tr("Device or dependency requirements not met");
            case FAILED -> tr("Activation or execution failed; retry is available");
            case DISABLED -> tr("Disabled; select to enable again");
            case CLOSED -> tr("Available, currently inactive");
        };
    }

    private static String tooltipFor(PipelineInfo info) {
        StringBuilder text = new StringBuilder(info.id().toString());
        if (!info.provider().isBlank()) {
            text.append(tr("\nProvider: ")).append(info.provider());
        }
        if (info.state() == PipelineInfo.State.FAILED) {
            text.append(tr("\nSelect to copy full error details"));
        } else if (!info.detail().isBlank()) {
            String detail = info.detail();
            text.append("\n").append(detail.length() > 100
                    ? detail.substring(0, 100) + "…" : detail);
        }
        return text.toString();
    }
}
