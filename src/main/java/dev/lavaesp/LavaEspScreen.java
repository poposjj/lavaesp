package dev.lavaesp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The settings screen, reachable from Mod Menu (via {@link LavaEspModMenu}) and from {@code /lavaesp}.
 *
 * <p>Top of the panel is a small notice board: the author line and one short line per setting, so the
 * screen explains itself without leaving the game. Below it sit the controls - the module switch, the
 * fade switch, the fade start distance and the colour (preset cycling plus a free-form AARRGGBB
 * field). Every widget writes straight into {@link LavaEspConfig} and saves.
 */
public final class LavaEspScreen extends Screen {

    private static final int PANEL_WIDTH = 260;
    private static final int HEADER_HEIGHT = 130;
    private static final int ROW = 26;
    private static final int ROWS = 7;

    private static final int[] FADE_STARTS = {10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100};
    private static final int[] PRESET_COLORS = {
            0xA000FFFF, // cyan (default)
            0xA030FF30, // green
            0xA0FF3030, // red
            0xA0FFD700, // gold
            0xA08080FF, // blue
            0xA0FFFFFF  // white
    };

    private static final int COLOR_TITLE = 0xFFFFD700;
    private static final int COLOR_AUTHOR = 0xFF9FE8FF;
    private static final int COLOR_TEXT = 0xFFDDDDDD;
    private static final int COLOR_SEPARATOR = 0x60FFFFFF;
    private static final int COLOR_BACKDROP = 0xC0101010;

    private final Screen parent;
    private Button enabledButton;
    private Button fadeButton;
    private Button fadeStartButton;
    private Button colorButton;
    private EditBox colorField;

    public LavaEspScreen(Screen parent) {
        super(Component.literal("Lava ESP v1.5.0"));
        this.parent = parent;
    }

    private int panelX() {
        return this.width / 2 - PANEL_WIDTH / 2;
    }

    private int panelY() {
        int total = HEADER_HEIGHT + ROWS * ROW + 10;
        return Math.max(12, (this.height - total) / 2);
    }

    @Override
    protected void init() {
        int x = this.panelX();
        int y = this.panelY() + HEADER_HEIGHT;

        this.enabledButton = this.addRenderableWidget(Button.builder(enabledLabel(), b -> {
            LavaEspConfig.enabled = !LavaEspConfig.enabled;
            LavaEspConfig.save();
            if (!LavaEspConfig.enabled) {
                LavaEspMod.get().invalidate();
            } else {
                LavaEspMod.get().requestReport();
            }
            b.setMessage(enabledLabel());
        }).pos(x, y).width(PANEL_WIDTH).build());

        this.fadeButton = this.addRenderableWidget(Button.builder(fadeLabel(), b -> {
            LavaEspConfig.fade = !LavaEspConfig.fade;
            LavaEspConfig.save();
            b.setMessage(fadeLabel());
        }).pos(x, y + ROW).width(PANEL_WIDTH).build());

        this.fadeStartButton = this.addRenderableWidget(Button.builder(fadeStartLabel(), b -> {
            LavaEspConfig.fadeStart = nextFadeStart(LavaEspConfig.fadeStart);
            LavaEspConfig.save();
            b.setMessage(fadeStartLabel());
        }).pos(x, y + ROW * 2).width(PANEL_WIDTH).build());

        this.colorButton = this.addRenderableWidget(Button.builder(colorLabel(), b -> {
            LavaEspConfig.color = nextColor(LavaEspConfig.color);
            LavaEspConfig.save();
            b.setMessage(colorLabel());
            if (this.colorField != null) {
                this.colorField.setValue(String.format("%08X", LavaEspConfig.color));
            }
        }).pos(x, y + ROW * 3).width(PANEL_WIDTH).build());

        this.colorField = new EditBox(this.font, x, y + ROW * 4, PANEL_WIDTH - 90, 20, Component.literal("AARRGGBB"));
        this.colorField.setMaxLength(8);
        this.colorField.setValue(String.format("%08X", LavaEspConfig.color));
        this.addRenderableWidget(this.colorField);

        this.addRenderableWidget(Button.builder(Component.literal("应用颜色"), b -> {
            Integer parsed = LavaEspConfig.tryParseColor(this.colorField.getValue());
            if (parsed != null) {
                LavaEspConfig.color = parsed;
                LavaEspConfig.save();
                this.colorButton.setMessage(colorLabel());
                this.colorField.setValue(String.format("%08X", LavaEspConfig.color));
            }
        }).pos(x + PANEL_WIDTH - 85, y + ROW * 4).width(85).build());

        this.addRenderableWidget(Button.builder(Component.literal("立即重扫"), b -> {
            LavaEspMod.get().invalidate();
            LavaEspMod.get().requestReport();
        }).pos(x, y + ROW * 5).width(PANEL_WIDTH).build());

        this.addRenderableWidget(Button.builder(Component.literal("完成"), b -> this.onClose())
                .pos(x, y + ROW * 6).width(PANEL_WIDTH).build());
    }

    /** the notice board: author line plus one short explanation per setting */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = this.panelX();
        int y = this.panelY();
        int bottom = y + HEADER_HEIGHT + ROWS * ROW + 4;
        graphics.fill(x - 8, y - 8, x + PANEL_WIDTH + 8, bottom, COLOR_BACKDROP);

        graphics.centeredText(this.font, Component.literal("Lava ESP v1.5.0"), x + PANEL_WIDTH / 2, y + 2, COLOR_TITLE);
        graphics.centeredText(this.font, Component.literal("by oioi"), x + PANEL_WIDTH / 2, y + 15, COLOR_AUTHOR);
        graphics.fill(x, y + 30, x + PANEL_WIDTH, y + 31, COLOR_SEPARATOR);

        String[] lines = {
                "用途 — Hypixel SkyBlock · 水晶矿洞钓虫",
                "开关 — 高亮所有 y≥64 的岩浆方块",
                "淡出 — 远处渐隐，避免满屏",
                "淡出起点 — 开始渐隐的距离 10~100",
                "颜色 — AARRGGBB，前两位是不透明度",
                "内部岩浆 — 六面被岩浆包住，仅 10% 浓度",
                "指令 — /lavaesp 开此界面，on / off 开关"
        };
        int lineY = y + 38;
        for (String line : lines) {
            graphics.text(this.font, Component.literal(line), x, lineY, COLOR_TEXT);
            lineY += 12;
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static Component enabledLabel() {
        return Component.literal("开关: " + (LavaEspConfig.enabled ? "开" : "关"));
    }

    private static Component fadeLabel() {
        return Component.literal("淡出: " + (LavaEspConfig.fade ? "开" : "关"));
    }

    private static Component fadeStartLabel() {
        return Component.literal("淡出起点: " + (int) LavaEspConfig.fadeStart + " 格");
    }

    private static Component colorLabel() {
        return Component.literal("颜色: " + String.format("%08X", LavaEspConfig.color));
    }

    private static double nextFadeStart(double current) {
        int value = (int) Math.round(current);
        for (int preset : FADE_STARTS) {
            if (preset > value) {
                return preset;
            }
        }
        return FADE_STARTS[0];
    }

    private static int nextColor(int current) {
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == current) {
                return PRESET_COLORS[(i + 1) % PRESET_COLORS.length];
            }
        }
        return PRESET_COLORS[0];
    }

    @Override
    public void onClose() {
        LavaEspConfig.save();
        this.minecraft.setScreenAndShow(this.parent);
    }
}
