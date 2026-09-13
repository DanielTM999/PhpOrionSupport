package dtm.ide.wizard;

import dtm.stools.component.form.FormField;
import dtm.stools.configs.UiTokens;

import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WizardFormGrid extends JPanel {

    private final Map<String, FormField> fields = new LinkedHashMap<>();
    private final int columns;
    private final int gap = UiTokens.space(2);

    private int row;
    private int column;

    WizardFormGrid(int columns) {
        super(new GridBagLayout());
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be greater than zero");
        }
        this.columns = columns;
        setOpaque(false);
    }

    WizardFormGrid add(FormField field) {
        return place(field, 1);
    }

    WizardFormGrid add(FormField field, int span) {
        return place(field, span);
    }

    WizardFormGrid addWide(FormField field) {
        return place(field, columns);
    }

    private WizardFormGrid place(FormField field, int span) {
        if (fields.putIfAbsent(field.getFieldName(), field) != null) {
            throw new IllegalArgumentException("duplicated field name: " + field.getFieldName());
        }
        int width = Math.min(span, columns);
        if (column + width > columns) {
            column = 0;
            row++;
        }

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.gridwidth = width;
        constraints.weightx = width;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, column == 0 ? 0 : gap, gap, 0);
        add(field, constraints);

        column += width;
        if (column >= columns) {
            column = 0;
            row++;
        }
        return this;
    }

    FormField field(String name) {
        return fields.get(name);
    }

    List<FormField> invalidFields() {
        List<FormField> invalid = new ArrayList<>();
        fields.values().forEach(field -> {
            if (!field.validateField().valid()) {
                invalid.add(field);
            }
        });
        return invalid;
    }
}
