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

// --- value types ---
//
// The plain data of the API: positions, ranges, edits, notebook output items. These are not
// optional in the way a feature is. An extension bundle builds them at module load — Claude Code's
// very first failure was `NotebookCellOutputItem.error(…)` running before activate() was ever
// called — and passes them back through the members below, so they have to be real classes with
// real behaviour rather than anything that merely exists.

class Position {
  constructor(line, character) {
    this.line = Math.max(0, line | 0);
    this.character = Math.max(0, character | 0);
  }
  isBefore(other) {
    return this.line < other.line || (this.line === other.line && this.character < other.character);
  }
  isBeforeOrEqual(other) {
    return this.isBefore(other) || this.isEqual(other);
  }
  isAfter(other) {
    return !this.isBeforeOrEqual(other);
  }
  isAfterOrEqual(other) {
    return !this.isBefore(other);
  }
  isEqual(other) {
    return this.line === other.line && this.character === other.character;
  }
  compareTo(other) {
    return this.isBefore(other) ? -1 : this.isEqual(other) ? 0 : 1;
  }
  translate(lineDelta, characterDelta) {
    const d = typeof lineDelta === 'object' && lineDelta !== null ? lineDelta : { lineDelta, characterDelta };
    return new Position(this.line + (d.lineDelta || 0), this.character + (d.characterDelta || 0));
  }
  with(line, character) {
    const c = typeof line === 'object' && line !== null ? line : { line, character };
    return new Position(c.line === undefined ? this.line : c.line, c.character === undefined ? this.character : c.character);
  }
}

class Range {
  constructor(startLine, startCharacter, endLine, endCharacter) {
    // Both shapes: (Position, Position) and (line, char, line, char).
    const start = startLine instanceof Position ? startLine : new Position(startLine, startCharacter);
    const end = startLine instanceof Position ? startCharacter : new Position(endLine, endCharacter);
    // VS Code keeps a Range sorted whichever way round it was built.
    const flip = end.isBefore(start);
    this.start = flip ? end : start;
    this.end = flip ? start : end;
  }
  get isEmpty() {
    return this.start.isEqual(this.end);
  }
  get isSingleLine() {
    return this.start.line === this.end.line;
  }
  contains(positionOrRange) {
    const from = positionOrRange instanceof Range ? positionOrRange.start : positionOrRange;
    const to = positionOrRange instanceof Range ? positionOrRange.end : positionOrRange;
    return !from.isBefore(this.start) && !to.isAfter(this.end);
  }
  isEqual(other) {
    return this.start.isEqual(other.start) && this.end.isEqual(other.end);
  }
  intersection(other) {
    const start = this.start.isAfter(other.start) ? this.start : other.start;
    const end = this.end.isBefore(other.end) ? this.end : other.end;
    return start.isAfter(end) ? undefined : new Range(start, end);
  }
  union(other) {
    return new Range(
      this.start.isBefore(other.start) ? this.start : other.start,
      this.end.isAfter(other.end) ? this.end : other.end,
    );
  }
  with(start, end) {
    const c = start instanceof Position || start === undefined ? { start, end } : start;
    return new Range(c.start === undefined ? this.start : c.start, c.end === undefined ? this.end : c.end);
  }
}

class Selection extends Range {
  constructor(anchorLine, anchorCharacter, activeLine, activeCharacter) {
    const anchor = anchorLine instanceof Position ? anchorLine : new Position(anchorLine, anchorCharacter);
    const active = anchorLine instanceof Position ? anchorCharacter : new Position(activeLine, activeCharacter);
    super(anchor, active);
    this.anchor = anchor;
    this.active = active;
  }
  get isReversed() {
    return this.anchor.isAfter(this.active);
  }
}

class Location {
  constructor(uri, rangeOrPosition) {
    this.uri = uri;
    this.range = rangeOrPosition instanceof Range ? rangeOrPosition : new Range(rangeOrPosition, rangeOrPosition);
  }
}

class TextEdit {
  constructor(range, newText) {
    this.range = range;
    this.newText = newText;
  }
  static replace(range, newText) {
    return new TextEdit(range, newText);
  }
  static insert(position, newText) {
    return new TextEdit(new Range(position, position), newText);
  }
  static delete(range) {
    return new TextEdit(range, '');
  }
  static setEndOfLine() {
    return new TextEdit(new Range(0, 0, 0, 0), '');
  }
}

/** Edits collected per file, applied by `workspace.applyEdit`. */
class WorkspaceEdit {
  constructor() {
    this._edits = new Map();
  }
  get size() {
    return this._edits.size;
  }
  set(uri, edits) {
    this._edits.set(String(uri && uri.fsPath ? uri.fsPath : uri), edits || []);
  }
  get(uri) {
    return this._edits.get(String(uri && uri.fsPath ? uri.fsPath : uri)) || [];
  }
  has(uri) {
    return this._edits.has(String(uri && uri.fsPath ? uri.fsPath : uri));
  }
  replace(uri, range, newText) {
    const key = String(uri && uri.fsPath ? uri.fsPath : uri);
    const list = this._edits.get(key) || [];
    list.push(new TextEdit(range, newText));
    this._edits.set(key, list);
  }
  insert(uri, position, newText) {
    this.replace(uri, new Range(position, position), newText);
  }
  delete(uri, range) {
    this.replace(uri, range, '');
  }
  entries() {
    return [...this._edits.entries()].map(([file, edits]) => [Uri.file(file), edits]);
  }
}

