/*
 * No-op ICompletionsProvider. Debug-console code completion is unsupported; returns an
 * empty list. Must be registered because the launch flow initializes it via getProvider().
 */
package dev.jcode.javadap;

import java.util.Collections;
import java.util.List;

import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.protocol.Types.CompletionItem;
import com.sun.jdi.StackFrame;

public class JCodeCompletionsProvider implements ICompletionsProvider {

    @Override
    public List<CompletionItem> codeComplete(StackFrame frame, String snippet, int line, int column) {
        return Collections.emptyList();
    }
}
