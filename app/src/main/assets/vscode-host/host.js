'use strict';
/*
 * JCode's VS Code extension host.
 *
 * Runs inside the Linux runtime under Node and loads a `.vsix` extension's `main` module with a
 * `vscode` module of our own in front of it. Everything the extension asks of the editor is
 * forwarded to JCode over stdin/stdout as newline-delimited JSON; everything JCode asks of the
 * extension (activate, resolve a webview, run a command) arrives the same way.
 *
 * JCode implements the slice of the API that extensions built around a webview use. Anything
 * outside it throws by name — `vscode.debug.startDebugging is not implemented by JCode` — because
 * an extension failing loudly at the call it actually made is far easier to act on than one that
 * silently does nothing.
 *
 * argv: --ext-dir <dir> --main <relative entry> [--id <extension id>]
 */

const path = require('path');
const fs = require('fs');
const Module = require('module');

// ---- argv ---------------------------------------------------------------------------------

const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const at = argv.indexOf(name);
  return at >= 0 && at + 1 < argv.length ? argv[at + 1] : fallback;
};
const EXT_DIR = arg('--ext-dir');
const MAIN = arg('--main');
const EXT_ID = arg('--id', 'extension');
if (!EXT_DIR || !MAIN) {
  process.stderr.write('host.js: --ext-dir and --main are required\n');
  process.exit(2);
}

// ---- transport ----------------------------------------------------------------------------

let nextRequestId = 1;
const pending = new Map();

const send = (message) => {
  process.stdout.write(JSON.stringify(message) + '\n');
};

/** Ask JCode something and wait for its answer. */
const call = (method, params) =>
  new Promise((resolve, reject) => {
    const id = nextRequestId++;
    pending.set(id, { resolve, reject });
    send({ id, method, params: params || {} });
  });

/** Tell JCode something with no answer expected. */
const notify = (method, params) => send({ method, params: params || {} });

const log = (level, text) => notify('host/log', { level, text: String(text) });

// ---- the vscode module --------------------------------------------------------------------

/** Everything not implemented routes here, so the failure names the member that was missing. */
const missing = (member) => () => {
  throw new Error(
    `vscode.${member} is not implemented by JCode. JCode runs the part of the VS Code API that ` +
      `extensions built around a webview use.`,
  );
};

/** A minimal Disposable. */
const disposable = (dispose) => ({ dispose: dispose || (() => {}) });

/** An event source shaped like vscode.Event. */
const emitter = () => {
  const listeners = new Set();
  const event = (listener, thisArgs) => {
    const bound = thisArgs ? listener.bind(thisArgs) : listener;
    listeners.add(bound);
    return disposable(() => listeners.delete(bound));
  };
  event.fire = (value) => {
    for (const listener of Array.from(listeners)) {
      try {
        listener(value);
      } catch (err) {
        log('error', `event listener threw: ${(err && err.stack) || err}`);
      }
    }
  };
  return event;
};

class Uri {
  constructor(scheme, authority, fsPath, query, fragment) {
    this.scheme = scheme || 'file';
    this.authority = authority || '';
    this.path = fsPath || '';
    this.query = query || '';
    this.fragment = fragment || '';
  }
  get fsPath() {
    return this.path;
  }
  static file(p) {
    return new Uri('file', '', p);
  }
  static parse(value) {
    const match = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/.exec(value);
    if (!match) return new Uri('file', '', value);
    return new Uri(match[1], match[2], match[3], match[4], match[5]);
  }
  static joinPath(base, ...parts) {
    return new Uri(base.scheme, base.authority, path.posix.join(base.path, ...parts), base.query, base.fragment);
  }
  with(change) {
    return new Uri(
      change.scheme !== undefined ? change.scheme : this.scheme,
      change.authority !== undefined ? change.authority : this.authority,
      change.path !== undefined ? change.path : this.path,
      change.query !== undefined ? change.query : this.query,
      change.fragment !== undefined ? change.fragment : this.fragment,
    );
  }
  toString() {
    const q = this.query ? `?${this.query}` : '';
    const f = this.fragment ? `#${this.fragment}` : '';
    return `${this.scheme}://${this.authority}${this.path}${q}${f}`;
  }
  toJSON() {
    return { scheme: this.scheme, authority: this.authority, path: this.path, query: this.query, fragment: this.fragment };
  }
}

