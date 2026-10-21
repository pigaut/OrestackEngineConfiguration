package io.github.pigaut.rpg;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.ResolveResult;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates one reference per word in every YAML scalar (plain, quoted and block scalars), so that
 * Ctrl+hover underlines a single word and Find Usages can report single words.
 *
 * Tokens that are not names are skipped, the same way the highlighter ignores them:
 *   <param>   {internal}   %external%   and words glued to a color code (&6&lText, 10m)
 */
public class OrestackReferenceContributor extends PsiReferenceContributor {

    // Group 1 is the word. The other alternatives are consumed first so nothing inside them matches.
    private static final Pattern TOKEN = Pattern.compile(
            "<[^<>\\s]+>"
                    + "|\\{[^{}\\s]+\\}"
                    + "|%[A-Za-z_][^%\\s]*%"
                    + "|(?<![\\w&§])([A-Za-z_][A-Za-z0-9_-]*)");

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(YAMLScalar.class),
                new PsiReferenceProvider() {
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(
                            @NotNull PsiElement element, @NotNull ProcessingContext context) {
                        String text = element.getText();
                        List<PsiReference> refs = new ArrayList<>();
                        Matcher m = TOKEN.matcher(text);
                        while (m.find()) {
                            if (m.group(1) == null) continue; // a skipped <param> / {placeholder} / %placeholder%
                            refs.add(new WordReference(element, new TextRange(m.start(1), m.end(1)), m.group(1)));
                        }
                        return refs.toArray(PsiReference.EMPTY_ARRAY);
                    }
                });
    }

    private static class WordReference extends PsiReferenceBase.Poly<PsiElement> {
        private final String word;

        WordReference(PsiElement element, TextRange range, String word) {
            super(element, range, true); // soft: no "unresolved reference" errors
            this.word = word;
        }

        @Override
        public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            PsiElement[] targets = OrestackGotoDeclarationHandler.findTargets(word, getElement());
            return PsiElementResolveResult.createResults(targets);
        }

        /** Used by Find Usages: this word is a usage of 'element' if Ctrl+click would lead there. */
        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            if (!(element instanceof YAMLKeyValue keyValue)) return false;
            if (!word.equals(OrestackKeys.nameOf(keyValue))) return false; // cheap check first

            for (ResolveResult result : multiResolve(false)) {
                PsiElement target = result.getElement();
                if (target != null && getElement().getManager().areElementsEquivalent(target, element)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Object @NotNull [] getVariants() {
            return ArrayUtil.EMPTY_OBJECT_ARRAY;
        }
    }
}