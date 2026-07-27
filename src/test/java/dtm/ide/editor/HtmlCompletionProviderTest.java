package dtm.ide.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlCompletionProviderTest {

    @Test
    void recognizesPhpAndHtmlCaretRegions() {
        String source = "<?php $name = 'A'; ?><div cla";
        assertTrue(HtmlCompletionProvider.isPhpAt(source, source.indexOf("$name") + 2));
        assertFalse(HtmlCompletionProvider.isPhpAt(source, source.length()));
    }

    @Test
    void completesTagsAndAttributesInHybridSource() {
        HtmlCompletionProvider provider = new HtmlCompletionProvider();
        String tag = "<?php echo 'ok'; ?><but";
        assertTrue(provider.suggestions(tag, tag.length(), false).stream()
                .anyMatch(item -> "button".equals(item.label())));

        String attribute = "<?php echo 'ok'; ?><button ari";
        assertTrue(provider.suggestions(attribute, attribute.length(), false).stream()
                .anyMatch(item -> "aria-label".equals(item.label())));
    }
}
