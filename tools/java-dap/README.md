# jcode-java-dap

A small standalone **Java Debug Adapter Protocol server over stdio** for JCode's
JVM debugging, built on Microsoft's `com.microsoft.java.debug.core` (0.53.1). It
reuses java-debug-core's full default handler set (initialize / launch /
setBreakpoints / stackTrace / scopes / variables / continue / stepping) and adds
only the JCode-specific `ISourceLookUpProvider` needed to place source-line
breakpoints against plain `javac`-compiled sources — no Eclipse JDT language
server required.

Why stdio: it eliminates the app→adapter loopback leg that blocks the TCP-based
js-debug adapter under proot. See `docs/java-debug-adapter-plan.md` for the full
transport analysis.

## Build (Gradle — canonical)

Requires Gradle 8.x + a JDK 17+:

```
gradle shadowJar
# → build/libs/jcode-java-dap.jar   (Main-Class: dev.jcode.javadap.Main)
```

This is a **standalone** Gradle build, intentionally kept out of the Android
application's build so its Java-17/JDI toolchain can't perturb the app modules.

## Build (no Gradle — fallback)

The fat jar is a plain javac + `jar` assembly. With a JDK 17+ and the six runtime
dependencies of java-debug-core on the classpath
(`com.microsoft.java:com.microsoft.java.debug.core:0.53.1`, `gson`, `rxjava:2.2.x`,
`reactive-streams`, `commons-lang3`, `commons-io`):

```
javac --add-modules jdk.jdi -encoding UTF-8 -cp "<deps>" -d out $(find src -name '*.java')
# then extract the deps into a staging dir, strip META-INF/*.SF|*.RSA|*.DSA and
# module-info.class, overlay out/dev, and: jar cfm jcode-java-dap.jar MANIFEST.MF -C stage .
```

## Run

```
java -Djava.net.preferIPv4Stack=true -jar jcode-java-dap.jar --source-path /abs/src/root
```

The DAP client speaks over this process's stdin/stdout. `--source-path` is
repeatable and also accepts `File.pathSeparator`-joined roots; the launch
request's `sourcePaths` are merged in as well.

## Launch config (what JCode's `prepareJava` sends)

```json
{
  "type": "java", "request": "launch",
  "mainClass": "com.example.Program",
  "classPaths": ["/tmp/jcode-java-classes"],
  "sourcePaths": ["/abs/src/root"],
  "vmArgs": "-Djava.net.preferIPv4Stack=true",
  "console": "internalConsole", "stopOnEntry": false
}
```

## Status

Desktop-verified (JDK 17): a HelloWorld breakpoint over stdio binds and hits,
with correct call stack, live variables, loop re-hits on `continue`, program
output streaming, and clean termination. The only unverified leg is the
adapter→debuggee JDWP `dt_socket` loopback **under proot on-device** (leg 2) —
the same class of proot-internal loopback debugpy already uses successfully; the
fallback if it stalls is explicit-port attach mode (see the plan doc).

## v1 scope

Java only (`.java`). Expression evaluation / hover / conditional breakpoints are
unsupported (no-op `IEvaluationProvider`). Kotlin is a follow-up (kotlinc
class-name mapping differs).
