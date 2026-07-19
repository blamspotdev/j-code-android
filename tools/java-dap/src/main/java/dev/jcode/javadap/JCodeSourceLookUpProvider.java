/*
 * Minimal ISourceLookUpProvider for the standalone JCode Java debug adapter.
 *
 * The only piece java-debug-core needs to actually place a source-line breakpoint is
 * getBreakpointLocations(): for each requested line it must return a JavaBreakpointLocation
 * whose className() is the fully-qualified (binary) name of the type enclosing that line
 * and whose lineNumber() is the source line. java-debug-core then installs a JDI
 * ClassPrepareRequest filtered on className() and a BreakpointRequest at lineNumber().
 *
 * We resolve the FQN by parsing the .java file itself: the `package` declaration plus the
 * brace-delimited spans of every (possibly nested) top-level type, choosing the innermost
 * type whose span contains the breakpoint line. This is a heuristic source scan (comments,
 * string/char literals and text blocks are blanked first), sufficient for javac-compiled
 * sources including multiple top-level types and a non-public class carrying main().
 */
package dev.jcode.javadap;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.microsoft.java.debug.core.DebugException;
import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint;

public class JCodeSourceLookUpProvider implements ISourceLookUpProvider {

    private final List<String> sourceRoots = new ArrayList<>();

    public JCodeSourceLookUpProvider(List<String> sourceRoots) {
        if (sourceRoots != null) {
            for (String r : sourceRoots) {
                if (r != null && !r.isBlank()) {
                    this.sourceRoots.add(r);
                }
            }
        }
    }

    /**
     * Called by java-debug-core during launch's post-launch phase. We opportunistically
     * pull the sourcePaths supplied in the launch request so FQN -> file lookups can find
     * .java files even when none were passed on the command line.
     */
    @Override
    public void initialize(IDebugAdapterContext debugContext, Map<String, Object> options) {
        if (debugContext != null) {
            String[] fromLaunch = debugContext.getSourcePaths();
            if (fromLaunch != null) {
                for (String r : fromLaunch) {
                    if (r != null && !r.isBlank() && !sourceRoots.contains(r)) {
                        sourceRoots.add(r);
                    }
                }
            }
        }
    }

    @Override
    public boolean supportsRealtimeBreakpointVerification() {
        return false;
    }

    @Override
    @Deprecated
    public String[] getFullyQualifiedName(String uri, int[] lines, int[] columns) throws DebugException {
        ParsedSource parsed = ParsedSource.parse(readSource(uri));
        int n = (lines == null) ? 0 : lines.length;
        String[] result = new String[n];
        for (int i = 0; i < n; i++) {
            String fqn = parsed.fqnForLine(lines[i]);
            result[i] = (fqn == null) ? "" : fqn;
        }
        return result;
    }

    @Override
    public JavaBreakpointLocation[] getBreakpointLocations(String sourceUri, SourceBreakpoint[] sourceBreakpoints)
            throws DebugException {
        ParsedSource parsed = ParsedSource.parse(readSource(sourceUri));
        int n = (sourceBreakpoints == null) ? 0 : sourceBreakpoints.length;
        // Must return exactly one location per input, in order: the caller iterates
        // locations.length and indexes sourceBreakpoints[i] in lockstep.
        JavaBreakpointLocation[] locations = new JavaBreakpointLocation[n];
        for (int i = 0; i < n; i++) {
            SourceBreakpoint sb = sourceBreakpoints[i];
            JavaBreakpointLocation loc = new JavaBreakpointLocation(sb.line, sb.column);
            String fqn = parsed.fqnForLine(sb.line);
            if (fqn != null && !fqn.isBlank()) {
                loc.setClassName(fqn);
            }
            // methodName/methodSignature left null => line breakpoint (not a method breakpoint).
            locations[i] = loc;
        }
        return locations;
    }

    @Override
    @Deprecated
    public String getSourceFileURI(String fullyQualifiedName, String sourcePath) {
        File f = findSourceFile(fullyQualifiedName, sourcePath);
        // NOTE: returns an absolute filesystem path (what DAP clients accept as Source.path).
        // If your client requires a URI, return f.toURI().toString() instead.
        return (f == null) ? null : f.getAbsolutePath();
    }

    @Override
    public String getSourceContents(String uri) {
        return readSource(uri);
    }

    @Override
    public List<MethodInvocation> findMethodInvocations(String uri, int line) {
        // Used only for inline "method entry" breakpoints / step targets; not needed
        // for basic line breakpoints.
        return Collections.emptyList();
    }

