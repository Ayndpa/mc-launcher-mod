package com.mclauncher.mod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 游戏内提示框（Toast）：右上角滑入的消息通知，用于替代散落在界面各处的 status/error 文本。
 * 消息自动消退，鼠标悬停可暂留。与原版 Toast 不冲突（原版 Toast 排在右上更靠上的位置，
 * 本实现从其下方开始排布，避免遮挡成就/通知类弹窗）。
 */
public final class Toasts {
    public enum Level {
        INFO(0xFF3B82F6, 0xFFFFFFFF),
        SUCCESS(0xFF22C55E, 0xFFFFFFFF),
        WARN(0xFFF59E0B, 0xFF1F2937),
        ERROR(0xFFEF4444, 0xFFFFFFFF);

        final int accent;
        final int text;

        Level(int accent, int text) {
            this.accent = accent;
            this.text = text;
        }
    }

    private static final long DEFAULT_STAY_MS = 4000;
    private static final long HOVER_STAY_MS = 1000;
    private static final int WIDTH = 220;
    private static final int MAX_VISIBLE = 6;
    private static final int TOP_OFFSET = 28;
    private static final int GAP = 4;

    private static final Deque<Toast> QUEUE = new ArrayDeque<>();
    private static final List<Toast> VISIBLE = new ArrayList<>();

    private Toasts() {
    }

    public static void info(String msg) {
        push(Level.INFO, msg);
    }

    public static void success(String msg) {
        push(Level.SUCCESS, msg);
    }

    public static void warn(String msg) {
        push(Level.WARN, msg);
    }

    public static void error(String msg) {
        push(Level.ERROR, msg);
    }

    private static void push(Level level, String msg) {
        synchronized (Toasts.class) {
            while (QUEUE.size() >= 32) {
                QUEUE.pollFirst();
            }
            QUEUE.addLast(new Toast(level, msg, System.currentTimeMillis()));
        }
    }

    /** 每帧调用（各 Screen 的 extractRenderState / render 里），负责更新并绘制 */
    public static void draw(GuiGraphicsExtractor g, float delta) {
        synchronized (Toasts.class) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            int mouseX = (int) mc.mouseHandler.xpos();
            int mouseY = (int) mc.mouseHandler.ypos();
            long now = System.currentTimeMillis();
            double scale = mc.getWindow().getGuiScale();
            int mx = (int) (mouseX / scale);
            int my = (int) (mouseY / scale);

            while (VISIBLE.size() < MAX_VISIBLE && !QUEUE.isEmpty()) {
                VISIBLE.addLast(QUEUE.pollFirst());
            }

            Iterator<Toast> it = VISIBLE.iterator();
            int idx = 0;
            while (it.hasNext()) {
                Toast t = it.next();
                int h = t.height(font);
                int y = TOP_OFFSET + idx * (h + GAP);
                int x = mc.getWindow().getGuiScaledWidth() - WIDTH - 6;

                boolean hovered = mx >= x && mx <= x + WIDTH && my >= y && my <= y + h;
                long stay = (hovered ? HOVER_STAY_MS : DEFAULT_STAY_MS);
                boolean expired = now - t.born > stay;
                if (expired) {
                    it.remove();
                    continue;
                }

                // 进出滑动动画
                long age = now - t.born;
                long remain = stay - age;
                int slide = 0;
                if (age < 200) {
                    slide = (int) ((1.0 - age / 200.0) * WIDTH);
                } else if (remain < 200) {
                    slide = (int) ((1.0 - remain / 200.0) * WIDTH);
                }
                int dx = x + slide;

                // 背景
                g.fill(dx, y, dx + WIDTH, y + h, 0xF0181820);
                g.fill(dx, y, dx + 3, y + h, t.level.accent);
                g.fill(dx + WIDTH - 1, y, dx + WIDTH, y + h, 0xFF2A2F3A);

                // 文本（自动换行）
                List<String> lines = wrap(font, t.msg, WIDTH - 12);
                int ty = y + 4;
                for (String line : lines) {
                    g.text(font, Component.literal(line), dx + 8, ty, t.level.text);
                    ty += font.lineHeight;
                }
                idx++;
            }
        }
    }

    private static List<String> wrap(Font font, String s, int maxWidth) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ")) {
                String test = cur.isEmpty() ? word : cur + " " + word;
                if (font.width(test) > maxWidth && !cur.isEmpty()) {
                    out.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(test);
                }
            }
            out.add(cur.toString());
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    private record Toast(Level level, String msg, long born) {
        int height(Font font) {
            return 8 + wrap(font, msg, WIDTH - 12).size() * font.lineHeight;
        }
    }
}