// --- webviews ---

/** Origin the app serves the extension's own files from. Also what `cspSource` reports, so an
 *  extension that builds a Content-Security-Policy around it keeps working unchanged. */
const RESOURCE_ORIGIN = 'https://jcode.webview';

const webviewViewProviders = new Map();
const webviews = new Map();
let nextWebviewId = 1;

const makeWebview = (viewId) => {
  const handle = `webview-${nextWebviewId++}`;
  const onDidReceiveMessage = emitter();
  let html = '';
  let options = {};
  const webview = {
    get options() {
      return options;
    },
    set options(value) {
      options = value || {};
    },
    get html() {
      return html;
    },
    set html(value) {
      html = String(value == null ? '' : value);
      notify('webview/html', { handle, viewId, html });
    },
    cspSource: RESOURCE_ORIGIN,
    onDidReceiveMessage,
    postMessage: (message) => call('webview/postMessage', { handle, message }).then(() => true),
    // The host sees the extension through the runtime's filesystem, but the page is rendered by the
    // app, which reaches the same files by a different path. So a resource URI is expressed relative
    // to the extension and given an origin the app serves — it resolves the side it is loaded on.
    asWebviewUri: (uri) => {
      const target = String((uri && (uri.fsPath || uri.path)) || uri);
      const rel = path.posix.relative(EXT_DIR.replace(/\\/g, '/'), target.replace(/\\/g, '/'));
      return Uri.parse(`${RESOURCE_ORIGIN}/${rel.replace(/^\/+/, '')}`);
    },
  };
  webviews.set(handle, webview);
  return { handle, webview };
};

// --- the API surface ---

const activeColorTheme = { kind: 2 }; // Dark. JCode overwrites this from its own theme below.
const onDidChangeActiveColorTheme = emitter();
const onDidChangeActiveTextEditor = emitter();
const onDidChangeTextEditorSelection = emitter();
const onDidChangeWindowState = emitter();
const onDidChangeConfiguration = emitter();
const onDidChangeWorkspaceFolders = emitter();

const commands = new Map();
const outputChannels = new Map();

let workspaceState = { folders: [], configuration: {} };
let activeTextEditor;
let windowState = { focused: true, active: true };

const configurationFor = (section) => {
  const prefix = section ? `${section}.` : '';
  return {
    get: (key, fallback) => {
      const value = workspaceState.configuration[prefix + key];
      return value === undefined ? fallback : value;
    },
    has: (key) => workspaceState.configuration[prefix + key] !== undefined,
    inspect: () => undefined,
    update: (key, value) => call('config/update', { key: prefix + key, value }),
  };
};

