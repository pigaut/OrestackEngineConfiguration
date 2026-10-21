package io.github.pigaut.rpg;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLQuotedText;
import org.jetbrains.yaml.psi.YAMLScalarList;
import org.jetbrains.yaml.psi.impl.YAMLPlainTextImpl;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eclipse-inspired highlighting for Orestack YAML files.
 *
 * Rules:
 *   comments        # ...                     (gray)
 *   keys            key:
 *   flags           key=value                 (key purple, '=' and value styled separately)
 *   booleans        true / false
 *   numbers         5, -3, +1, 0.75, 100%, 10m, 2.5s
 *   CONSTANT_CASE   IRON_ORE, AIR
 *   camelCase       sendHologram
 *   keywords        return, stop, continue, break   (same color as camelCase)
 *   $shortcuts      $player, $target (any $word is a shortcut)
 *   shortcuts       names defined under the "*-shortcuts" sections of config.yml (see OrestackShortcuts):
 *                   COLOR shortcuts ('&emerald', '&c', ...) get their own color, every other
 *                   shortcut shares the placeholder color. References are highlighted in unquoted
 *                   values, in quoted values (shortcuts and placeholders only, nothing else), and in definition keys.
 *   parameters      <amount> and (optional=value), ONLY inside keys; the whole token gets
 *                   one color and nothing inside it is highlighted separately.
 *                   In values, <param> tokens are left as raw text (nothing inside is colored).
 *   §x              section code (any char after §) plus the next char if not whitespace
 *   placeholders    {internal} and %external%  (same color)
 *   # --- Title --- comments that start and end with --- are bold
 *   shapes          shape: | grids, valid ones only: each ingredient symbol gets its own
 *                   color and '~' is dimmed. '->' and the result item stay raw text.
 *                   Invalid shapes stay raw text.
 *   everything else stays raw text
 */
public class OrestackYamlConfiguration implements Annotator {

    // ───────────────────────── Palette ─────────────────────────
    // style(lightThemeRGB, darkThemeRGB, fontStyle)
    private static TextAttributes style(int light, int dark, int font) {
        return new TextAttributes(new JBColor(new Color(light), new Color(dark)), null, null, null, font);
    }

    static final TextAttributes COMMENT  = style(0x808080, 0x808080, Font.ITALIC); // gray
    static final TextAttributes COMMENT_HEADER = style(0x808080, 0x808080, Font.BOLD | Font.ITALIC); // gray bold: # --- Title ---
    static final TextAttributes KEY      = style(0x7F0055, 0xCC7832, Font.BOLD);   // Eclipse keyword purple
    static final TextAttributes FLAG_KEY = style(0x8E44AD, 0xBB86FC, Font.PLAIN);  // purple
    static final TextAttributes FLAG_EQUALS = style(0xB5007F, 0xE06C9F, Font.BOLD);   // magenta
    static final TextAttributes TRUE     = style(0x008000, 0x57A64A, Font.BOLD);
    static final TextAttributes FALSE    = style(0xC00000, 0xFF6B68, Font.BOLD);
    static final TextAttributes NUMBER   = style(0xB26500, 0x6897BB, Font.PLAIN);
    static final TextAttributes CONSTANT = style(0x85ce22, 0x85ce22, Font.BOLD | Font.ITALIC); // blue aqua (constants)
    static final TextAttributes CAMEL    = style(0xa7ec21, 0xa7ec21, Font.PLAIN);  // softer lime green (method calls)
    static final TextAttributes KEYWORD  = CAMEL;                                  // return/stop/continue/break
    static final TextAttributes SECTION  = style(0xC79100, 0xFFD54F, Font.BOLD);   // gold: §x code + following char
    static final TextAttributes PLACEHOLDER = style(0x79abff, 0x79abff, Font.PLAIN); // blue: {internal} %external% $shortcut
    static final TextAttributes SHORTCUT = style(0x5473aa, 0x5473aa, Font.PLAIN);                           // $shortcut shares the placeholder color
    static final TextAttributes COLOR_SHORTCUT = style(0xe4dd4b, 0xe4dd4b, Font.BOLD); // pink: shortcuts from color-shortcuts
    static final TextAttributes PARAM    = style(0xE65100, 0xFF9E64, Font.PLAIN);  // deep orange: <param>, (opt=param) (keys only)
    static final TextAttributes QUOTED = style(0xbcbec4, 0xbcbec4, Font.PLAIN); // '...' and "..." text, quotes included

    // ── recipe shapes ──
    static final TextAttributes SHAPE_EMPTY  = style(0x9E9E9E, 0x6E6E6E, Font.PLAIN);  // ~ (empty slot)

