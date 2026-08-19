/*
 * No-op IHotCodeReplaceProvider. Hot code replace is disabled. getEventHub() MUST return a
 * non-null Observable because SetBreakpointsRequestHandler.initialize() (run from the
 * DebugAdapter constructor) subscribes to it immediately; Observable.never() supplies a
 * live, event-free stream. Must be registered before the ProtocolServer is constructed.
 */
package dev.jcode.javadap;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.microsoft.java.debug.core.adapter.HotCodeReplaceEvent;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import io.reactivex.Observable;

public class JCodeHotCodeReplaceProvider implements IHotCodeReplaceProvider {

    @Override
    public void onClassRedefined(Consumer<List<String>> consumer) {
        // never invoked: no classes are ever redefined
    }

    @Override
    public CompletableFuture<List<String>> redefineClasses() {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public Observable<HotCodeReplaceEvent> getEventHub() {
        return Observable.never();
    }
}
