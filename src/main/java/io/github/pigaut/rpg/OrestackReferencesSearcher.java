package io.github.pigaut.rpg;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;

/**
 * Teaches Find Usages to search for the right text.
 *
 * By default IntelliJ searches for the element's full name, and for a key like
 *   castInstantTransmission (distance=7) (manaCost=0):
 * that is the whole key text, which never appears anywhere else. Here the search is for the
 * first word only. Each occurrence is then checked through the references created by
 * OrestackReferenceContributor (WordReference#isReferenceTo).
 */
public class OrestackReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {

    public OrestackReferencesSearcher() {
        super(true); // run inside a read action
    }

    @Override
    public void processQuery(@NotNull ReferencesSearch.SearchParameters params,
                             @NotNull Processor<? super PsiReference> consumer) {
        PsiElement target = params.getElementToSearch();
        if (!(target instanceof YAMLKeyValue keyValue) || !target.isValid()) return;
        if (!OrestackKeys.isTrackedKey(keyValue)) return;

        String name = OrestackKeys.nameOf(keyValue);
        if (name == null) return;

        params.getOptimizer().searchWord(
                name,
                params.getEffectiveSearchScope(),
                UsageSearchContext.ANY,
                true, // case sensitive
                target);
    }
}