    // Each distinct ingredient symbol in a shape gets the next color, in order of first appearance.
    static final TextAttributes[] SHAPE_INGREDIENTS = {
            style(0x1976D2, 0x4FA3FF, Font.BOLD), // blue
            style(0x388E3C, 0x6CC644, Font.BOLD), // green
            style(0xE65100, 0xFF9E64, Font.BOLD), // orange
            style(0x8E24AA, 0xBB86FC, Font.BOLD), // purple
            style(0xC79100, 0xFFD54F, Font.BOLD), // gold
            style(0x00838F, 0x2AC3DE, Font.BOLD), // cyan
            style(0xC2185B, 0xF48FB1, Font.BOLD), // pink
            style(0x6D4C41, 0xBCAAA4, Font.BOLD), // brown
    };

    // ───────────────────────── Matching ─────────────────────────
    // Parameters are only recognised inside keys.
    private static final String PARAM_REGEX = "<[^<>\\s]+>|\\([^()\\s]+\\)";
    private static final Pattern KEY_TOKEN_PATTERN =
            Pattern.compile(PARAM_REGEX + "|\\$[A-Za-z_][A-Za-z0-9_-]*");

    private static final String GENERIC_SHORTCUT_REGEX = "\\$[A-Za-z_][A-Za-z0-9_-]*";
    private static final String PLACEHOLDER_REGEX = "\\{[^{}\\s]+\\}|%[A-Za-z_][^%\\s]*%"; // {internal} and %external%

    // Alternatives are tried in order, left to right, so tokens can never overlap.
    private static final Pattern TOKEN = Pattern.compile(
            // 0. section code: '§' + any char, plus the next char if it isn't whitespace
            "(?<sect>§\\S(?:[^\\s§])?)"
                    // 1. '&' color code (&c, ...): consumed and ignored so "&cTool" isn't read as camelCase
                    + "|(?<code>&[0-9a-fk-orx])"
                    // 1b. placeholders: {internal} and %external%
                    + "|(?<ph>" + PLACEHOLDER_REGEX + ")"
                    // 2. [..] and <..> groups: consumed and ignored (so <manaCost> is never read as camelCase)
                    + "|(?<skip>\\[[^\\[\\]{}]*\\]|<[^<>\\s]+>)"
                    // 3. $shortcut
                    + "|(?<shortcut>" + GENERIC_SHORTCUT_REGEX + ")"
                    // 4. flag: identifier immediately followed by '='
                    + "|(?<flag>[A-Za-z_][A-Za-z0-9_-]*)(?<eq>=)"
                    // 5. any other identifier, classified afterward
                    + "|(?<word>[A-Za-z_][A-Za-z0-9_-]*)"
                    // 6. number with optional sign, decimals and a short unit (%, s, m, ms, k...)
                    + "|(?<num>(?<![\\w.&§#$])[+-]?\\d+(?:\\.\\d+)?(?:%|[a-z]{1,2})?(?![A-Za-z0-9_]|\\.\\d))");

    private static final Pattern CONSTANT_CASE = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");
    private static final Pattern CAMEL_CASE    = Pattern.compile("[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*");

    private static final Set<String> KEYWORDS = Set.of("return", "stop", "continue", "break");

    // Recipe shape tokens: the arrow, or any run of non-whitespace
    private static final Pattern SHAPE_TOKEN = Pattern.compile("->|\\S+");

    private record ShapeToken(int start, int end, String text) {}

    // ───────────────────────── Shortcut patterns ─────────────────────────
    // The defined shortcuts come from config.yml, so these patterns are built from them and cached
    // until the shortcut data changes. The "known" group always exists ('(?!)' never matches).

    /** Patterns compiled for one snapshot of the shortcut data. */
    private record Compiled(OrestackShortcuts.Data data, Pattern valuePattern, Pattern shortcutPattern, Pattern keyPattern) {}

    private volatile Compiled compiled;

    private @NotNull Compiled compiledFor(@NotNull OrestackShortcuts.Data data) {
        Compiled current = compiled;
        if (current == null || current.data() != data) {
            String known = data.knownRegex() != null ? data.knownRegex() : "(?!)";
            current = new Compiled(
                    data,
                    // defined shortcuts first, so '&emerald' wins over the '&e' color code
                    Pattern.compile("(?<known>" + known + ")|" + TOKEN.pattern()),
                    // quoted values: shortcuts and placeholders only
                    Pattern.compile("(?<known>" + known + ")|(?<ph>" + PLACEHOLDER_REGEX + ")"
                            + "|(?<generic>" + GENERIC_SHORTCUT_REGEX + ")"),
                    // keys: shortcuts (carved out, left uncolored) and parameters
                    Pattern.compile("(?<known>" + known + ")|(?<param>" + PARAM_REGEX + ")|(?<generic>"
                            + GENERIC_SHORTCUT_REGEX + ")"));
            compiled = current;
        }
        return current;
    }