class Diagnostic {
  constructor(range, message, severity) {
    this.range = range;
    this.message = message;
    this.severity = severity === undefined ? 0 : severity;
    this.source = undefined;
  }
}

class CodeLens {
  constructor(range, command) {
    this.range = range;
    this.command = command;
  }
  get isResolved() {
    return !!this.command;
  }
}

class MarkdownString {
  constructor(value, supportThemeIcons) {
    this.value = value || '';
    this.isTrusted = undefined;
    this.supportThemeIcons = !!supportThemeIcons;
    this.supportHtml = undefined;
  }
  appendText(text) {
    this.value += String(text).replace(/[\\`*_{}[\]()#+\-.!]/g, '\\$&');
    return this;
  }
  appendMarkdown(text) {
    this.value += text;
    return this;
  }
  appendCodeblock(code, language) {
    this.value += `\n\`\`\`${language || ''}\n${code}\n\`\`\`\n`;
    return this;
  }
}

class SnippetString {
  constructor(value) {
    this.value = value || '';
  }
  appendText(text) {
    this.value += String(text).replace(/[$}\\]/g, '\\$&');
    return this;
  }
  appendTabstop(number) {
    this.value += `$${number === undefined ? 0 : number}`;
    return this;
  }
  appendPlaceholder(value, number) {
    this.value += `\${${number === undefined ? 0 : number}:${value}}`;
    return this;
  }
  appendChoice(values, number) {
    this.value += `\${${number === undefined ? 0 : number}|${values.join(',')}|}`;
    return this;
  }
}

class ThemeIcon {
  constructor(id, color) {
    this.id = id;
    this.color = color;
  }
}
ThemeIcon.File = new ThemeIcon('file');
ThemeIcon.Folder = new ThemeIcon('folder');

class ThemeColor {
  constructor(id) {
    this.id = id;
  }
}

class RelativePattern {
  constructor(base, pattern) {
    const baseUri = base && base.uri ? base.uri : base;
    this.baseUri = typeof baseUri === 'string' ? Uri.file(baseUri) : baseUri;
    this.base = this.baseUri && this.baseUri.fsPath;
    this.pattern = pattern;
  }
}

class CancellationTokenSource {
  constructor() {
    const onCancellationRequested = emitter();
    this._cancelled = false;
    const self = this;
    this.token = {
      get isCancellationRequested() {
        return self._cancelled;
      },
      onCancellationRequested,
    };
  }
  cancel() {
    if (this._cancelled) return;
    this._cancelled = true;
    this.token.onCancellationRequested.fire();
  }
  dispose() {}
}

/** A token that is never cancelled, for the many places one has to be handed over. */
const noCancellation = () => new CancellationTokenSource().token;

/**
 * `context.environmentVariableCollection` — the environment an extension wants terminals to start
 * with. A full collection rather than a set of no-ops: an extension reads back what it wrote
 * (Claude Code's MCP setup calls `.get`, and failed on a stub that only had the setters), and the
 * values are real here because they are applied to the terminals `window.createTerminal` opens.
 */
const makeEnvironmentCollection = () => {
  const store = new Map();
  const mutate = (type) => (variable, value, options) => store.set(variable, { value, type, options: options || {} });
  const collection = {
    persistent: true,
    description: undefined,
    replace: mutate(1),
    append: mutate(2),
    prepend: mutate(3),
    get: (variable) => store.get(variable),
    forEach: (fn, thisArg) => store.forEach((mutator, variable) => fn.call(thisArg, variable, mutator, collection)),
    delete: (variable) => store.delete(variable),
    clear: () => store.clear(),
    [Symbol.iterator]: () => store.entries(),
    // Scoping selects which terminals a mutation applies to; JCode has one terminal environment, so
    // every scope is the same collection rather than a silently separate one.
    getScoped: () => collection,
    /** The collection resolved against [base], as a terminal should start. */
    applyTo: (base) => {
      const out = { ...base };
      store.forEach(({ value, type }, name) => {
        if (type === 2) out[name] = `${out[name] === undefined ? '' : out[name]}${value}`;
        else if (type === 3) out[name] = `${value}${out[name] === undefined ? '' : out[name]}`;
        else out[name] = value;
      });
      return out;
    },
  };
  return collection;
};

const environmentVariables = makeEnvironmentCollection();

class NotebookCellOutputItem {
  constructor(data, mime) {
    this.data = data;
    this.mime = mime;
  }
  static text(value, mime) {
    return new NotebookCellOutputItem(Buffer.from(String(value), 'utf8'), mime || 'text/plain');
  }
  static json(value, mime) {
    return new NotebookCellOutputItem(Buffer.from(JSON.stringify(value), 'utf8'), mime || 'application/json');
  }
  static stdout(value) {
    return NotebookCellOutputItem.text(value, 'application/vnd.code.notebook.stdout');
  }
  static stderr(value) {
    return NotebookCellOutputItem.text(value, 'application/vnd.code.notebook.stderr');
  }
  static error(err) {
    const value = { name: (err && err.name) || 'Error', message: (err && err.message) || String(err), stack: err && err.stack };
    return new NotebookCellOutputItem(
      Buffer.from(JSON.stringify(value), 'utf8'),
      'application/vnd.code.notebook.error',
    );
  }
}

class NotebookCellOutput {
  constructor(items, metadata) {
    this.items = items || [];
    this.metadata = metadata;
  }
}

/** VS Code's own error factory for filesystem providers; extensions compare against `.code`. */
class FileSystemError extends Error {
  constructor(messageOrUri, code) {
    super(String(messageOrUri && messageOrUri.fsPath ? messageOrUri.fsPath : messageOrUri || code));
    this.name = code || 'FileSystemError';
    this.code = code || 'Unknown';
  }
  static FileExists(m) {
    return new FileSystemError(m, 'FileExists');
  }
  static FileNotFound(m) {
    return new FileSystemError(m, 'FileNotFound');
  }
  static FileNotADirectory(m) {
    return new FileSystemError(m, 'FileNotADirectory');
  }
  static FileIsADirectory(m) {
    return new FileSystemError(m, 'FileIsADirectory');
  }
  static NoPermissions(m) {
    return new FileSystemError(m, 'NoPermissions');
  }
  static Unavailable(m) {
    return new FileSystemError(m, 'Unavailable');
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
const terminals = [];
const uriHandlers = [];
const fileSystemProviders = new Map();
const textDocumentContentProviders = new Map();
const openDocuments = new Map();
let nextTerminalId = 1;
let nextDecorationId = 1;

const onDidOpenTerminal = emitter();
const onDidCloseTerminal = emitter();
const onDidOpenTextDocument = emitter();
const onDidCloseTextDocument = emitter();
const onDidChangeTextDocument = emitter();
const onDidSaveTextDocument = emitter();
const onWillSaveTextDocument = emitter();
const onDidChangeTabs = emitter();
const onDidChangeTabGroups = emitter();

let workspaceState = { folders: [], configuration: {} };
let activeTextEditor;
let windowState = { focused: true, active: true };

/**
 * The editor tab model, derived from the one thing JCode reports: which file is active.
 *
 * Kept as a live view rather than a snapshot so an extension that holds on to `tabGroups` — Claude
 * Code reads it in a dozen places — keeps seeing the current file rather than the one that happened
 * to be open when it looked first.
 */
const tabGroups = {
  get all() {
    return [tabGroups.activeTabGroup];
  },
  get activeTabGroup() {
    const tabs = activeTextEditor
      ? [
          {
            label: path.posix.basename(activeTextEditor.document.uri.fsPath),
            input: new vscode.TabInputText(activeTextEditor.document.uri),
            isActive: true,
            isDirty: activeTextEditor.document.isDirty,
            isPinned: false,
            isPreview: false,
          },
        ]
      : [];
    return { isActive: true, viewColumn: 1, activeTab: tabs[0], tabs };
  },
  onDidChangeTabs,
  onDidChangeTabGroups,
  close: () => Promise.resolve(true),
};

/** A TextEditor over [document]; the shape extensions read after opening a file. */
const editorFor = (document, selection) => {
  const at = selection || new Selection(0, 0, 0, 0);
  return {
    document,
    selection: at,
    selections: [at],
    visibleRanges: [at],
    viewColumn: 1,
    options: { tabSize: 4, insertSpaces: true },
    // Editing through the editor object is not wired to JCode's buffer; an extension that wants to
    // change a file has workspace.applyEdit, which is.
    edit: () => Promise.resolve(false),
    insertSnippet: () => Promise.resolve(false),
    setDecorations: () => {},
    revealRange: () => {},
    show: () => {},
    hide: () => {},
  };
};

/** A TextDocument backed by real bytes, as the runtime filesystem sees them. */
const makeDocument = (uri, text, languageId) => {
  const lines = text.split('\n');
  const document = {
    uri,
    get fileName() {
      return uri.fsPath;
    },
    languageId: languageId || languageOf(uri.fsPath),
    version: 1,
    isUntitled: uri.scheme === 'untitled',
    isDirty: false,
    isClosed: false,
    eol: 1,
    get lineCount() {
      return lines.length;
    },
    getText: (range) => {
      if (!range) return text;
      const out = [];
      for (let i = range.start.line; i <= Math.min(range.end.line, lines.length - 1); i++) {
        const line = lines[i] === undefined ? '' : lines[i];
        const from = i === range.start.line ? range.start.character : 0;
        const to = i === range.end.line ? range.end.character : line.length;
        out.push(line.slice(from, to));
      }
      return out.join('\n');
    },
    lineAt: (lineOrPosition) => {
      const index = typeof lineOrPosition === 'number' ? lineOrPosition : lineOrPosition.line;
      const value = lines[index] === undefined ? '' : lines[index];
      return {
        lineNumber: index,
        text: value,
        range: new Range(index, 0, index, value.length),
        rangeIncludingLineBreak: new Range(index, 0, index, value.length + 1),
        firstNonWhitespaceCharacterIndex: value.length - value.trimStart().length,
        isEmptyOrWhitespace: value.trim().length === 0,
      };
    },
    offsetAt: (position) => {
      let offset = 0;
      for (let i = 0; i < position.line && i < lines.length; i++) offset += lines[i].length + 1;
      return offset + position.character;
    },
    positionAt: (offset) => {
      let remaining = Math.max(0, offset);
      for (let i = 0; i < lines.length; i++) {
        if (remaining <= lines[i].length) return new Position(i, remaining);
        remaining -= lines[i].length + 1;
      }
      return new Position(Math.max(0, lines.length - 1), 0);
    },
    getWordRangeAtPosition: (position, regex) => {
      const line = lines[position.line] || '';
      const re = new RegExp((regex && regex.source) || '[A-Za-z0-9_$]+', 'g');
      let m;
      while ((m = re.exec(line))) {
        if (m.index <= position.character && position.character <= m.index + m[0].length) {
          return new Range(position.line, m.index, position.line, m.index + m[0].length);
        }
      }
      return undefined;
    },
    validateRange: (range) => range,
    validatePosition: (position) => position,
    save: () => vscode.workspace.fs.writeFile(uri, Buffer.from(text, 'utf8')).then(() => true),
  };
  return document;
};

/** A language id from the extension, enough for the `languageId` checks extensions make. */
const languageOf = (file) => {
  const ext = path.posix.extname(String(file)).slice(1).toLowerCase();
  const table = {
    ts: 'typescript', tsx: 'typescriptreact', js: 'javascript', jsx: 'javascriptreact', mjs: 'javascript',
    cjs: 'javascript', json: 'json', jsonc: 'jsonc', md: 'markdown', py: 'python', rb: 'ruby', go: 'go',
    rs: 'rust', java: 'java', kt: 'kotlin', kts: 'kotlin', c: 'c', h: 'c', cpp: 'cpp', cc: 'cpp',
    hpp: 'cpp', cs: 'csharp', php: 'php', sh: 'shellscript', bash: 'shellscript', zsh: 'shellscript',
    yml: 'yaml', yaml: 'yaml', toml: 'toml', xml: 'xml', html: 'html', css: 'css', scss: 'scss',
    sql: 'sql', swift: 'swift', lua: 'lua', dart: 'dart', vue: 'vue', svelte: 'svelte',
  };
  return table[ext] || 'plaintext';
};

/** A glob as `findFiles` gets it, as a regex over a workspace-relative path. */
const globToRegExp = (glob) => {
  let out = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*' && glob[i + 1] === '*') {
      // `**/` may match nothing at all, so the separator is part of the optional group.
      out += glob[i + 2] === '/' ? '(?:.*/)?' : '.*';
      i += glob[i + 2] === '/' ? 2 : 1;
    } else if (c === '*') out += '[^/]*';
    else if (c === '?') out += '[^/]';
    else if (c === '{') out += '(?:';
    else if (c === '}') out += ')';
    else if (c === ',') out += '|';
    else out += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${out}$`);
};

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
  // Value types (see above). Exposed by name because extensions both construct them and test with
  // `instanceof` — a duck-typed stand-in passes the first use and fails the second.
  Position,
  Range,
  Selection,
  Location,
  TextEdit,
  WorkspaceEdit,
  Diagnostic,
  CodeLens,
  MarkdownString,
  SnippetString,
  ThemeIcon,
  ThemeColor,
  RelativePattern,
  CancellationTokenSource,
  NotebookCellOutputItem,
  NotebookCellOutput,
  FileSystemError,

  ExtensionMode: { Production: 1, Development: 2, Test: 3 },
  ExtensionKind: { UI: 1, Workspace: 2 },
  ConfigurationTarget: { Global: 1, Workspace: 2, WorkspaceFolder: 3 },
  ColorThemeKind: { Light: 1, Dark: 2, HighContrast: 3, HighContrastLight: 4 },
  ViewColumn: { Active: -1, Beside: -2, One: 1, Two: 2, Three: 3 },
  FileType: { Unknown: 0, File: 1, Directory: 2, SymbolicLink: 64 },
  FileChangeType: { Changed: 1, Created: 2, Deleted: 3 },
  StatusBarAlignment: { Left: 1, Right: 2 },
  TreeItemCollapsibleState: { None: 0, Collapsed: 1, Expanded: 2 },
  DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2, Hint: 3 },
  EndOfLine: { LF: 1, CRLF: 2 },
  ProgressLocation: { SourceControl: 1, Window: 10, Notification: 15 },
  QuickPickItemKind: { Separator: -1, Default: 0 },
  TextDocumentSaveReason: { Manual: 1, AfterDelay: 2, FocusOut: 3 },
  TextEditorRevealType: { Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3 },
  TextEditorSelectionChangeKind: { Keyboard: 1, Mouse: 2, Command: 3 },
  DecorationRangeBehavior: { OpenOpen: 0, ClosedClosed: 1, OpenClosed: 2, ClosedOpen: 3 },
  OverviewRulerLane: { Left: 1, Center: 2, Right: 4, Full: 7 },
  // JCode is a local editor with one window: `Desktop`, never `Web`.
  UIKind: { Desktop: 1, Web: 2 },
  LogLevel: { Off: 0, Trace: 1, Debug: 2, Info: 3, Warning: 4, Error: 5 },
  TerminalLocation: { Panel: 1, Editor: 2 },
  // The tab-input types exist so `tab.input instanceof vscode.TabInputText` can answer at all.
  TabInputText: class TabInputText {
    constructor(uri) {
      this.uri = uri;
    }
  },
  TabInputTextDiff: class TabInputTextDiff {
    constructor(original, modified) {
      this.original = original;
      this.modified = modified;
    }
  },
  TabInputWebview: class TabInputWebview {
    constructor(viewType) {
      this.viewType = viewType;
    }
  },
  TabInputNotebook: class TabInputNotebook {
    constructor(uri, notebookType) {
      this.uri = uri;
      this.notebookType = notebookType;
    }
  },
  TabInputCustom: class TabInputCustom {
    constructor(uri, viewType) {
      this.uri = uri;
      this.viewType = viewType;
    }
  },
  TabInputTerminal: class TabInputTerminal {},

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
    /**
     * An output channel, or a LogOutputChannel when asked for one.
     *
     * `createOutputChannel(name, { log: true })` is a different type in VS Code — it carries
     * `trace/debug/info/warn/error` instead of just `append`. Extensions build their logger on it at
     * construction time, so the levelled members have to be there; they are added unconditionally
     * because a channel that has them spare costs nothing and one that lacks them is fatal.
     */
    createOutputChannel: (name, options) => {
      const existing = outputChannels.get(name);
      if (existing) return existing;
      const write = (text) => notify('output/append', { name, text });
      const level = (tag) => (message, ...rest) => {
        const extra = rest.length ? ` ${rest.map((r) => (r && r.stack) || (typeof r === 'object' ? JSON.stringify(r) : String(r))).join(' ')}` : '';
        write(`[${tag}] ${message instanceof Error ? message.stack || message.message : message}${extra}\n`);
      };
      const channel = {
        name,
        append: write,
        appendLine: (text) => write(`${text}\n`),
        replace: (text) => notify('output/replace', { name, text }),
        clear: () => notify('output/clear', { name }),
        show: () => notify('output/show', { name }),
        hide: () => {},
        dispose: () => outputChannels.delete(name),
        // LogOutputChannel
        logLevel: 3,
        onDidChangeLogLevel: emitter(),
        trace: level('trace'),
        debug: level('debug'),
        info: level('info'),
        warn: level('warn'),
        error: level('error'),
      };
      if (options && options.log) channel.languageId = 'log';
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
    withProgress: (_options, task) => Promise.resolve(task({ report: () => {} }, noCancellation())),
    setStatusBarMessage: () => disposable(),
    createStatusBarItem: () => ({
      text: '',
      tooltip: '',
      command: undefined,
      show: () => {},
      hide: () => {},
      dispose: () => {},
    }),

    /** Open a file in JCode's editor and report an editor for it. */
    showTextDocument: async (target, options) => {
      const uri = target && target.uri ? target.uri : target;
      const document = target && target.getText ? target : await vscode.workspace.openTextDocument(uri);
      const selection = options && options.selection;
      await call('window/showTextDocument', {
        path: document.uri.fsPath,
        line: selection ? selection.start.line + 1 : 0,
        column: selection ? selection.start.character + 1 : 0,
      });
      activeTextEditor = editorFor(document, selection);
      return activeTextEditor;
    },

    /**
     * A terminal in JCode's own terminal panel. Real rather than a stand-in because JCode has
     * terminals and an extension's whole point can be to run something in one — Claude Code offers
     * to run its CLI that way.
     */
    createTerminal: (optionsOrName, shellPath, shellArgs) => {
      const options = typeof optionsOrName === 'string' ? { name: optionsOrName, shellPath, shellArgs } : optionsOrName || {};
      const name = options.name || EXT_ID;
      const id = `term-${nextTerminalId++}`;
      const terminal = {
        name,
        processId: Promise.resolve(undefined),
        creationOptions: options,
        exitStatus: undefined,
        state: { isInteractedWith: false },
        // Present but never populated: JCode's terminal does not report command boundaries back, so
        // an extension that waits on shell integration would wait forever. Absent is the answer VS
        // Code itself gives for a terminal without it, and extensions handle that.
        shellIntegration: undefined,
        sendText: (text, addNewLine) => notify('terminal/sendText', { id, text: String(text), newline: addNewLine !== false }),
        show: (preserveFocus) => notify('terminal/show', { id, preserveFocus: !!preserveFocus }),
        hide: () => notify('terminal/hide', { id }),
        dispose: () => {
          terminals.splice(terminals.indexOf(terminal), 1);
          notify('terminal/dispose', { id });
          onDidCloseTerminal.fire(terminal);
        },
      };
      terminals.push(terminal);
      notify('terminal/create', {
        id,
        name,
        cwd: String((options.cwd && (options.cwd.fsPath || options.cwd)) || ''),
        // What the extension asked terminals to start with, resolved over what it passed directly.
        env: environmentVariables.applyTo(options.env || {}),
      });
      onDidOpenTerminal.fire(terminal);
      return terminal;
    },
    get terminals() {
      return terminals;
    },
    get activeTerminal() {
      return terminals[terminals.length - 1];
    },
    onDidOpenTerminal,
    onDidCloseTerminal,
    onDidChangeActiveTerminal: emitter(),
    onDidChangeTerminalState: emitter(),
    // Shell integration is not reported by JCode's terminal, so these never fire. They exist because
    // an extension subscribes during activate() and must not die there.
    onDidChangeTerminalShellIntegration: emitter(),
    onDidStartTerminalShellExecution: emitter(),
    onDidEndTerminalShellExecution: emitter(),

    /**
     * The editor tabs, as far as the host can know them.
     *
     * JCode tells the host which file is active and nothing more, so this reports that one tab
     * rather than pretending to a full model of the editor area. An extension reading it to find
     * "the file the user is looking at" — which is what these calls are for — gets the right answer;
     * one enumerating every open editor gets a short list rather than a wrong one.
     */
    get tabGroups() {
      return tabGroups;
    },

    activeNotebookEditor: undefined,
    visibleNotebookEditors: [],
    onDidChangeActiveNotebookEditor: emitter(),

    // Registration points. Accepted and held rather than refused: they are called during activate(),
    // where throwing would take the whole extension down over a feature it may never use. What JCode
    // cannot show simply never gets asked for.
    registerUriHandler: (handler) => {
      uriHandlers.push(handler);
      return disposable(() => uriHandlers.splice(uriHandlers.indexOf(handler), 1));
    },
    registerWebviewPanelSerializer: () => disposable(),
    registerCustomEditorProvider: () => disposable(),
    registerTerminalLinkProvider: () => disposable(),
    registerFileDecorationProvider: () => disposable(),
    registerTreeDataProvider: () => disposable(),
    // Decorations are a rendering feature of an editor JCode draws itself, so the type is inert.
    createTextEditorDecorationType: (options) => ({ key: `decoration-${nextDecorationId++}`, options, dispose: () => {} }),

    createTreeView: missing('window.createTreeView'),
    showOpenDialog: missing('window.showOpenDialog'),
    showSaveDialog: missing('window.showSaveDialog'),
  },

  workspace: {
    get workspaceFolders() {
      return workspaceState.folders.length ? workspaceState.folders : undefined;
    },
    get name() {
      return workspaceState.folders[0] && workspaceState.folders[0].name;
    },
    get rootPath() {
      return workspaceState.folders[0] && workspaceState.folders[0].uri.fsPath;
    },
    workspaceFile: undefined,
    // A workspace opened in JCode is the user's own; there is no restricted mode to be outside of.
    isTrusted: true,
    onDidGrantWorkspaceTrust: emitter(),
    get textDocuments() {
      return [...openDocuments.values()];
    },
    onDidChangeWorkspaceFolders,
    onDidChangeConfiguration,
    onDidSaveTextDocument,
    onDidOpenTextDocument,
    onDidCloseTextDocument,
    onDidChangeTextDocument,
    onWillSaveTextDocument,
    onDidCreateFiles: emitter(),
    onDidDeleteFiles: emitter(),
    onDidRenameFiles: emitter(),
    onWillCreateFiles: emitter(),
    onWillDeleteFiles: emitter(),
    onWillRenameFiles: emitter(),
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
    isWritableFileSystem: (scheme) => scheme === 'file',

    /**
     * Open a document. Real, because the host shares the runtime's filesystem — the same reason
     * `fs` above is real. Takes the three shapes VS Code does: a Uri, a path, or `{content, language}`
     * for an untitled buffer. A custom scheme is served by whatever provider registered it.
     */
    openTextDocument: async (target, maybeOptions) => {
      if (target === undefined || (target && typeof target === 'object' && !target.scheme && !target.fsPath)) {
        const options = target || maybeOptions || {};
        const uri = new Uri('untitled', '', `/untitled-${openDocuments.size + 1}`);
        const document = makeDocument(uri, options.content || '', options.language);
        openDocuments.set(String(uri), document);
        onDidOpenTextDocument.fire(document);
        return document;
      }
      const uri = typeof target === 'string' ? Uri.file(target) : target;
      const key = String(uri);
      const existing = openDocuments.get(key);
      if (existing) return existing;

      let text;
      if (uri.scheme && uri.scheme !== 'file') {
        const provider = textDocumentContentProviders.get(uri.scheme);
        const fsp = fileSystemProviders.get(uri.scheme);
        if (provider) text = await provider.provideTextDocumentContent(uri, noCancellation());
        else if (fsp) text = Buffer.from(await fsp.readFile(uri)).toString('utf8');
        else throw FileSystemError.Unavailable(`no provider registered for the "${uri.scheme}" scheme`);
      } else {
        text = await fs.promises.readFile(uri.fsPath, 'utf8');
      }
      const document = makeDocument(uri, text === undefined || text === null ? '' : String(text));
      openDocuments.set(key, document);
      onDidOpenTextDocument.fire(document);
      return document;
    },

    createFileSystemWatcher: () => ({
      onDidCreate: emitter(),
      onDidChange: emitter(),
      onDidDelete: emitter(),
      dispose: () => {},
    }),

    /** Walk the workspace for [include], honouring [exclude] and [maxResults]. */
    findFiles: async (include, exclude, maxResults) => {
      const pattern = include && include.pattern ? include.pattern : String(include || '**/*');
      const base = (include && include.base) || (workspaceState.folders[0] && workspaceState.folders[0].uri.fsPath);
      if (!base) return [];
      const match = globToRegExp(pattern);
      const skip = exclude ? globToRegExp(exclude.pattern ? exclude.pattern : String(exclude)) : null;
      const limit = maxResults === undefined || maxResults <= 0 ? Infinity : maxResults;
      const found = [];
      const walk = async (dir, rel) => {
        if (found.length >= limit) return;
        let entries;
        try {
          entries = await fs.promises.readdir(dir, { withFileTypes: true });
        } catch {
          return; // unreadable directory: skipped, not fatal
        }
        for (const entry of entries) {
          if (found.length >= limit) return;
          const childRel = rel ? `${rel}/${entry.name}` : entry.name;
          // Never worth descending, and on a phone the cost of doing so is the whole call.
          if (entry.isDirectory()) {
            if (entry.name === '.git' || entry.name === 'node_modules') continue;
            if (skip && skip.test(childRel)) continue;
            await walk(path.posix.join(dir, entry.name), childRel);
          } else if (match.test(childRel) && !(skip && skip.test(childRel))) {
            found.push(Uri.file(path.posix.join(base, childRel)));
          }
        }
      };
      await walk(base, '');
      return found;
    },

    /** Apply a WorkspaceEdit to the files it names. */
    applyEdit: async (edit) => {
      if (!edit || typeof edit.entries !== 'function') return false;
      for (const [uri, edits] of edit.entries()) {
        const file = uri.fsPath;
        let text = await fs.promises.readFile(file, 'utf8').catch(() => '');
        const document = makeDocument(uri, text);
        // Back to front, so an earlier edit's offsets are not shifted by a later one.
        const ordered = [...edits].sort((a, b) => b.range.start.compareTo(a.range.start));
        for (const one of ordered) {
          const from = document.offsetAt(one.range.start);
          const to = document.offsetAt(one.range.end);
          text = text.slice(0, from) + one.newText + text.slice(to);
        }
        await fs.promises.writeFile(file, text);
        const reopened = makeDocument(uri, text);
        openDocuments.set(String(uri), reopened);
        onDidChangeTextDocument.fire({ document: reopened, contentChanges: [], reason: undefined });
      }
      return true;
    },

    save: (uri) => Promise.resolve(uri),
    saveAll: () => Promise.resolve(true),

    // Held so `openTextDocument` can serve a custom scheme through them. Registering is accepted
    // during activate() whether or not JCode ever shows such a document.
    registerFileSystemProvider: (scheme, provider) => {
      fileSystemProviders.set(scheme, provider);
      return disposable(() => fileSystemProviders.delete(scheme));
    },
    registerTextDocumentContentProvider: (scheme, provider) => {
      textDocumentContentProviders.set(scheme, provider);
      return disposable(() => textDocumentContentProviders.delete(scheme));
    },
  },

  env: {
    appName: 'JCode',
    appHost: 'jcode',
    uriScheme: 'jcode',
    language: 'en',
    machineId: 'jcode',
    sessionId: `jcode-${Date.now()}`,
    appRoot: EXT_DIR,
    // The host runs in JCode's own Linux runtime, not over a remote connection: `remoteName` is
    // undefined for the same reason it is in a local VS Code window. Extensions branch on this to
    // decide whether their binaries can reach the workspace — here they can.
    remoteName: undefined,
    uiKind: 1,
    shell: process.env.SHELL || '/bin/bash',
    isNewAppInstall: false,
    isTelemetryEnabled: false,
    onDidChangeTelemetryEnabled: emitter(),
    onDidChangeShell: emitter(),
    logLevel: 3,
    onDidChangeLogLevel: emitter(),
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

  /**
   * The namespaces JCode has its own systems for.
   *
   * Registering into one is accepted and dropped; asking one to *do* something still throws by
   * name. The split matters: registration happens during activate(), where a throw takes the whole
   * extension down over a feature the user may never touch, while a call is a thing the user just
   * asked for and deserves a straight answer about.
   */
  languages: registryNamespace('languages', {
    // JCode owns diagnostics ([[Issues]] pane) and does not publish them to extensions, so the
    // honest answer is that this extension can see none — not that the call does not exist.
    getDiagnostics: (uri) => (uri ? [] : []),
    onDidChangeDiagnostics: emitter(),
    createDiagnosticCollection: (name) => {
      const store = new Map();
      return {
        name,
        set: (uri, diagnostics) => store.set(String(uri), diagnostics),
        get: (uri) => store.get(String(uri)) || [],
        delete: (uri) => store.delete(String(uri)),
        clear: () => store.clear(),
        forEach: (fn) => store.forEach((value, key) => fn(Uri.parse(key), value)),
        dispose: () => store.clear(),
      };
    },
    getLanguages: () => Promise.resolve([]),
    setTextDocumentLanguage: (document) => Promise.resolve(document),
    match: () => 0,
  }),
  debug: registryNamespace('debug', {
    activeDebugSession: undefined,
    breakpoints: [],
    onDidChangeActiveDebugSession: emitter(),
    onDidStartDebugSession: emitter(),
    onDidTerminateDebugSession: emitter(),
    onDidChangeBreakpoints: emitter(),
    addBreakpoints: () => {},
    removeBreakpoints: () => {},
  }),
  tasks: registryNamespace('tasks', {
    taskExecutions: [],
    onDidStartTask: emitter(),
    onDidEndTask: emitter(),
    onDidStartTaskProcess: emitter(),
    onDidEndTaskProcess: emitter(),
  }),
  scm: registryNamespace('scm', {}),
  comments: registryNamespace('comments', {}),
  notebooks: registryNamespace('notebooks', {}),
  tests: registryNamespace('tests', {}),
  authentication: registryNamespace('authentication', {
    onDidChangeSessions: emitter(),
    // No identity provider to consult, and `undefined` is what VS Code returns when there is no
    // session — an extension that asked without `createIfNone` handles it.
    getSession: () => Promise.resolve(undefined),
  }),

  /**
   * Language models. JCode brokers none, and saying so is what makes these extensions usable: told
   * there is no proxy, Codex falls back to the CLI it ships — which is the path that works here.
   */
  lm: registryNamespace('lm', {
    isModelProxyAvailable: () => false,
    onDidChangeModelProxyAvailability: emitter(),
    onDidChangeChatModels: emitter(),
    selectChatModels: () => Promise.resolve([]),
    tools: [],
  }),
  chat: registryNamespace('chat', {}),

  extensions: {
    /**
     * An extension looking itself up wants its own version or path, and it must find itself: Claude
     * Code reads `getExtension(id).packageJSON` during activate() with no null check. The id is
     * matched case-insensitively and against the manifest's own `publisher.name`, because the id
     * JCode installed under is not always spelled the way the extension spells it.
     *
     * Any other id is a dependency JCode does not host, and `undefined` is what VS Code answers for
     * one that is not installed.
     */
    getExtension: (id) => (isSelf(id) ? selfExtension() : undefined),
    get all() {
      return [selfExtension()];
    },
    onDidChange: emitter(),
  },
};

const isSelf = (id) => {
  const wanted = String(id || '').toLowerCase();
  const pkg = extensionPackageJson();
  return wanted === String(EXT_ID).toLowerCase() || wanted === `${pkg.publisher}.${pkg.name}`.toLowerCase();
};

const selfExtension = () => ({
  id: EXT_ID,
  extensionPath: EXT_DIR,
  extensionUri: Uri.file(EXT_DIR),
  extensionKind: 2,
  isActive: true,
  exports: undefined,
  packageJSON: extensionPackageJson(),
  activate: () => Promise.resolve(undefined),
});

/**
 * A namespace whose `register*`/`create*` members are accepted no-ops and whose [known] members are
 * answered, while anything else throws by name. See the note on `languages` above.
 */
function registryNamespace(name, known) {
  return new Proxy(known, {
    get: (target, key) => {
      if (key in target) return target[key];
      const member = String(key);
      if (member.startsWith('register')) return () => disposable();
      // Node and JSON.stringify probe objects with these; a throw here would be an error nobody asked for.
      if (typeof key === 'symbol' || member === 'then' || member === 'toJSON' || member === 'inspect') return undefined;
      return missing(`${name}.${member}`);
    },
  });
}

let packageJsonCache;
const extensionPackageJson = () => {
  if (packageJsonCache === undefined) {
    packageJsonCache = (() => {
      try {
        return JSON.parse(fs.readFileSync(path.join(EXT_DIR, 'package.json'), 'utf8'));
      } catch {
        return {};
      }
    })();
  }
  return packageJsonCache;
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
    // The context's view of the extension itself. Not decoration: Claude Code reads
    // `context.extension.packageJSON.version` while wiring up its MCP server, without a null check.
    extension: selfExtension(),
    storageUri: Uri.file(path.join(EXT_DIR, '.storage')),
    storagePath: path.join(EXT_DIR, '.storage'),
    globalStorageUri: Uri.file(path.join(EXT_DIR, '.global-storage')),
    globalStoragePath: path.join(EXT_DIR, '.global-storage'),
    logUri: Uri.file(path.join(EXT_DIR, '.logs')),
    logPath: path.join(EXT_DIR, '.logs'),
    languageModelAccessInformation: { onDidChange: emitter(), canSendRequest: () => undefined },
    environmentVariableCollection: environmentVariables,
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
          // Logged either way. A reply carries only the message, and a bare "Cannot read properties
          // of undefined" says nothing about which member was missing — the one thing worth knowing
          // when an extension dies inside activate().
          log('error', (err && err.stack) || String(err));
          if (message.id !== undefined) send({ id: message.id, error: (err && err.message) || String(err) });
        },
      );
  }
});

process.on('uncaughtException', (err) => log('error', `uncaught: ${(err && err.stack) || err}`));
process.on('unhandledRejection', (err) => log('error', `unhandled rejection: ${(err && err.stack) || err}`));

notify('host/ready', { id: EXT_ID, node: process.version });
