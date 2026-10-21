package io.github.pigaut.rpg;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLKeyValue;

/**
 * Makes top-level keys in the tracked folders valid Find Usages targets
 * (Alt+F7, Ctrl+Alt+F7, and Ctrl+B / Ctrl+click on the key itself).
 *
 * The actual searching is done by {@link OrestackReferencesSearcher}.
 */
public class OrestackFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        return element instanceof YAMLKeyValue keyValue && OrestackKeys.isTrackedKey(keyValue);
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(@NotNull PsiElement element, boolean forHighlightUsages) {
        return new FindUsagesHandler(element) {};
    }
}