    /** COLOR shortcuts get their own color, everything else (including unknown $words) the shared one. */
    private static @NotNull TextAttributes shortcutAttributes(@Nullable OrestackShortcuts.Kind kind) {
        return kind == OrestackShortcuts.Kind.COLOR ? COLOR_SHORTCUT : SHORTCUT;
    }

    // ───────────────────────── Annotator ─────────────────────────
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof PsiComment) {
            mark(holder, element.getTextRange(), isHeaderComment(element.getText()) ? COMMENT_HEADER : COMMENT);

        } else if (element instanceof YAMLKeyValue kv) {
            PsiElement key = kv.getKey();
            if (key != null) highlightKey(key, holder);

        } else if (element instanceof YAMLPlainTextImpl) { // unquoted values
            highlightValue(element, holder);

        } else if (element instanceof YAMLQuotedText) { // quoted values: only shortcuts and placeholders
            highlightShortcuts(element, holder);

        } else if (element instanceof YAMLScalarList) { // block scalars: shape: |
            if (element.getParent() instanceof YAMLKeyValue kv && "shape".equals(kv.getKeyText())) {
                highlightShape(element, holder);
            }
        }
    }

    /** "# --- Phase 2 ---": text between '#' and the end starts and ends with "---". */
    private static boolean isHeaderComment(String comment) {
        String body = comment.startsWith("#") ? comment.substring(1).trim() : comment.trim();
        return body.length() >= 6 && body.startsWith("---") && body.endsWith("---");
    }

    private void highlightValue(PsiElement element, AnnotationHolder holder) {
        OrestackShortcuts.Data data = OrestackShortcuts.get(element.getProject());
        String text = element.getText();
        int base = element.getTextRange().getStartOffset();
        Matcher m = compiledFor(data).valuePattern().matcher(text);

        while (m.find()) {
            if (m.group("known") != null) {
                mark(holder, base + m.start("known"), base + m.end("known"),
                        shortcutAttributes(data.kindOf(m.group("known"))));

            } else if (m.group("sect") != null) {
                mark(holder, base + m.start("sect"), base + m.end("sect"), SECTION);

            } else if (m.group("ph") != null) {
                mark(holder, base + m.start("ph"), base + m.end("ph"), PLACEHOLDER);

            } else if (m.group("skip") != null) {
                // ignored

            } else if (m.group("shortcut") != null) {
                // a $word that is not defined in config.yml still counts as a (non-color) shortcut
                mark(holder, base + m.start("shortcut"), base + m.end("shortcut"), SHORTCUT);

            } else if (m.group("flag") != null) {
                mark(holder, base + m.start("flag"), base + m.end("flag"), FLAG_KEY);
                mark(holder, base + m.start("eq"),   base + m.end("eq"), FLAG_EQUALS);
                // the value is scanned by the following iterations, so it gets normal value colors

            } else if (m.group("word") != null) {
                TextAttributes attr = classify(m.group("word"));
                if (attr != null) mark(holder, base + m.start("word"), base + m.end("word"), attr);

            } else if (m.group("num") != null) {
                mark(holder, base + m.start("num"), base + m.end("num"), NUMBER);
            }
            // "code" matches are intentionally skipped
        }
    }

    /** Quoted values are white (quotes included), except for shortcut references and {placeholders} / %placeholders%. */
    private void highlightShortcuts(PsiElement element, AnnotationHolder holder) {
        OrestackShortcuts.Data data = OrestackShortcuts.get(element.getProject());
        String text = element.getText();
        int base = element.getTextRange().getStartOffset();
        Matcher m = compiledFor(data).shortcutPattern().matcher(text);

        int last = 0;
        while (m.find()) {
            String known = m.group("known");
            TextAttributes attr;
            if (known != null) {
                attr = shortcutAttributes(data.kindOf(known));
            } else if (m.group("ph") != null) {
                attr = PLACEHOLDER;
            } else {
                attr = SHORTCUT; // an undefined $word
            }

            if (m.start() > last) mark(holder, base + last, base + m.start(), QUOTED);
            mark(holder, base + m.start(), base + m.end(), attr);
            last = m.end();
        }
        if (last < text.length()) mark(holder, base + last, base + text.length(), QUOTED);
    }

    /**
     * Key colored as KEY, except <param> / (opt=param) parts (PARAM).
     * Shortcuts ($star, &emerald, ...) are never colored specially in keys: they stay KEY.
     */
    private void highlightKey(PsiElement key, AnnotationHolder holder) {
        String text = key.getText();
        int base = key.getTextRange().getStartOffset();

        Matcher m = compiledFor(OrestackShortcuts.get(key.getProject())).keyPattern().matcher(text);
        int last = 0;

        while (m.find()) {
            if (m.group("param") == null) continue; // shortcuts: stay part of the KEY run

            if (m.start() > last) mark(holder, base + last, base + m.start(), KEY);
            mark(holder, base + m.start(), base + m.end(), PARAM);
            last = m.end();
        }
        if (last < text.length()) mark(holder, base + last, base + text.length(), KEY);
    }

    /**
     * Highlights a valid recipe shape:
     *
     *   shape: |
     *     A  A  A
     *     A  ~  A  ->  leather_helmet
     *     ~  ~  ~
     *
     * Valid means: every row has the same number of grid cells, there is exactly one '->',
     * and at least one result token follows it. Invalid shapes are left as raw text.
     */
    private void highlightShape(PsiElement scalar, AnnotationHolder holder) {
        String text = scalar.getText();
        int base = scalar.getTextRange().getStartOffset();

        // Collect tokens per row (the first line is the "|" header, so it is skipped)
        List<List<ShapeToken>> rows = new ArrayList<>();
        int pos = text.indexOf('\n');
        while (pos >= 0 && pos < text.length()) {
            int lineStart = pos + 1;
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();

            List<ShapeToken> tokens = new ArrayList<>();
            Matcher m = SHAPE_TOKEN.matcher(text.substring(lineStart, lineEnd));
            while (m.find()) {
                tokens.add(new ShapeToken(lineStart + m.start(), lineStart + m.end(), m.group()));
            }
            if (!tokens.isEmpty()) rows.add(tokens);
            pos = lineEnd;
        }
        if (rows.isEmpty()) return;

        // Validate: one arrow, a result after it, and equal widths on the grid side
        int arrows = 0;
        int width = -1;
        for (List<ShapeToken> row : rows) {
            int arrowIndex = -1;
            for (int i = 0; i < row.size(); i++) {
                if (row.get(i).text().equals("->")) {
                    arrows++;
                    arrowIndex = i;
                }
            }
            int cells = arrowIndex < 0 ? row.size() : arrowIndex;
            if (arrowIndex >= 0 && (cells == 0 || arrowIndex == row.size() - 1)) return; // no cells / no result
            if (width < 0) width = cells;
            else if (width != cells) return; // ragged grid
        }
        if (arrows != 1) return;

        // Highlight
        Map<String, TextAttributes> ingredientColors = new HashMap<>();
        for (List<ShapeToken> row : rows) {
            boolean afterArrow = false;
            boolean resultDone = false;
            for (ShapeToken t : row) {
                TextAttributes attr;
                if (t.text().equals("->")) {
                    afterArrow = true;
                    continue; // arrow stays raw text

                } else if (afterArrow) {
                    if (!resultDone) {
                        resultDone = true;
                        continue; // result item stays raw text
                    }
                    attr = NUMBER; // extra tokens after the result, e.g. an amount

                } else if (t.text().equals("~")) {
                    attr = SHAPE_EMPTY;

                } else {
                    attr = ingredientColors.get(t.text());
                    if (attr == null) {
                        attr = SHAPE_INGREDIENTS[ingredientColors.size() % SHAPE_INGREDIENTS.length];
                        ingredientColors.put(t.text(), attr);
                    }
                }
                mark(holder, base + t.start(), base + t.end(), attr);
            }
        }
    }

    private @Nullable TextAttributes classify(String word) {
        if (word.equals("true"))  return TRUE;
        if (word.equals("false")) return FALSE;
        if (KEYWORDS.contains(word)) return KEYWORD;
        if (word.length() >= 2 && CONSTANT_CASE.matcher(word).matches()) return CONSTANT;
        if (CAMEL_CASE.matcher(word).matches()) return CAMEL;
        return null; // snake_case, kebab-case, Capitalized, lowercase: raw text
    }

    private void mark(AnnotationHolder holder, int start, int end, TextAttributes attr) {
        mark(holder, new TextRange(start, end), attr);
    }

    private void mark(AnnotationHolder holder, TextRange range, TextAttributes attr) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .enforcedTextAttributes(attr)
                .create();
    }
}