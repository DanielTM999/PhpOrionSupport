package dtm.ide.ui;

import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class PillButtons {

    public static final int FIELD_HEIGHT = 30;

    private PillButtons() {
    }

    public static JButton primary(String text, Icon icon) {
        return new PillButton(text, icon, Tone.PRIMARY);
    }

    public static JButton secondary(String text, Icon icon) {
        return new PillButton(text, icon, Tone.OUTLINED);
    }

    public static JButton ghost(String text, Icon icon) {
        return new PillButton(text, icon, Tone.GHOST);
    }

    public static JButton iconAction(Icon icon, String tooltip) {
        JButton button = new PillButton("", icon, Tone.OUTLINED);
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(UiTokens.scale(38), UiTokens.scale(FIELD_HEIGHT)));
        return button;
    }

    private enum Tone { PRIMARY, OUTLINED, GHOST }

    private static final class PillButton extends JButton {

        private final Tone tone;
        private boolean hover;

        private PillButton(String text, Icon icon, Tone tone) {
            super(text, icon);
            this.tone = tone;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setIconTextGap(UiTokens.space(2));
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(UiTokens.font());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(UiTokens.space(2), UiTokens.space(4),
                    UiTokens.space(2), UiTokens.space(4)));
            setForeground(textColor());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(Cursor.getPredefinedCursor(
                    enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            setForeground(enabled ? textColor() : UiTokens.muted());
            repaint();
        }

        private Color textColor() {
            return tone == Tone.PRIMARY
                    ? UiTokens.onColor(UiTokens.primary())
                    : UiTokens.foreground();
        }

        private Color fillColor() {
            Color base = switch (tone) {
                case PRIMARY -> UiTokens.primary();
                case OUTLINED -> UiTokens.surfaceAlt();
                case GHOST -> UiTokens.overlay(UiTokens.foreground(), 0.06F);
            };
            if (!isEnabled()) {
                return UiTokens.disabled(base);
            }
            if (getModel().isPressed()) {
                return UiTokens.pressed(base);
            }
            return hover ? UiTokens.hover(base) : base;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = UiTokens.radius(UiTokens.Radius.SM);
            g2.setColor(fillColor());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            if (tone == Tone.OUTLINED) {
                g2.setColor(UiTokens.border());
                g2.setStroke(new BasicStroke(UiTokens.stroke()));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            if (isFocusOwner()) {
                g2.setColor(UiTokens.overlay(UiTokens.accent(), 0.75F));
                g2.setStroke(new BasicStroke(UiTokens.stroke() + 0.5F));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc, arc);
            }
            g2.dispose();
            super.paintComponent(graphics);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            preferred.height = Math.max(preferred.height, UiTokens.scale(FIELD_HEIGHT));
            return preferred;
        }
    }
}
