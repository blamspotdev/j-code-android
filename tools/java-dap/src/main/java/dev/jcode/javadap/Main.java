/*
 * JCode standalone Java debug adapter (DAP over stdio).
 *
 * Reuses com.microsoft.java.debug.core's ProtocolServer + DebugAdapter (the full
 * default handler set: initialize / launch / attach / setBreakpoints / stackTrace /
 * scopes / variables / ...). We only supply the provider context. A launch given a
 * concrete mainClass + classPaths spawns the debuggee with -agentlib:jdwp and attaches
 * entirely inside java-debug-core; the only JCode-specific piece needed to actually
 * hit a source-line breakpoint is the ISourceLookUpProvider (source file <-> FQN).
 */
package dev.jcode.javadap;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import com.microsoft.java.debug.core.adapter.IProviderContext;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.microsoft.java.debug.core.adapter.ProtocolServer;
import com.microsoft.java.debug.core.adapter.ProviderContext;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // The DAP client talks to us over stdio. Capture the REAL stdout stream for the
        // protocol BEFORE redirecting System.out, so that stray prints (ours, the
        // library's logging, or the debuggee) cannot corrupt the protocol byte stream.
        final InputStream protocolIn = System.in;
        final OutputStream protocolOut = System.out;

        // From here on, anything written to System.out/System.err goes to stderr,
        // keeping stdout (fd 1) exclusively for Debug Adapter Protocol traffic.
        System.setOut(new PrintStream(System.err, true));

        final List<String> sourceRoots = parseSourceRoots(args);

        final IProviderContext context = new ProviderContext();
        // registerProvider requires the *interface* Class and a provider that implements it.
        context.registerProvider(ISourceLookUpProvider.class,
                new JCodeSourceLookUpProvider(sourceRoots));
        context.registerProvider(IVirtualMachineManagerProvider.class,
                new JCodeVirtualMachineManagerProvider());
        // The launch/breakpoint flow looks these three up via getProvider(), which throws
        // IllegalArgumentException if a provider was never registered. Register no-op stubs.
        // (In particular, SetBreakpointsRequestHandler.initialize() -- invoked from the
        //  DebugAdapter constructor below -- calls getProvider(IHotCodeReplaceProvider.class)
        //  .getEventHub(), so IHotCodeReplaceProvider MUST be registered first.)
        context.registerProvider(IEvaluationProvider.class,
                new JCodeEvaluationProvider());
        context.registerProvider(IHotCodeReplaceProvider.class,
                new JCodeHotCodeReplaceProvider());
        context.registerProvider(ICompletionsProvider.class,
                new JCodeCompletionsProvider());

        // Concrete ProtocolServer(in, out, ctx) internally does
        //   super(in, out); this.debugAdapter = new DebugAdapter(this, ctx);
        // wiring the full default handler set. run() blocks until EOF / stop().
        final ProtocolServer server = new ProtocolServer(protocolIn, protocolOut, context);
        server.run();
    }

    /**
     * Source roots let FQN -> file resolution (stack frames, "source" requests) find
     * .java files. Breakpoint resolution itself does NOT need these: setBreakpoints
     * hands us the absolute source path, which we parse directly.
     *
     * Accepted forms (repeatable):
     *   --source-path DIR
     *   --sourcepath  DIR
     *   --source-path DIR1{File.pathSeparator}DIR2{...}
     */
    private static List<String> parseSourceRoots(String[] args) {
        final List<String> roots = new ArrayList<>();
        if (args == null) {
            return roots;
        }
        final Pattern sep = Pattern.compile(Pattern.quote(File.pathSeparator));
        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];
            if (("--source-path".equals(arg) || "--sourcepath".equals(arg)) && i + 1 < args.length) {
                for (final String part : sep.split(args[++i])) {
                    if (part != null && !part.isBlank()) {
                        roots.add(part.trim());
                    }
                }
            }
        }
        return roots;
    }
}
