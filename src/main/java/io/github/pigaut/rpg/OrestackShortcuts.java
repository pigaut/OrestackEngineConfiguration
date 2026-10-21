package io.github.pigaut.rpg;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLDocument;
import org.jetbrains.yaml.psi.YAMLFile;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Knows which shortcuts exist, read from config.yml:
 *
 *   - The file is the config.yml inside a folder named "Orestack" if there is one, otherwise any
 *     other config.yml in the project (the first one that actually defines shortcuts).
 *   - Every top-level key ending with "-shortcuts" is a shortcut section, and each key inside it
 *     is a shortcut name ('&emerald', $star, ...).
 *   - Shortcuts inside "color-shortcuts" are COLOR shortcuts, all the others are OTHER.
 *
 * The result is cached per project and refreshed when the config file changes, or when files are
 * created, deleted, moved or renamed.
 */
final class OrestackShortcuts {

    enum Kind { COLOR, OTHER }

    private static final String SECTION_SUFFIX = "-shortcuts";
    private static final String COLOR_SECTION = "color-shortcuts";

    private static final Key<CachedValue<Data>> CACHE = Key.create("orestack.shortcuts");

    private OrestackShortcuts() {}

    // ───────────────────────── Data ─────────────────────────

    static final class Data {
        static final Data EMPTY = new Data(Map.of());

        private final Map<String, Kind> kinds;
        private final @Nullable String knownRegex;

        Data(Map<String, Kind> kinds) {
            this.kinds = kinds;
            this.knownRegex = buildRegex(kinds.keySet());
        }

        /** The kind of the shortcut with this exact name, or null if it isn't a defined shortcut. */
        @Nullable Kind kindOf(String name) {
            return kinds.get(name);
        }

        /** Every defined shortcut name (used for autocompletion). */
        Collection<String> names() {
            return kinds.keySet();
        }

        /**
         * Regex alternation matching any defined shortcut, longest names first so '&emerald' wins
         * over '&e'. Null when nothing is defined. Only names starting with a non-word character
         * ('$', '&', ...) are included, so a plain word is never treated as a shortcut.
         */
        @Nullable String knownRegex() {
            return knownRegex;
        }
    }

    private static @Nullable String buildRegex(Collection<String> names) {
        List<String> usable = new ArrayList<>();
        for (String name : names) {
            if (!name.isEmpty() && !isWordChar(name.charAt(0))) usable.add(name);
        }
        if (usable.isEmpty()) return null;

        usable.sort((a, b) -> a.length() != b.length() ? b.length() - a.length() : a.compareTo(b));

        StringBuilder regex = new StringBuilder();
        for (String name : usable) {
            if (regex.length() > 0) regex.append('|');
            regex.append(Pattern.quote(name));
            // '$star' must not match the start of '$starfish'. '&' names are exempt because
            // a color code is normally glued to the text after it (&6Title).
            if (name.charAt(0) == '$') regex.append("(?![A-Za-z0-9_-])");
        }
        return regex.toString();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ───────────────────────── Lookup (cached) ─────────────────────────

    static @NotNull Data get(@NotNull Project project) {
        // Index lookups are not allowed while indexing, and an empty result must not be cached
        if (DumbService.isDumb(project)) return Data.EMPTY;
        return CachedValuesManager.getManager(project).getCachedValue(project, CACHE, () -> load(project), false);
    }

    private static CachedValueProvider.Result<Data> load(Project project) {
        List<Object> dependencies = new ArrayList<>();
        dependencies.add(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS); // a config.yml appeared / moved / vanished

        Map<String, Kind> kinds = Map.of();
        PsiManager psiManager = PsiManager.getInstance(project);

        for (VirtualFile file : findConfigCandidates(project)) {
            if (!(psiManager.findFile(file) instanceof YAMLFile yamlFile)) continue;
            dependencies.add(yamlFile); // re-read when this file is edited

            Map<String, Kind> found = readShortcuts(yamlFile);
            if (!found.isEmpty()) {
                kinds = found;
                break;
            }
        }
        return CachedValueProvider.Result.create(new Data(kinds), dependencies.toArray());
    }

    /** config.yml files, best first: inside an "Orestack" folder, then source folders, then the rest. */
    private static List<VirtualFile> findConfigCandidates(Project project) {
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        List<VirtualFile> files = new ArrayList<>(
                FilenameIndex.getVirtualFilesByName("config.yml", GlobalSearchScope.projectScope(project)));
        files.removeIf(f -> !fileIndex.isInContent(f) || fileIndex.isExcluded(f));

        files.sort(Comparator
                .comparingInt((VirtualFile f) -> isInOrestackFolder(f) ? 0 : 1)
                .thenComparingInt(f -> fileIndex.isInSourceContent(f) ? 0 : 1)
                .thenComparing(VirtualFile::getPath));
        return files;
    }

    private static boolean isInOrestackFolder(VirtualFile file) {
        VirtualFile parent = file.getParent();
        return parent != null && parent.getName().equalsIgnoreCase("Orestack");
    }

    private static Map<String, Kind> readShortcuts(YAMLFile yamlFile) {
        Map<String, Kind> kinds = new HashMap<>();

        for (YAMLDocument document : yamlFile.getDocuments()) {
            if (!(document.getTopLevelValue() instanceof YAMLMapping root)) continue;

            for (YAMLKeyValue section : root.getKeyValues()) {
                String sectionName = unquote(section.getKeyText());
                if (!sectionName.endsWith(SECTION_SUFFIX)) continue;
                if (!(section.getValue() instanceof YAMLMapping entries)) continue;

                Kind kind = sectionName.equals(COLOR_SECTION) ? Kind.COLOR : Kind.OTHER;
                for (YAMLKeyValue entry : entries.getKeyValues()) {
                    String name = unquote(entry.getKeyText());
                    if (!name.isEmpty()) kinds.putIfAbsent(name, kind);
                }
            }
        }
        return kinds;
    }

    /** Removes one pair of surrounding quotes: '&0' -> &0 */
    static @NotNull String unquote(@NotNull String text) {
        String t = text.trim();
        if (t.length() >= 2) {
            char first = t.charAt(0);
            if ((first == '\'' || first == '"') && t.charAt(t.length() - 1) == first) {
                return t.substring(1, t.length() - 1);
            }
        }
        return t;
    }
}