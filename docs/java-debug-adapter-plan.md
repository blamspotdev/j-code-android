# JCode JVM debugging — implementation plan (java-debug adapter)

Status: **DONE — shipped and verified end-to-end in the app UI.** The
`tools/java-dap` module (7cb615e) compiles against the real
`com.microsoft.java:com.microsoft.java.debug.core:0.53.1`; the fat jar is
published as GitHub release `java-dap-v1`; and a full UI-first debug session on
the Odin2 (rebuilt app, v1.3.2) passed: set a gutter breakpoint in a `.java`
file → run-config Debug → javac compile → adapter launch/attach → **Paused** at
the breakpoint with current-line highlight → Continue re-hit the breakpoint on
every loop iteration (output streamed: `i=1..5`, `done total=30`) → clean
terminate (`disconnected`). Desktop and in-proot spikes also pass. Leg 1 (stdio)
+ leg 2 (JDWP dt_socket under proot) both confirmed. v1 limitation: no
expression `evaluate`/hover (long-press variable inspect returns nothing for
Java — `IEvaluationProvider` is a no-op stub); variables show via the
scopes/variables panel.

## Why this is the only viable of the three engines

Three JVM/native adapters were investigated for "make it fully work under proot":

- **netcoredbg (.NET)** — dead-end. The debuggee launch fails under proot with
  `0x80070490` (Element not found); a ptrace-based engine can't nest inside
  proot's ptrace sandbox (same class as gdb/lldb). Not fixable without moving
  off proot. Standalone `dotnet App.dll` runs fine — only *debugging* breaks.
- **js-debug (Node)** — dead-end. It's TCP-only; the app→adapter loopback
  `connect()` never completes (leg 1, see below). It cannot speak stdio, so
  there's no way around the broken leg.
- **java-debug (JVM)** — viable, because the JVM adapter *can* speak DAP over
  **stdio**, which sidesteps the one broken loopback leg.

## The load-bearing fact: two loopback legs, only one is broken

- **Leg 1 — app JVM → adapter (proot child).** This is what empirically fails
  (the js-debug 12 s `TcpTransport.connect` timeout). **Eliminated by using
  stdio pipes** — already proven for debugpy / lldb-dap / netcoredbg via
  `ProcessTransport`.
- **Leg 2 — adapter → debuggee JVM (JDWP `dt_socket`).** This is
  proot-internal (child↔child), never touches the app JVM. **debugpy already
  relies on exactly this class of proot-internal loopback and fully works
  on-device**, so it is very likely fine when pinned to IPv4.

Strategy: **DAP over stdio (leg 1 gone), JDWP `dt_socket` pinned to
`127.0.0.1` + `-Djava.net.preferIPv4Stack=true` (leg 2 minimized).**

## Adapter choice (ranked)

**Rank 1 (chosen): a small custom stdio adapter on `com.microsoft.java.debug.core`
(0.53.1).** Ship a shaded fat jar `jcode-java-dap.jar` = java-debug-**core** + a
~200–400-line launcher `dev.jcode.javadap.Main`:

```java
new ProtocolServer(System.in, System.out, providerContext).run();
```

`providerContext` (`IProviderContext`) must register 5 providers — `getProvider`
throws if one is missing:

- `IVirtualMachineManagerProvider` → `com.sun.jdi.Bootstrap.virtualMachineManager()`
  (trivial passthrough).
- `ISourceLookUpProvider` → **the only substantive custom code.** Map source
  file ↔ FQN using the file's `package` + **every** top-level type declared
  (not just the public one), resolved against the configured `sourcePaths`, and
  implement `getBreakpointLocations()`. Bugs here surface as silently unverified
  breakpoints.
- `IEvaluationProvider`, `IHotCodeReplaceProvider`, `ICompletionsProvider` →
  unsupported stubs.

With `console=internalConsole`, java-debug-core self-spawns the debuggee JVM
(JDI `LaunchingConnector` = `com.sun.tools.jdi.SunCommandLineLauncher`) with
`-agentlib:jdwp` and attaches — no Eclipse JDT LS needed.

**Rank 2 (rejected): the "official" java-debug via eclipse.jdt.ls + TCP.**
Returns a TCP port → forces the broken leg-1 TCP connect, and drags in full
jdt.ls (heavy, wants a workspace model, hostile to plain `javac` files).

**Rank 3 (fallback): hand-rolled `com.sun.jdi` ↔ DAP stdio adapter.** Only if
core's handler registration proves too entangled.

## What's already done on this branch (compiles)

- **`core/distro/.../DebugEngineModels.kt`** — `java-debug` entry replaced:
  `dapAdapter=true`, `transport="stdio"`, `debugType="java"`,
  `adapterCommand="java -Djava.net.preferIPv4Stack=true -cp \"$HOME/java-dap/jcode-java-dap.jar\" dev.jcode.javadap.Main"`,
  `requiredSdks=["jdk"]`, Java-only (`.java`). Flipping `dapAdapter` false→true
  makes the fail-fast block at `DebugController.kt:90` no longer trip.
- **`app/.../debug/DebugController.kt`** — new `"java" ->` branch in
  `prepareLaunch` calling `prepareJava()`, which: detects `mainClass` (package +
  `public static void main(String[])`), computes the source root by stripping the
  package path, `javac -g -encoding UTF-8 -d /tmp/jcode-java-classes` over the
  tree, and returns a stdio `LaunchPlan` (`tcpPort=null`) with `mainClass`,
  `classPaths`, `sourcePaths`, `vmArgs=-Djava.net.preferIPv4Stack=true`.

