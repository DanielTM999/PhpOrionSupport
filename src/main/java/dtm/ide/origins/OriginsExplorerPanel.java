package dtm.ide.origins;

import javax.swing.BorderFactory;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;

public final class OriginsExplorerPanel extends javax.swing.JPanel {
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Origins");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private volatile BiConsumer<Path, Integer> openHandler;

    public OriginsExplorerPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tree.setRootVisible(true);
        tree.addTreeSelectionListener(event -> {
            Object selected = tree.getLastSelectedPathComponent();
            if (selected instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof EndpointNode endpoint) {
                BiConsumer<Path, Integer> handler = openHandler;
                if (handler != null) handler.accept(endpoint.file(), endpoint.line());
            }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    public void setOpenHandler(BiConsumer<Path, Integer> handler) {
        this.openHandler = handler;
    }

    public void showSnapshot(OriginsSnapshot snapshot) {
        Runnable update = () -> {
            root.removeAllChildren();
            root.setUserObject(snapshot == null ? "Origins" : "Origins — " + snapshot.endpoints().size() + " endpoints");
            if (snapshot != null) {
                Map<String, DefaultMutableTreeNode> moduleNodes = new java.util.LinkedHashMap<>();
                for (OriginsModule module : snapshot.modules().values()) {
                    DefaultMutableTreeNode node = new DefaultMutableTreeNode(module.name());
                    moduleNodes.put(module.name(), node);
                    root.add(node);
                }
                for (OriginsEndpoint endpoint : snapshot.endpoints()) {
                    DefaultMutableTreeNode module = moduleNodes.computeIfAbsent(endpoint.module(), key -> {
                        DefaultMutableTreeNode node = new DefaultMutableTreeNode(key);
                        root.add(node);
                        return node;
                    });
                    DefaultMutableTreeNode controller = child(module, endpoint.controller());
                    controller.add(new DefaultMutableTreeNode(new EndpointNode(
                            endpoint.httpMethod() + " " + endpoint.route() + " → " + endpoint.handler(),
                            endpoint.file(), endpoint.line()
                    )));
                }
            }
            model.reload();
            tree.expandRow(0);
        };
        if (SwingUtilities.isEventDispatchThread()) update.run();
        else SwingUtilities.invokeLater(update);
    }

    private static DefaultMutableTreeNode child(DefaultMutableTreeNode parent, String label) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (label.equals(child.getUserObject())) return child;
        }
        DefaultMutableTreeNode created = new DefaultMutableTreeNode(label);
        parent.add(created);
        return created;
    }

    private record EndpointNode(String label, Path file, int line) {
        @Override
        public String toString() {
            return label;
        }
    }
}
