package io.github.pigaut.rpg;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLFile;

/**
 * Autocompletion for shortcuts that start with '$' or '&' ($star, &emerald, &6, ...).
 *
 * The names come from the "*-shortcuts" sections of config.yml (see OrestackShortcuts).
 * The popup opens as soon as '$' or '&' is typed, and Ctrl+Space works anywhere in a word:
 *
 *   Title: &em|        ->  &emerald
 *   Cost: 5$co|        ->  $coin
 *
 * Register in plugin.xml:
 *   <extensions defaultExtensionNs="com.intellij">
 *       <completion.contributor language="yaml"
 *           implementationClass="io.github.pigaut.rpg.OrestackShortcutCompletionContributor"/>
 *   </extensions>
 */
public class OrestackShortcutCompletionContributor extends CompletionContributor {

    public OrestackShortcutCompletionContributor() {
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement()
                        .inFile(PlatformPatterns.psiFile(YAMLFile.class))
                        .andNot(PlatformPatterns.psiElement(PsiComment.class)),
                new CompletionProvider<CompletionParameters>() {
                    @Override
                    protected void addCompletions(@NotNull CompletionParameters parameters,
                                                  @NotNull ProcessingContext context,
                                                  @NotNull CompletionResultSet result) {
                        String prefix = shortcutPrefix(parameters);
                        if (prefix == null) return;

                        OrestackShortcuts.Data data = OrestackShortcuts.get(parameters.getPosition().getProject());
                        // The default matcher doesn't treat '$' and '&' as part of the prefix
                        CompletionResultSet matching = result.withPrefixMatcher(new PlainPrefixMatcher(prefix));

                        for (String name : data.names()) {
                            if (name.isEmpty() || (name.charAt(0) != '$' && name.charAt(0) != '&')) continue;

                            boolean color = data.kindOf(name) == OrestackShortcuts.Kind.COLOR;
                            matching.addElement(LookupElementBuilder.create(name)
                                    .withTypeText(color ? "color shortcut" : "shortcut", true));
                        }
                    }
                });
    }

    /** Open the popup right after '$' or '&' is typed, not only after the first letter. */
    @Override
    public boolean invokeAutoPopup(@NotNull PsiElement position, char typeChar) {
        return (typeChar == '$' || typeChar == '&') && position.getContainingFile() instanceof YAMLFile;
    }

    /**
     * The shortcut being typed before the caret, including its '$' / '&', or null.
     *   "cost: 5$co|"  ->  "$co"
     *   "&6&l|"        ->  "&l"
     */
    private static @Nullable String shortcutPrefix(@NotNull CompletionParameters parameters) {
        CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
        int offset = parameters.getOffset();
        if (offset < 0 || offset > text.length()) return null;

        int start = offset;
        while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
        if (start == 0) return null;

        char marker = text.charAt(start - 1);
        if (marker != '$' && marker != '&') return null;

        return text.subSequence(start - 1, offset).toString();
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}