const vscode = {
  version: '1.85.0',
  Uri,
  // A class, not a plain object: extensions do `new vscode.Disposable(() => …)` as well as
  // `vscode.Disposable.from(…)`.
  Disposable: class Disposable {
    constructor(callOnDispose) {
      this._callOnDispose = callOnDispose;
    }
    dispose() {
      if (typeof this._callOnDispose === 'function') this._callOnDispose();
      this._callOnDispose = undefined;
    }
    static from(...items) {
      return new Disposable(() => items.forEach((i) => i && typeof i.dispose === 'function' && i.dispose()));
    }
  },
  EventEmitter: class {
    constructor() {
      this.event = emitter();
    }
    fire(value) {
      this.event.fire(value);
    }
    dispose() {}
  },
  ExtensionMode: { Production: 1, Development: 2, Test: 3 },
  ConfigurationTarget: { Global: 1, Workspace: 2, WorkspaceFolder: 3 },
  ColorThemeKind: { Light: 1, Dark: 2, HighContrast: 3, HighContrastLight: 4 },
  ViewColumn: { Active: -1, Beside: -2, One: 1, Two: 2, Three: 3 },
  FileType: { Unknown: 0, File: 1, Directory: 2, SymbolicLink: 64 },
  StatusBarAlignment: { Left: 1, Right: 2 },
  TreeItemCollapsibleState: { None: 0, Collapsed: 1, Expanded: 2 },

  commands: {
    registerCommand: (id, handler, thisArg) => {
      commands.set(id, thisArg ? handler.bind(thisArg) : handler);
      notify('commands/registered', { id });
      return disposable(() => commands.delete(id));
    },
    registerTextEditorCommand: (id, handler, thisArg) => vscode.commands.registerCommand(id, handler, thisArg),
    executeCommand: (id, ...args) => {
      const local = commands.get(id);
      if (local) return Promise.resolve().then(() => local(...args));
      return call('commands/execute', { id, args });
    },
    // Answered here rather than asked of JCode: JCode implements no VS Code built-in commands, so
    // the extension's own registrations are the whole list. Asking anyway rejected, and an extension
    // that calls this without a catch (they do) turned that into an unhandled rejection.
    getCommands: () => Promise.resolve(Array.from(commands.keys())),
  },

  window: {
    get activeTextEditor() {
      return activeTextEditor;
    },
    get activeColorTheme() {
      return activeColorTheme;
    },
    get state() {
      return windowState;
    },
    visibleTextEditors: [],
    onDidChangeActiveTextEditor,
    onDidChangeTextEditorSelection,
    onDidChangeActiveColorTheme,
    onDidChangeWindowState,
    onDidChangeVisibleTextEditors: emitter(),
    showInformationMessage: (message, ...items) => call('window/message', { level: 'info', message, items: flatItems(items) }),
    showWarningMessage: (message, ...items) => call('window/message', { level: 'warning', message, items: flatItems(items) }),
    showErrorMessage: (message, ...items) => call('window/message', { level: 'error', message, items: flatItems(items) }),
    showQuickPick: (items, options) =>
      Promise.resolve(items).then((resolved) =>
        call('window/quickPick', { items: resolved.map(labelOf), options: options || {} }).then((picked) =>
          picked == null ? undefined : resolved[picked],
        ),
      ),
    showInputBox: (options) => call('window/inputBox', { options: options || {} }),
    createOutputChannel: (name) => {
      const existing = outputChannels.get(name);
      if (existing) return existing;
      const channel = {
        name,
        append: (text) => notify('output/append', { name, text }),
        appendLine: (text) => notify('output/append', { name, text: `${text}\n` }),
        replace: (text) => notify('output/replace', { name, text }),
        clear: () => notify('output/clear', { name }),
        show: () => notify('output/show', { name }),
        hide: () => {},
        dispose: () => outputChannels.delete(name),
      };
      outputChannels.set(name, channel);
      return channel;
    },
    registerWebviewViewProvider: (viewId, provider) => {
      webviewViewProviders.set(viewId, provider);
      notify('webview/providerRegistered', { viewId });
      return disposable(() => webviewViewProviders.delete(viewId));
    },
    createWebviewPanel: (viewType, title, _column, _options) => {
      const { handle, webview } = makeWebview(viewType);
      const onDidDispose = emitter();
      notify('webview/panelCreated', { handle, viewType, title });
      return {
        viewType,
        title,
        webview,
        active: true,
        visible: true,
        onDidDispose,
        onDidChangeViewState: emitter(),
        reveal: () => notify('webview/reveal', { handle }),
        dispose: () => {
          webviews.delete(handle);
          onDidDispose.fire();
          notify('webview/disposed', { handle });
        },
      };
    },
    withProgress: (_options, task) => Promise.resolve(task({ report: () => {} }, { isCancellationRequested: false })),
    setStatusBarMessage: () => disposable(),
    createStatusBarItem: () => ({
      text: '',
      tooltip: '',
      command: undefined,
      show: () => {},
      hide: () => {},
      dispose: () => {},
    }),
    createTreeView: missing('window.createTreeView'),
    registerTreeDataProvider: missing('window.registerTreeDataProvider'),
    createTerminal: missing('window.createTerminal'),
    showTextDocument: missing('window.showTextDocument'),
  },

  workspace: {
    get workspaceFolders() {
      return workspaceState.folders.length ? workspaceState.folders : undefined;
    },
    get name() {
      return workspaceState.folders[0] && workspaceState.folders[0].name;
    },
    onDidChangeWorkspaceFolders,
    onDidChangeConfiguration,
    onDidSaveTextDocument: emitter(),
    onDidOpenTextDocument: emitter(),
    onDidCloseTextDocument: emitter(),
    onDidChangeTextDocument: emitter(),
    getConfiguration: (section) => configurationFor(section),
    asRelativePath: (input, includeFolder) => {
      const target = String(input && input.fsPath ? input.fsPath : input);
      for (const folder of workspaceState.folders) {
        const base = folder.uri.fsPath;
        if (target === base) return includeFolder ? folder.name : '';
        if (target.startsWith(`${base}/`)) {
          const rel = target.slice(base.length + 1);
          return includeFolder ? `${folder.name}/${rel}` : rel;
        }
      }
      return target;
    },
    getWorkspaceFolder: (uri) => {
      const target = String(uri && uri.fsPath ? uri.fsPath : uri);
      return workspaceState.folders.find((f) => target === f.uri.fsPath || target.startsWith(`${f.uri.fsPath}/`));
    },
    // The extension host shares the runtime's filesystem, so these are real operations rather than
    // a round trip through JCode.
    fs: {
      readFile: (uri) => fs.promises.readFile(uri.fsPath || uri.path).then((b) => new Uint8Array(b)),
      writeFile: (uri, content) => fs.promises.writeFile(uri.fsPath || uri.path, Buffer.from(content)),
      delete: (uri, options) => fs.promises.rm(uri.fsPath || uri.path, { recursive: !!(options && options.recursive), force: true }),
      createDirectory: (uri) => fs.promises.mkdir(uri.fsPath || uri.path, { recursive: true }),
      stat: (uri) =>
        fs.promises.stat(uri.fsPath || uri.path).then((s) => ({
          type: s.isDirectory() ? 2 : s.isSymbolicLink() ? 64 : 1,
          ctime: s.ctimeMs,
          mtime: s.mtimeMs,
          size: s.size,
        })),
      readDirectory: (uri) =>
        fs.promises
          .readdir(uri.fsPath || uri.path, { withFileTypes: true })
          .then((entries) => entries.map((e) => [e.name, e.isDirectory() ? 2 : e.isSymbolicLink() ? 64 : 1])),
      copy: (from, to) => fs.promises.copyFile(from.fsPath || from.path, to.fsPath || to.path),
      rename: (from, to) => fs.promises.rename(from.fsPath || from.path, to.fsPath || to.path),
    },
    openTextDocument: missing('workspace.openTextDocument'),
    createFileSystemWatcher: () => ({
      onDidCreate: emitter(),
      onDidChange: emitter(),
      onDidDelete: emitter(),
      dispose: () => {},
    }),
    findFiles: missing('workspace.findFiles'),
    applyEdit: missing('workspace.applyEdit'),
  },

  env: {
    appName: 'JCode',
    appHost: 'jcode',
    uriScheme: 'jcode',
    language: 'en',
    machineId: 'jcode',
    sessionId: `jcode-${Date.now()}`,
    clipboard: {
      readText: () => call('env/clipboardRead', {}),
      writeText: (text) => call('env/clipboardWrite', { text }),
    },
    openExternal: (uri) => call('env/openExternal', { uri: String(uri) }).then(() => true),
    asExternalUri: (uri) => Promise.resolve(uri),
  },

  // Localisation. An extension built against a recent VS Code calls `l10n.t()` for user-facing
  // strings, often during activate() — so this has to exist, and returning the source string is the
  // right answer when there is no translation bundle to consult.
  l10n: {
    t: (...args) => {
      const first = args[0];
      const message = typeof first === 'string' ? first : (first && first.message) || '';
      const values = typeof first === 'string' ? args.slice(1) : (first && first.args) || [];
      // VS Code's own formatting: {0}, {1}, … positional, or {name} against an object of values.
      return String(message).replace(/\{([^}]+)\}/g, (match, key) => {
        const index = Number(key);
        const value = Number.isInteger(index) ? values[index] : values && values[key];
        return value === undefined ? match : String(value);
      });
    },
    bundle: undefined,
    uri: undefined,
  },

  languages: new Proxy({}, { get: (_t, key) => missing(`languages.${String(key)}`) }),
  debug: new Proxy({}, { get: (_t, key) => missing(`debug.${String(key)}`) }),
  tasks: new Proxy({}, { get: (_t, key) => missing(`tasks.${String(key)}`) }),
  scm: new Proxy({}, { get: (_t, key) => missing(`scm.${String(key)}`) }),
  extensions: {
    getExtension: () => undefined,
    all: [],
    onDidChange: emitter(),
  },
};

