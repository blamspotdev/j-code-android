# JCode JVM debugging — implementation plan (java-debug adapter)

Status: **WIP.** Kotlin integration is done and compiles (this branch,
`fa45cc9`). The adapter jar itself, its GitHub release, and on-device
verification are not done — see "Remaining work". Confidence in the approach: 90%.

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

1. **`tools/java-dap` Gradle module** (Java 17, `shadowJar`, depend on
   `com.microsoft.java.debug:com.microsoft.java.debug.core:0.53.1`, Main-Class
   `dev.jcode.javadap.Main`). Prefer a standalone Gradle project over an AGP
   subproject to avoid plugin interference. Files: `build.gradle`, `Main.java`,
   `JCodeSourceLookUpProvider.java`, the four stub providers.
2. **De-risk with a desktop JVM spike first** (no Android): hit a breakpoint in a
   plain `javac` HelloWorld over stdio before touching the app. This is the main
   engineering unknown — standing up `ProtocolServer` + core handlers + stub
   providers outside the JDT plugin.
3. **Build the shaded jar**, publish it as a GitHub release asset (the catalog
   `installCommand` currently points at a placeholder
   `java-dap-v1/jcode-java-dap.jar` URL — publish the real asset, then confirm the
   URL matches).
4. **Rebuild the app.**
5. **On-device verification (the real gate).** Install the `jdk` toolchain,
   install the java-debug adapter (wget), open a `.java` with `main`, set a
   breakpoint, Debug, and confirm it **pauses**.

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
