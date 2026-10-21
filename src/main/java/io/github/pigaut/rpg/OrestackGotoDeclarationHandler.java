package io.github.pigaut.rpg;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider.Result;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLDocument;
import org.jetbrains.yaml.psi.YAMLFile;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLMapping;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ctrl+Click / "Go to declaration" for Orestack YAML files.
 *
 * The word under the cursor (isolated, camelCase, snake_case or kebab-case) is looked up as:
 *   1. a YAML file with that name, without extension   (coal  ->  coal.yml / coal.yaml)
 *   2. a root-level key in any YAML file                (example_pickaxe  ->  example_pickaxe:)
 *      For keys with parameters, only the first word counts:
 *      castInstantTransmission (distance=7) (manaCost=0):  ->  castInstantTransmission
 *
 * Plain (unquoted) values are handled by {@link OrestackReferenceContributor}, which uses
 * {@link #findTargets(String, PsiElement)} so only the hovered word is underlined.
 * This handler covers everything else (mainly the key side), and returns nothing for plain
 * values so targets are never listed twice.
 *
 * Duplicates are avoided:
 *   - identical copies (e.g. the same file in build/out folders) are shown once
 *   - a file in a non-source folder is ignored when a file with the same name exists in a source folder
 *   - when a file is already a target, its own root keys are not listed again
 *
 * Performance: nothing is scanned at click time.
 *   - Candidate files come from IntelliJ's indexes (OrestackKeyIndex and the filename index).
 *   - PSI is only touched for the few candidate files, and their root keys are cached per file.
 */
public class OrestackGotoDeclarationHandler implements GotoDeclarationHandler {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private static final Key<CachedValue<Map<String, List<KeyEntry>>>> ROOT_KEYS_CACHE =
            Key.create("orestack.yaml.rootKeys");

    /** A root-level key with a stable identity used to drop identical copies. */
    private record KeyEntry(String id, YAMLKeyValue keyValue) {}

    // ───────────────────────── Handler ─────────────────────────

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement source, int offset, Editor editor) {
        if (source == null || !(source.getContainingFile() instanceof YAMLFile)) return null;
        if (PsiTreeUtil.getParentOfType(source, PsiComment.class, false) != null) return null;

        // Scalar values are resolved through OrestackReferenceContributor; handling them here too
        // would list every target twice.
        if (PsiTreeUtil.getParentOfType(source, YAMLScalar.class, false) != null) return null;

        String word = wordAt(editor.getDocument().getCharsSequence(), offset);
        PsiElement[] targets = findTargets(word, source);
        return targets.length == 0 ? null : targets;
    }

    // ───────────────────────── Lookup (shared with the reference contributor) ─────────────────────────

    /**
     * Finds the files and root-level keys a word refers to.
     * Never returns null: an empty array means "nothing found" (or indexes not ready).
     */
    public static PsiElement @NotNull [] findTargets(@Nullable String word, @NotNull PsiElement source) {
        if (word == null || word.isEmpty()) return PsiElement.EMPTY_ARRAY;

        Project project = source.getProject();
        // Index lookups throw IndexNotReadyException while the project is indexing
        if (DumbService.isDumb(project)) return PsiElement.EMPTY_ARRAY;

        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        PsiManager psiManager = PsiManager.getInstance(project);
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        PsiFile currentFile = source.getContainingFile();

        // 1. files: coal -> coal.yml / coal.yaml
        Map<String, PsiElement> fileTargets = new LinkedHashMap<>();
        for (String extension : new String[]{".yml", ".yaml"}) {
            List<VirtualFile> files = new ArrayList<>(FilenameIndex.getVirtualFilesByName(word + extension, scope));
            sourceFirst(files, fileIndex);

            for (VirtualFile file : files) {
                if (!isUsable(file, fileIndex) || isStaleCopy(file, fileIndex, scope)) continue;
                PsiFile psiFile = psiManager.findFile(file);
                if (psiFile == null || psiFile.equals(currentFile)) continue;
                fileTargets.putIfAbsent("file:" + file.getName() + ":" + file.getLength(), psiFile);
            }
        }

        // 2. root-level keys: the index narrows it down to the few files containing the word
        Collection<VirtualFile> candidates = FileBasedIndex.getInstance()
                .getContainingFiles(OrestackKeyIndex.NAME, word, scope);
        List<VirtualFile> candidateFiles = new ArrayList<>(candidates);
        sourceFirst(candidateFiles, fileIndex);

        Map<String, PsiElement> keyTargets = new LinkedHashMap<>();
        for (VirtualFile file : candidateFiles) {
            if (!isUsable(file, fileIndex) || isStaleCopy(file, fileIndex, scope)) continue;
            if (!(psiManager.findFile(file) instanceof YAMLFile yamlFile)) continue;
            if (fileTargets.containsValue(yamlFile)) continue; // the file itself is already a target

            for (KeyEntry entry : getRootKeys(yamlFile).getOrDefault(word, List.of())) {
                YAMLKeyValue keyValue = entry.keyValue();
                if (!keyValue.isValid()) continue;
                PsiElement key = keyValue.getKey();
                if (key != null && PsiTreeUtil.isAncestor(key, source, false)) continue; // don't target itself
                keyTargets.putIfAbsent(entry.id(), keyValue);
            }
        }

        if (fileTargets.isEmpty() && keyTargets.isEmpty()) return PsiElement.EMPTY_ARRAY;

        List<PsiElement> result = new ArrayList<>(fileTargets.values());
        result.addAll(keyTargets.values());
        return result.toArray(PsiElement.EMPTY_ARRAY);
    }

    // ───────────────────────── Per-file keys (cached) ─────────────────────────

    /** Root-level keys of one file, grouped by first word. Invalidated when the file changes. */
    private static Map<String, List<KeyEntry>> getRootKeys(YAMLFile yamlFile) {
        return CachedValuesManager.getManager(yamlFile.getProject()).getCachedValue(yamlFile, ROOT_KEYS_CACHE, () ->
                Result.create(computeRootKeys(yamlFile), yamlFile), false);
    }

    private static @NotNull Map<String, List<KeyEntry>> computeRootKeys(YAMLFile yamlFile) {
        VirtualFile file = yamlFile.getVirtualFile();
        if (file == null) return Map.of();

        Map<String, List<KeyEntry>> result = new HashMap<>();

        for (YAMLDocument document : yamlFile.getDocuments()) {
            if (!(document.getTopLevelValue() instanceof YAMLMapping mapping)) continue;

            for (YAMLKeyValue keyValue : mapping.getKeyValues()) {
                PsiElement key = keyValue.getKey();
                if (key == null) continue;

                String keyText = keyValue.getKeyText();
                String id = "key:" + file.getName() + ":" + file.getLength() + ":" + key.getTextOffset()
                        + ":" + keyText;
                result.computeIfAbsent(OrestackKeys.firstWord(keyText), k -> new ArrayList<>())
                        .add(new KeyEntry(id, keyValue));
            }
        }
        return result;
    }

    // ───────────────────────── Helpers ─────────────────────────

    private static boolean isUsable(VirtualFile file, ProjectFileIndex fileIndex) {
        return fileIndex.isInContent(file) && !fileIndex.isExcluded(file);
    }

    /** True for a file outside the source folders that has a same-named twin inside a source folder. */
    private static boolean isStaleCopy(VirtualFile file, ProjectFileIndex fileIndex, GlobalSearchScope scope) {
        if (fileIndex.isInSourceContent(file)) return false;
        for (VirtualFile other : FilenameIndex.getVirtualFilesByName(file.getName(), scope)) {
            if (!other.equals(file) && isUsable(other, fileIndex) && fileIndex.isInSourceContent(other)) return true;
        }
        return false;
    }

    /** Files inside real source folders first (the sort is stable, so the rest keeps its order). */
    private static void sourceFirst(List<VirtualFile> files, ProjectFileIndex fileIndex) {
        files.sort(Comparator.comparing((VirtualFile f) -> !fileIndex.isInSourceContent(f)));
    }

    /** The identifier-like word (letters, digits, '_' and '-') around the offset, or null. */
    private static @Nullable String wordAt(CharSequence text, int offset) {
        if (offset < 0 || offset > text.length()) return null;

        int start = offset;
        int end = offset;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isWordChar(text.charAt(end))) end++;
        if (start == end) return null;

        String word = text.subSequence(start, end).toString();
        return WORD.matcher(word).matches() ? word : null;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}