const flatItems = (items) => {
  const flat = items.length === 1 && Array.isArray(items[0]) ? items[0] : items;
  return flat.filter((i) => typeof i === 'string' || (i && typeof i.title === 'string')).map(labelOf);
};
const labelOf = (item) => (typeof item === 'string' ? item : item && (item.label || item.title)) || String(item);

// ---- module interception ------------------------------------------------------------------

const originalResolve = Module._resolveFilename;
Module._resolveFilename = function (request, ...rest) {
  if (request === 'vscode') return 'vscode';
  return originalResolve.call(this, request, ...rest);
};
const originalLoad = Module._load;
Module._load = function (request, ...rest) {
  if (request === 'vscode') return vscode;
  return originalLoad.call(this, request, ...rest);
};

// ---- extension lifecycle ------------------------------------------------------------------

let extensionModule;
let extensionContext;

const subscriptions = [];

const activate = async (state) => {
  workspaceState = {
    folders: (state.folders || []).map((f, index) => ({
      uri: Uri.file(f.path),
      name: f.name || path.posix.basename(f.path),
      index,
    })),
    configuration: state.configuration || {},
  };
  windowState = { focused: true, active: true };

  // Worth stating plainly in the log: an extension that resolves its working directory from the
  // workspace behaves completely differently when this is empty, and the difference is otherwise
  // only visible several layers downstream.
  log('info', 'workspace folders: ' + JSON.stringify(workspaceState.folders.map((f) => f.uri.fsPath)));

  const entry = path.resolve(EXT_DIR, MAIN);
  extensionModule = require(entry);

  extensionContext = {
    subscriptions,
    extensionPath: EXT_DIR,
    extensionUri: Uri.file(EXT_DIR),
    extensionMode: 1,
    storageUri: Uri.file(path.join(EXT_DIR, '.storage')),
    globalStorageUri: Uri.file(path.join(EXT_DIR, '.global-storage')),
    logUri: Uri.file(path.join(EXT_DIR, '.logs')),
    environmentVariableCollection: { replace: () => {}, append: () => {}, prepend: () => {}, clear: () => {} },
    asAbsolutePath: (rel) => path.resolve(EXT_DIR, rel),
    workspaceState: memento(),
    globalState: Object.assign(memento(), { setKeysForSync: () => {} }),
    secrets: {
      get: (key) => call('secrets/get', { key }),
      store: (key, value) => call('secrets/store', { key, value }),
      delete: (key) => call('secrets/delete', { key }),
      onDidChange: emitter(),
    },
  };

  if (typeof extensionModule.activate !== 'function') {
    throw new Error(`${MAIN} does not export activate()`);
  }
  await extensionModule.activate(extensionContext);
  return { commands: Array.from(commands.keys()), views: Array.from(webviewViewProviders.keys()) };
};

