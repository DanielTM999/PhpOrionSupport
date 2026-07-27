package dtm.ide.navigation;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

public final class PhpNavigationPopup {
    public record Item(String snippet, String location, Runnable onActivate) {}

    private static final Color BG = new Color(0x1E1F22);
    private static final Color HEADER_BG = new Color(0x2B2D31);
    private static final Color STRIPE_BG = new Color(0x26282C);
    private static final Color SELECTION_BG = new Color(0x2F4D78);
    private static final Color TEXT = new Color(0xD7D7D7);
    private static final Color MUTED = new Color(0x8A8A8A);
    private static final Color BORDER = new Color(0x3A3D41);
    private static final Font UI_FONT = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font MONO_FONT = new Font("JetBrains Mono", Font.PLAIN, 12);
    private static JDialog current;

    private PhpNavigationPopup() {}

    public static void show(Window owner, Point screen, String headerText, List<Item> items) {
        SwingUtilities.invokeLater(() -> doShow(owner, screen, headerText, items));
    }

    private static void doShow(Window owner, Point screen, String headerText, List<Item> items) {
        if (items == null || items.isEmpty()) return;
        if (current != null) current.dispose();

        JDialog dialog = new JDialog(owner);
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(BorderFactory.createLineBorder(BORDER));

        JLabel header = new JLabel("  " + headerText);
        header.setOpaque(true);
        header.setBackground(HEADER_BG);
        header.setForeground(MUTED);
        header.setFont(UI_FONT.deriveFont(Font.BOLD, 11f));
        header.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        installDrag(dialog, header);
        root.add(header, BorderLayout.NORTH);

        DefaultListModel<Item> model = new DefaultListModel<>();
        items.forEach(model::addElement);
        JList<Item> list = new JList<>(model);
        list.setBackground(BG);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(24);
        list.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        list.setCellRenderer((ignored, value, index, selected, focused) ->
                renderRow(value, index, selected));
        list.setSelectedIndex(0);

        Runnable activate = () -> {
            Item item = list.getSelectedValue();
            dialog.dispose();
            if (item != null && item.onActivate() != null) item.onActivate().run();
        };
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(event.getPoint())) {
                    list.setSelectedIndex(index);
                    activate.run();
                }
            }
        });
        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(event.getPoint())) {
                    list.setSelectedIndex(index);
                }
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate");
        list.getActionMap().put("activate", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                activate.run();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        list.getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dialog.dispose();
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);
        applyThinScrollBar(scroll.getVerticalScrollBar());
        applyThinScrollBar(scroll.getHorizontalScrollBar());
        root.add(scroll, BorderLayout.CENTER);

        dialog.setContentPane(root);
        int rows = Math.min(Math.max(items.size(), 1), 12);
        dialog.setSize(new Dimension(560, 38 + rows * 24));
        if (screen == null) dialog.setLocationRelativeTo(owner);
        else dialog.setLocation(screen);
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                dialog.dispose();
            }
        });
        current = dialog;
        dialog.setVisible(true);
        list.requestFocusInWindow();
    }

    private static Component renderRow(Item value, int index, boolean selected) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        row.setBackground(selected ? SELECTION_BG : (index % 2 == 0 ? BG : STRIPE_BG));
        JLabel snippet = new JLabel(value == null ? "" : value.snippet());
        snippet.setFont(MONO_FONT);
        snippet.setForeground(TEXT);
        row.add(snippet, BorderLayout.CENTER);
        JLabel location = new JLabel(value == null ? "" : value.location());
        location.setFont(UI_FONT);
        location.setForeground(MUTED);
        row.add(location, BorderLayout.EAST);
        return row;
    }

    private static void applyThinScrollBar(JScrollBar bar) {
        bar.setUI(new ThinScrollBarUI());
        bar.setPreferredSize(bar.getOrientation() == JScrollBar.HORIZONTAL
                ? new Dimension(0, 8) : new Dimension(8, 0));
        bar.setUnitIncrement(16);
        bar.setOpaque(false);
    }

    private static void installDrag(JDialog dialog, JLabel handle) {
        Point[] origin = {null};
        handle.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                origin[0] = event.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                origin[0] = null;
            }
        });
        handle.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                if (origin[0] == null) return;
                Point location = dialog.getLocation();
                dialog.setLocation(location.x + event.getX() - origin[0].x,
                        location.y + event.getY() - origin[0].y);
            }
        });
    }

    private static final class ThinScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(0x5A5D62);
            trackColor = BG;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private static JButton zeroButton() {
            JButton button = new JButton();
            Dimension zero = new Dimension(0, 0);
            button.setPreferredSize(zero);
            button.setMinimumSize(zero);
            button.setMaximumSize(zero);
            return button;
        }

        @Override
        protected Dimension getMinimumThumbSize() {
            return scrollbar.getOrientation() == JScrollBar.HORIZONTAL
                    ? new Dimension(28, 8) : new Dimension(8, 28);
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
            graphics.setColor(BG);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setColor(thumbColor);
            if (scrollbar.getOrientation() == JScrollBar.HORIZONTAL) {
                int height = Math.max(4, bounds.height - 4);
                copy.fillRoundRect(bounds.x + 1, bounds.y + 2,
                        bounds.width - 2, height, height, height);
            } else {
                int width = Math.max(4, bounds.width - 4);
                copy.fillRoundRect(bounds.x + 2, bounds.y + 1,
                        width, bounds.height - 2, width, width);
            }
            copy.dispose();
        }
    }
}
