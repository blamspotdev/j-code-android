use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

use grep::matcher::Matcher;
use grep::regex::RegexMatcherBuilder;
use grep::searcher::sinks::UTF8;
use grep::searcher::{BinaryDetection, SearcherBuilder};
use ignore::overrides::OverrideBuilder;
use ignore::WalkBuilder;
use jni::objects::{JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::jint;
use jni::JNIEnv;

// Must mirror NativeSearch.kt (dev.jcode.core.search).
const FLAG_REGEX: jint = 1;
const FLAG_CASE_SENSITIVE: jint = 2;
const FLAG_WHOLE_WORD: jint = 4;
const FLAG_INCLUDE_HIDDEN: jint = 8;

const MAX_FILE_SIZE: u64 = 10 * 1024 * 1024;

/// Distinguishes the real cargo-built library from the CMake stub (which lacks this symbol):
/// the Kotlin side probes it after loadLibrary and falls back to the in-process search if absent.
#[no_mangle]
pub extern "system" fn Java_dev_jcode_core_search_NativeSearch_nativeProbe(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}

/// Walks `root` (gitignore-aware) and streams every match to `sink.onMatch(...)`.
/// Stops early when the sink returns false or `max_results` is reached. Returns matches emitted.
#[no_mangle]
pub extern "system" fn Java_dev_jcode_core_search_NativeSearch_nativeSearch(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
    query: JString,
    flags: jint,
    include_globs: JObjectArray,
    exclude_globs: JObjectArray,
    max_results: jint,
    sink: JObject,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        search_impl(
            &mut env,
            root,
            query,
            flags,
            include_globs,
            exclude_globs,
            max_results,
            sink,
        )
        .unwrap_or(0)
    }))
    .unwrap_or(0)
}

fn get_string_array(env: &mut JNIEnv, array: &JObjectArray) -> jni::errors::Result<Vec<String>> {
    let len = env.get_array_length(array)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let obj = env.get_object_array_element(array, i)?;
        let s: String = env.get_string(&JString::from(obj))?.into();
        out.push(s);
    }
    Ok(out)
}

#[allow(clippy::too_many_arguments)]
fn search_impl(
    env: &mut JNIEnv,
    root: JString,
    query: JString,
    flags: jint,
    include_globs: JObjectArray,
    exclude_globs: JObjectArray,
    max_results: jint,
    sink: JObject,
) -> jni::errors::Result<jint> {
    let root: String = env.get_string(&root)?.into();
    let query: String = env.get_string(&query)?.into();
    let includes = get_string_array(env, &include_globs)?;
    let excludes = get_string_array(env, &exclude_globs)?;

    let pattern = if flags & FLAG_REGEX != 0 {
        query
    } else {
        regex_escape(&query)
    };
    let matcher = match RegexMatcherBuilder::new()
        .case_insensitive(flags & FLAG_CASE_SENSITIVE == 0)
        .word(flags & FLAG_WHOLE_WORD != 0)
        .build(&pattern)
    {
        Ok(m) => m,
        Err(_) => return Ok(0),
    };

    let root_path = Path::new(&root);
    let mut overrides = OverrideBuilder::new(root_path);
    // The ignore crate only skips .git via hidden-file filtering, so exclude it explicitly for the
    // include-hidden case; binary/artifact globs mirror the in-process engine's defaults.
    for glob in ["!**/.git/**", "!.jcode/trash/**", "!*.class", "!*.jar", "!*.so", "!*.png", "!*.jpg", "!*.gif"] {
        let _ = overrides.add(glob);
    }
    for glob in &includes {
        let _ = overrides.add(glob);
    }
    for glob in &excludes {
        let _ = overrides.add(&format!("!{glob}"));
    }

    let mut walk = WalkBuilder::new(root_path);
    walk.hidden(flags & FLAG_INCLUDE_HIDDEN == 0)
        .max_filesize(Some(MAX_FILE_SIZE))
        .follow_links(false);
    if let Ok(ov) = overrides.build() {
        walk.overrides(ov);
    }

    let mut searcher = SearcherBuilder::new()
        .binary_detection(BinaryDetection::quit(b'\x00'))
        .line_number(true)
        .build();

    let mut emitted: jint = 0;
    let mut stop = false;
    let mut jni_error: Option<jni::errors::Error> = None;

    for entry in walk.build() {
        if stop || jni_error.is_some() {
            break;
        }
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        if !entry.file_type().map(|t| t.is_file()).unwrap_or(false) {
            continue;
        }
        let path = entry.path();
        let rel = path
            .strip_prefix(root_path)
            .unwrap_or(path)
            .to_string_lossy()
            .replace('\\', "/");

        let result = searcher.search_path(
            &matcher,
            path,
            UTF8(|line_number, line| {
                let line = line.trim_end_matches(['\r', '\n']);
                let mut keep_going = true;
                matcher
                    .find_iter(line.as_bytes(), |m| {
                        if max_results > 0 && emitted >= max_results {
                            keep_going = false;
                            stop = true;
                            return false;
                        }
                        // UTF-16 code-unit columns, to match what Kotlin string indexing expects.
                        let col_start = line[..m.start()].encode_utf16().count() as jint;
                        let col_end = line[..m.end()].encode_utf16().count() as jint;
                        match call_on_match(env, &sink, &rel, line_number as jint - 1, col_start, col_end, line) {
                            Ok(true) => {
                                emitted += 1;
                                true
                            }
                            Ok(false) => {
                                keep_going = false;
                                stop = true;
                                false
                            }
                            Err(e) => {
                                jni_error = Some(e);
                                keep_going = false;
                                stop = true;
                                false
                            }
                        }
                    })
                    .ok();
                Ok(keep_going)
            }),
        );
        let _ = result;
    }

    match jni_error {
        Some(e) => Err(e),
        None => Ok(emitted),
    }
}

fn call_on_match(
    env: &mut JNIEnv,
    sink: &JObject,
    file_path: &str,
    line_number: jint,
    col_start: jint,
    col_end: jint,
    line_text: &str,
) -> jni::errors::Result<bool> {
    let jpath = env.new_string(file_path)?;
    let jline = env.new_string(line_text)?;
    let result = env.call_method(
        sink,
        "onMatch",
        "(Ljava/lang/String;IIILjava/lang/String;)Z",
        &[
            JValue::Object(&jpath),
            JValue::Int(line_number),
            JValue::Int(col_start),
            JValue::Int(col_end),
            JValue::Object(&jline),
        ],
    );
    env.delete_local_ref(jpath)?;
    env.delete_local_ref(jline)?;
    result?.z()
}

// Same meta set as regex_syntax::escape — escaping arbitrary characters (e.g. "\ ") is a parse
// error in the regex crate, so only true metacharacters get a backslash.
fn regex_escape(literal: &str) -> String {
    let mut out = String::with_capacity(literal.len() * 2);
    for c in literal.chars() {
        if matches!(
            c,
            '\\' | '.' | '+' | '*' | '?' | '(' | ')' | '|' | '[' | ']' | '{' | '}' | '^' | '$' | '#' | '&' | '-' | '~'
        ) {
            out.push('\\');
        }
        out.push(c);
    }
    out
}