const memento = () => {
  const store = new Map();
  return {
    get: (key, fallback) => (store.has(key) ? store.get(key) : fallback),
    update: (key, value) => {
      store.set(key, value);
      return Promise.resolve();
    },
    keys: () => Array.from(store.keys()),
  };
};

/** Ask the extension to fill in a webview view JCode is showing. */
const resolveWebviewView = async ({ viewId }) => {
  const provider = webviewViewProviders.get(viewId);
  if (!provider) throw new Error(`no webview view provider registered for "${viewId}"`);
  const { handle, webview } = makeWebview(viewId);
  const onDidDispose = emitter();
  const onDidChangeVisibility = emitter();
  const view = {
    viewType: viewId,
    webview,
    visible: true,
    onDidDispose,
    onDidChangeVisibility,
    show: () => notify('webview/reveal', { handle }),
    title: undefined,
    description: undefined,
  };
  await provider.resolveWebviewView(view, { state: undefined }, { isCancellationRequested: false, onCancellationRequested: emitter() });
  return { handle, html: webview.html };
};

// ---- dispatch -----------------------------------------------------------------------------

const handlers = {
  activate,
  resolveWebviewView,
  'command/execute': ({ id, args }) => {
    const handler = commands.get(id);
    if (!handler) throw new Error(`command "${id}" is not registered`);
    return handler(...(args || []));
  },
  'webview/message': ({ handle, message }) => {
    const webview = webviews.get(handle);
    if (webview) webview.onDidReceiveMessage.fire(message);
    return null;
  },
  'state/activeFile': (file) => {
    activeTextEditor = file
      ? {
          document: {
            uri: Uri.file(file.path),
            fileName: file.path,
            languageId: file.languageId || 'plaintext',
            isUntitled: false,
            isDirty: !!file.dirty,
            getText: () => '',
            lineCount: 0,
          },
          selection: { active: { line: 0, character: 0 }, anchor: { line: 0, character: 0 }, isEmpty: true },
          selections: [],
        }
      : undefined;
    onDidChangeActiveTextEditor.fire(activeTextEditor);
    return null;
  },
  'state/theme': ({ kind }) => {
    activeColorTheme.kind = kind === 'light' ? 1 : 2;
    onDidChangeActiveColorTheme.fire(activeColorTheme);
    return null;
  },
  'state/configuration': ({ configuration }) => {
    workspaceState.configuration = configuration || {};
    onDidChangeConfiguration.fire({ affectsConfiguration: () => true });
    return null;
  },
  deactivate: async () => {
    for (const item of subscriptions) {
      try {
        if (item && typeof item.dispose === 'function') item.dispose();
      } catch (err) {
        log('warning', `dispose threw: ${err}`);
      }
    }
    if (extensionModule && typeof extensionModule.deactivate === 'function') await extensionModule.deactivate();
    return null;
  },
};

