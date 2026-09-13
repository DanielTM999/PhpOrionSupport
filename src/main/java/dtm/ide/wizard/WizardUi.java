package dtm.ide.wizard;

import dtm.ide.ui.PillButtons;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.configs.UiTokens;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;

final class WizardUi {

    static final int FIELD_HEIGHT = PillButtons.FIELD_HEIGHT;

    private WizardUi() {
    }

    static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    static JButton primary(String text, Icon icon) {
        return PillButtons.primary(text, icon);
    }

    static JButton secondary(String text, Icon icon) {
        return PillButtons.secondary(text, icon);
    }

    static JButton ghost(String text, Icon icon) {
        return PillButtons.ghost(text, icon);
    }

    static JButton iconAction(Icon icon, String tooltip) {
        return PillButtons.iconAction(icon, tooltip);
    }

    static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UiTokens.muted());
        label.setFont(UiTokens.fontSmall());
        return label;
    }

    static JLabel title(String text, float size) {
        JLabel label = new JLabel(text);
        label.setForeground(UiTokens.foreground());
        label.setFont(UiTokens.fontBold().deriveFont(size));
        return label;
    }

    static <T extends JComponent> T sized(T component) {
        component.putClientProperty("JComponent.roundRect", false);
        component.setPreferredSize(new Dimension(UiTokens.scale(160), UiTokens.scale(FIELD_HEIGHT)));
        component.setMinimumSize(new Dimension(UiTokens.scale(90), UiTokens.scale(FIELD_HEIGHT)));
        return component;
    }

    static CardPanel section(String title, String subtitle, JComponent content) {
        CardPanel card = new CardPanel(title, subtitle);
        card.setVariant(CardPanel.Variant.FILLED);
        card.setArc(UiTokens.radius(UiTokens.Radius.MD));
        card.setPadding(new Insets(UiTokens.space(3), UiTokens.space(3),
                UiTokens.space(3), UiTokens.space(3)));
        card.setContent(content);
        return card;
    }

    static JLabel summaryKey(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UiTokens.muted());
        label.setFont(UiTokens.fontSmall());
        return label;
    }

    static JLabel summaryValue(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UiTokens.foreground());
        label.setFont(UiTokens.font().deriveFont(Font.BOLD));
        return label;
    }
}
