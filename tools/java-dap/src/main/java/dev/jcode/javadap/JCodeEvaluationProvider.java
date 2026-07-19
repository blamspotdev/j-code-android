/*
 * No-op IEvaluationProvider. Expression evaluation, conditional breakpoints and logpoints
 * are unsupported by this minimal adapter; every evaluation returns a failed future so the
 * core degrades gracefully instead of NPEing. Must be registered because the breakpoint
 * event handler looks it up via getProvider(IEvaluationProvider.class).
 */
package dev.jcode.javadap;

import java.util.concurrent.CompletableFuture;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

public class JCodeEvaluationProvider implements IEvaluationProvider {

    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return false;
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
        return unsupported();
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ObjectReference thisContext, ThreadReference thread) {
        return unsupported();
    }

    @Override
    public CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint breakpoint, ThreadReference thread) {
        return unsupported();
    }

    @Override
    public CompletableFuture<Value> invokeMethod(ObjectReference thisContext, String methodName, String methodSignature,
            Value[] args, ThreadReference thread, boolean invokeSuper) {
        return unsupported();
    }

    @Override
    public void clearState(ThreadReference thread) {
        // no evaluation state to clear
    }

    private static CompletableFuture<Value> unsupported() {
        CompletableFuture<Value> future = new CompletableFuture<>();
        future.completeExceptionally(
                new UnsupportedOperationException("Expression evaluation is not supported by jcode-java-dap."));
        return future;
    }
}