const dispatch = async (message) => {
  const handler = handlers[message.method];
  if (!handler) throw new Error(`unknown host method "${message.method}"`);
  return handler(message.params || {});
};

let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let newline;
  while ((newline = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, newline).trim();
    buffer = buffer.slice(newline + 1);
    if (!line) continue;
    let message;
    try {
      message = JSON.parse(line);
    } catch (err) {
      log('error', `bad frame: ${line.slice(0, 200)}`);
      continue;
    }
    // A reply to something we asked for.
    if (message.id !== undefined && message.method === undefined) {
      const waiting = pending.get(message.id);
      if (!waiting) continue;
      pending.delete(message.id);
      if (message.error) waiting.reject(new Error(message.error));
      else waiting.resolve(message.result);
      continue;
    }
    // A request from JCode.
    Promise.resolve()
      .then(() => dispatch(message))
      .then(
        (result) => {
          if (message.id !== undefined) send({ id: message.id, result: result === undefined ? null : result });
        },
        (err) => {
          const text = (err && err.stack) || String(err);
          if (message.id !== undefined) send({ id: message.id, error: (err && err.message) || String(err) });
          else log('error', text);
        },
      );
  }
});

process.on('uncaughtException', (err) => log('error', `uncaught: ${(err && err.stack) || err}`));
process.on('unhandledRejection', (err) => log('error', `unhandled rejection: ${(err && err.stack) || err}`));

notify('host/ready', { id: EXT_ID, node: process.version });
