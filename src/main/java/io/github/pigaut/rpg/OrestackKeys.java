package io.github.pigaut.rpg;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLDocument;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLMapping;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides which keys Find Usages works for, and what name they are searched by.
 *
 * Tracked keys are the TOP-LEVEL keys of yaml files located in (or below) one of these folders,
 * matched against the path relative to the content root:
 *   functions, effects/sounds, effects/particles, items, messages, recipes
 *
 * The searchable name of a key is its first word, so parameters are ignored:
 *   castInstantTransmission (distance=7) (manaCost=0):  ->  castInstantTransmission
 */
public final class OrestackKeys {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private static final List<List<String>> TRACKED_FOLDERS = List.of(
            List.of("functions"),
            List.of("effects", "sounds"),
            List.of("effects", "particles"),
            List.of("items"),
            List.of("messages"),
            List.of("recipes")
    );

    private OrestackKeys() {}

    /** True for a top-level key of a yaml file inside one of the tracked folders. */
    public static boolean isTrackedKey(@NotNull YAMLKeyValue keyValue) {
        if (!(keyValue.getParent() instanceof YAMLMapping mapping)) return false;
        if (!(mapping.getParent() instanceof YAMLDocument)) return false;

        PsiFile psiFile = keyValue.getContainingFile().getOriginalFile();
        VirtualFile file = psiFile.getVirtualFile();
        return file != null && isInTrackedFolder(file, keyValue.getProject());
    }

    /** The first word of the key (the part Find Usages looks for), or null if it isn't a plain name. */
    public static @Nullable String nameOf(@NotNull YAMLKeyValue keyValue) {
        String word = firstWord(keyValue.getKeyText());
        return WORD.matcher(word).matches() ? word : null;
    }

    /** "castInstantTransmission (distance=7) (manaCost=0)" -> "castInstantTransmission" */
    public static @NotNull String firstWord(@NotNull String keyText) {
        String trimmed = keyText.trim();
        int space = 0;
        while (space < trimmed.length() && !Character.isWhitespace(trimmed.charAt(space))) space++;
        return trimmed.substring(0, space);
    }

    private static boolean isInTrackedFolder(@NotNull VirtualFile file, @NotNull Project project) {
        VirtualFile root = ProjectFileIndex.getInstance(project).getContentRootForFile(file);
        VirtualFile parent = file.getParent();
        if (root == null || parent == null) return false;

        String relative = VfsUtilCore.getRelativePath(parent, root, '/');
        if (relative == null || relative.isEmpty()) return false;

        List<String> segments = Arrays.asList(relative.split("/"));
        for (List<String> folder : TRACKED_FOLDERS) {
            if (containsSequence(segments, folder)) return true;
        }
        return false;
    }

    /** True if 'sequence' appears as consecutive elements of 'segments' (e.g. effects, sounds). */
    private static boolean containsSequence(List<String> segments, List<String> sequence) {
        for (int i = 0; i + sequence.size() <= segments.size(); i++) {
            if (segments.subList(i, i + sequence.size()).equals(sequence)) return true;
        }
        return false;
    }
}