    // getSource(...), getJavaRuntimeVersion(...), getOriginalLineMappings(...),
    // getDecompiledLineMappings(...) keep their interface defaults.

    // ---------------------------------------------------------------------------------
    // File helpers
    // ---------------------------------------------------------------------------------

    private String readSource(String uri) {
        Path p = toPath(uri);
        if (p == null) {
            return "";
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // Degrade gracefully: an unreadable source yields an empty parse (breakpoint
            // stays unverified) rather than failing the whole setBreakpoints request.
            System.err.println("[jcode-java-dap] cannot read source '" + uri + "': " + e);
            return "";
        }
    }

    private static Path toPath(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            if (uri.startsWith("file:")) {
                return Paths.get(URI.create(uri));
            }
            return Paths.get(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private File findSourceFile(String fqn, String sourcePath) {
        List<String> candidates = new ArrayList<>();
        if (sourcePath != null && !sourcePath.isBlank()) {
            candidates.add(sourcePath.replace('\\', '/'));
        }
        if (fqn != null && !fqn.isBlank()) {
            String top = fqn;
            int dollar = top.indexOf('$'); // outer type owns the .java file
            if (dollar >= 0) {
                top = top.substring(0, dollar);
            }
            candidates.add(top.replace('.', '/') + ".java");
        }
        for (String root : sourceRoots) {
            for (String rel : candidates) {
                File f = new File(root, rel);
                if (f.isFile()) {
                    return f;
                }
            }
        }
        for (String rel : candidates) {
            File f = new File(rel);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // Heuristic Java source parser: package + (nested) type spans -> FQN for a line.
    // ---------------------------------------------------------------------------------

    private static final class TypeRange {
        final String binaryName;   // e.g. "HelloWorld" or "Outer$Inner"
        final int startLine;
        final int endLine;
        final boolean topLevel;
        final boolean isPublic;

        TypeRange(String binaryName, int startLine, int endLine, boolean topLevel, boolean isPublic) {
            this.binaryName = binaryName;
            this.startLine = startLine;
            this.endLine = endLine;
            this.topLevel = topLevel;
            this.isPublic = isPublic;
        }
    }

    private static final class Frame {
        final String name; // null for a non-type block or anonymous class body
        final int startLine;
        final boolean isPublic;

        Frame(String name, int startLine, boolean isPublic) {
            this.name = name;
            this.startLine = startLine;
            this.isPublic = isPublic;
        }
    }

    private static final class ParsedSource {
        private static final Pattern PACKAGE =
                Pattern.compile("\\bpackage\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*;");

        private final String packageName;
        private final List<TypeRange> ranges;

        private ParsedSource(String packageName, List<TypeRange> ranges) {
            this.packageName = packageName;
            this.ranges = ranges;
        }

        String fqnForLine(int line) {
            TypeRange best = null;
            for (TypeRange r : ranges) {
                if (line >= r.startLine && line <= r.endLine) {
                    // Prefer the innermost (smallest span) enclosing type.
                    if (best == null || (r.endLine - r.startLine) < (best.endLine - best.startLine)) {
                        best = r;
                    }
                }
            }
            if (best == null) {
                // Line outside any type body (e.g. a field/import line): fall back to the
                // public top-level type, else the first top-level type.
                for (TypeRange r : ranges) {
                    if (r.topLevel && r.isPublic) {
                        best = r;
                        break;
                    }
                }
                if (best == null) {
                    for (TypeRange r : ranges) {
                        if (r.topLevel) {
                            best = r;
                            break;
                        }
                    }
                }
            }
            if (best == null) {
                return null;
            }
            return packageName.isEmpty() ? best.binaryName : packageName + "." + best.binaryName;
        }

        static ParsedSource parse(String source) {
            if (source == null || source.isEmpty()) {
                return new ParsedSource("", Collections.emptyList());
            }
            String cleaned = blankCommentsAndLiterals(source);

            String packageName = "";
            Matcher pm = PACKAGE.matcher(cleaned);
            if (pm.find()) {
                packageName = pm.group(1).replaceAll("\\s+", "");
            }

            List<TypeRange> ranges = new ArrayList<>();
            List<Frame> stack = new ArrayList<>();

            int line = 1;
            StringBuilder word = new StringBuilder();
            boolean expectName = false;
            boolean sawPublic = false;
            boolean dotBefore = false;
            String pendingName = null;
            int pendingLine = 1;
            boolean pendingPublic = false;

            int n = cleaned.length();
            for (int i = 0; i <= n; i++) {
                char c = (i < n) ? cleaned.charAt(i) : '\0';

                if (isIdentChar(c)) {
                    word.append(c);
                    continue;
                }

                // Flush a completed identifier/keyword.
                if (word.length() > 0) {
                    String w = word.toString();
                    word.setLength(0);
                    if (expectName) {
                        pendingName = w;
                        pendingLine = line;
                        pendingPublic = sawPublic;
                        sawPublic = false;
                        expectName = false;
                    } else if ("class".equals(w) && !dotBefore) {
                        // guard against the `.class` literal
                        expectName = true;
                    } else if ("interface".equals(w) || "enum".equals(w) || "record".equals(w)) {
                        expectName = true;
                    } else if ("public".equals(w)) {
                        sawPublic = true;
                    }
                    dotBefore = false;
                }

                switch (c) {
                    case '\n':
                        line++;
                        break;
                    case '.':
                        dotBefore = true;
                        break;
                    case '{':
                        stack.add(new Frame(pendingName, pendingName != null ? pendingLine : line, pendingPublic));
                        pendingName = null;
                        pendingPublic = false;
                        expectName = false;
                        sawPublic = false;
                        dotBefore = false;
                        break;
                    case '}':
                        if (!stack.isEmpty()) {
                            Frame f = stack.remove(stack.size() - 1);
                            if (f.name != null) {
                                List<String> parts = new ArrayList<>();
                                for (Frame anc : stack) {
                                    if (anc.name != null) {
                                        parts.add(anc.name);
                                    }
                                }
                                parts.add(f.name);
                                boolean topLevel = parts.size() == 1;
                                ranges.add(new TypeRange(String.join("$", parts),
                                        f.startLine, line, topLevel, f.isPublic));
                            }
                        }
                        pendingName = null;
                        expectName = false;
                        sawPublic = false;
                        dotBefore = false;
                        break;
                    case ';':
                        // statement / member-declaration terminator: reset pending type detection
                        pendingName = null;
                        expectName = false;
                        sawPublic = false;
                        dotBefore = false;
                        break;
                    case ' ':
                    case '\t':
                    case '\r':
                    case '\f':
                    case '\0':
                        // whitespace: preserve dotBefore across gaps (e.g. `Foo . class`)
                        break;
                    default:
                        // any other symbol ('(', ')', '<', '>', ',', '=', '@', ...) breaks a dot chain
                        dotBefore = false;
                        break;
                }
            }

            return new ParsedSource(packageName, ranges);
        }

        private static boolean isIdentChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }

        /**
         * Replace the interiors of // line comments, block comments, string literals,
         * char literals and text blocks with spaces, preserving newlines so that line
         * numbers stay aligned. This keeps stray braces/semicolons inside literals from
         * corrupting the brace-depth scan.
         */
        private static String blankCommentsAndLiterals(String src) {
            int n = src.length();
            StringBuilder out = new StringBuilder(n);
            int i = 0;
            while (i < n) {
                char c = src.charAt(i);
                char d = (i + 1 < n) ? src.charAt(i + 1) : '\0';

                // line comment
                if (c == '/' && d == '/') {
                    i += 2;
                    while (i < n && src.charAt(i) != '\n') {
                        out.append(' ');
                        i++;
                    }
                    continue;
                }
                // block comment
                if (c == '/' && d == '*') {
                    out.append("  ");
                    i += 2;
                    while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                        out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    if (i < n) {
                        out.append("  ");
                        i += 2;
                    }
                    continue;
                }
                // text block """ ... """
                if (c == '"' && d == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                    out.append("   ");
                    i += 3;
                    while (i < n && !(src.charAt(i) == '"' && i + 2 < n
                            && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                        out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    if (i + 2 < n) {
                        out.append("   ");
                        i += 3;
                    } else {
                        while (i < n) {
                            out.append(' ');
                            i++;
                        }
                    }
                    continue;
                }
                // string literal
                if (c == '"') {
                    out.append(' ');
                    i++;
                    while (i < n && src.charAt(i) != '"') {
                        if (src.charAt(i) == '\\' && i + 1 < n) {
                            out.append("  ");
                            i += 2;
                            continue;
                        }
                        out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    if (i < n) {
                        out.append(' ');
                        i++;
                    }
                    continue;
                }
                // char literal
                if (c == '\'') {
                    out.append(' ');
                    i++;
                    while (i < n && src.charAt(i) != '\'') {
                        if (src.charAt(i) == '\\' && i + 1 < n) {
                            out.append("  ");
                            i += 2;
                            continue;
                        }
                        out.append(' ');
                        i++;
                    }
                    if (i < n) {
                        out.append(' ');
                        i++;
                    }
                    continue;
                }

                out.append(c);
                i++;
            }
            return out.toString();
        }
    }
}