Resulting DAP `launch` request:

```json
{
  "type": "java", "request": "launch", "name": "JCode Debug",
  "cwd": "<distroCwd>", "console": "internalConsole", "stopOnEntry": false,
  "mainClass": "com.example.Program",
  "classPaths": ["/tmp/jcode-java-classes"],
  "sourcePaths": ["/…/src"],
  "vmArgs": "-Djava.net.preferIPv4Stack=true",
  "args": ""
}
```

Concrete `mainClass` + `classPaths` are mandatory — `$Auto`/`$Runtime`/empty
resolution lives in the JDT plugin we don't ship — which is exactly what
`prepareJava` produces.

## Remaining work

1. ~~`tools/java-dap` Gradle module~~ — **DONE** (7cb615e). Note: the Maven
   groupId is `com.microsoft.java` (not `.debug`); all five providers are
   mandatory (`SetBreakpointsRequestHandler.initialize()` subscribes to
   `IHotCodeReplaceProvider.getEventHub()` in the `DebugAdapter` constructor);
   `--add-modules jdk.jdi` at compile time.
2. ~~De-risk with a desktop JVM spike~~ — **DONE, PASS.** A HelloWorld breakpoint
   over stdio binds (`verified:true`) and hits; call stack, live variables, loop
   re-hits on `continue`, output streaming, clean terminate all work — via both
   classpath and `java -jar jcode-java-dap.jar`. The trailing RxJava
   VMDisconnect stack trace is java-debug-core's benign teardown noise (fires
   after `terminated`). The main engineering unknown (core handlers + providers
   outside the JDT plugin) is resolved.
3. ~~On-device leg-2 verification~~ — **DONE, PASS** (Odin2, ubuntu-24.04 guest,
   openjdk-21-jdk-headless). The same stdio spike run *inside proot* against the
   real fat jar hit the breakpoint (`HelloWorld.main:5`, `total=0/i=1`), re-hit
   across loop iterations on `continue`, streamed output, and exited cleanly. The
   adapter→debuggee JDWP `dt_socket` loopback works under proot — no attach-mode
   fallback needed. Leg 1 (stdio) + leg 2 (JDWP) both confirmed on-device.
4. ~~Publish the shaded jar as a GitHub release asset~~ — **DONE.** Published as
   `java-dap-v1` on blamspotdev/j-code-android; the catalog `installCommand` URL
   resolves and the downloaded jar's sha256 matches the built artifact.
5. ~~Rebuild the app + final UI-first debug test~~ — **DONE, PASS** (see Status).
   The signed release APK (matching keystore, `install -r`, runtime preserved)
   was verified with a live breakpoint-pause + loop-debug session in the UI.

## Known follow-ups (minor, non-blocking)

- **No `evaluate`/hover for Java** (`IEvaluationProvider` no-op stub): the editor
  long-press variable-inspect popup returns nothing for `.java`. Variables are
  available via scopes/variables. A real evaluation provider is a future item.
- **java-debug's `requiredSdks=["jdk"]` pulls in the `jdk` toolchain entry,
  which also downloads ktlint (~50 MB)** for Kotlin linting — unnecessary for
  Java debugging and slow on throttled links. Consider a leaner JDK-only
  requirement (or make the ktlint step non-fatal) so the Install button
  completes quickly. (The adapter itself only needs `java`/`javac` on PATH.)
- **Kotlin** (`.kt`) support: deferred (kotlinc class-name mapping differs).

## Honest risks

1. **Leg-2 loopback** — the one that could still block it. Leg 1 is eliminated by
   stdio. Leg 2 is proot-internal `dt_socket`; debugpy proves this class works
   on-device, and IPv4-pinning both JVMs is the mitigation. **Residual risk:**
   java-debug's connector could bind a host/IPv6 that `preferIPv4Stack` doesn't
   fully override, reproducing the js-debug stall. **Fallback:** attach mode —
   JCode spawns the debuggee with `address=127.0.0.1:<port>,server=y,suspend=y`
   and the adapter sends a DAP `attach` to that literal port. Same source-lookup
   provider, so it's a transport fallback, not a redesign. **Must be verified
   on-device with a HelloWorld breakpoint before calling it done.**
2. **java-debug-core standalone handler registration** — see spike above.
3. **Kotlin (`.kt`)** — kotlinc class-name mapping (`…Kt`, synthetic/inline
   names) breaks the naive source→FQN provider; kotlinc isn't in the `jdk` entry.
   Java-only for v1; Kotlin is a follow-up.
4. **`evaluate`/hover** — `IEvaluationProvider` is JDT-coupled; the editor's
   long-press inspect returns null for Java in v1. Acceptable; document it.
5. **Two JVMs under proot** — memory pressure; consider a debuggee `-Xmx` cap
   (analogous to the .NET GC cap in `prepareDotnet`) if devices OOM.

## Notes / corrections

- The `jdk` SDK catalog entry (`catalog.yaml:60`) is **fine** — it uses forward
  slashes (`/tmp/ktlint`). An earlier research pass claimed a `\tmp\ktlint`
  backslash bug; that was a misread. No catalog.yaml change is needed.
- No `extraPath` is needed — `openjdk-21-jdk-headless` puts `java`/`javac` on the
  default PATH `spawnDapProcess` already sets. Headless is sufficient: it keeps
  `jdk.jdi` (`com.sun.jdi`) and the jdwp agent; only AWT/GUI is dropped. A bare
  JRE would **not** work.
