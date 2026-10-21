package io.github.pigaut.rpg;

import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps the first word of every non-indented line of a .yml/.yaml file to that file.
 *
 *   castInstantTransmission (distance=7) (manaCost=0):  ->  "castInstantTransmission"
 *
 * It is only a fast candidate filter: the go-to-declaration handler verifies the real
 * root-level keys through PSI, and only for the few files returned here.
 * IntelliJ builds this during normal indexing and updates it per file when a file changes.
 *
 * Register in plugin.xml:
 *   <extensions defaultExtensionNs="com.intellij">
 *       <fileBasedIndex implementation="io.github.pigaut.rpg.OrestackKeyIndex"/>
 *   </extensions>
 */
public class OrestackKeyIndex extends ScalarIndexExtension<String> {

    public static final ID<String, Void> NAME = ID.create("io.github.pigaut.rpg.yamlRootKeys");

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> {
            Map<String, Void> result = new HashMap<>();
            CharSequence text = inputData.getContentAsText();
            int length = text.length();
            int i = 0;

            while (i < length) {
                // i is at the start of a line
                int start = i;
                char c = text.charAt(start);
                if (c == '"' || c == '\'') start++; // quoted key

                if (start < length && (Character.isLetter(text.charAt(start)) || text.charAt(start) == '_')) {
                    int end = start;
                    while (end < length && isWordChar(text.charAt(end))) end++;
                    result.put(text.subSequence(start, end).toString(), null);
                }

                // skip to the start of the next line
                while (i < length && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
                while (i < length && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) i++;
            }
            return result;
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public FileBasedIndex.@NotNull InputFilter getInputFilter() {
        return file -> {
            String extension = file.getExtension();
            return "yml".equals(extension) || "yaml".equals(extension);
        };
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}