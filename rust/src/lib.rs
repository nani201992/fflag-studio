//! Flag engine for FFlag Studio: parse, validate and canonicalise a FastFlag set.
//! Java keeps the UI state as a JSON object; every edit and every apply goes
//! through here, so a bad value never reaches the client's settings file.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::{json, Map, Value};

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Flag,
    Int,
    Str,
}

fn kind_of(key: &str) -> Option<Kind> {
    // FLog/DFLog are log-level integers and are real client flags too
    [("DFFlag", Kind::Flag), ("FFlag", Kind::Flag), ("DFInt", Kind::Int),
     ("FInt", Kind::Int), ("DFLog", Kind::Int), ("FLog", Kind::Int),
     ("DFString", Kind::Str), ("FString", Kind::Str)]
        .iter()
        .find(|(p, _)| key.starts_with(p))
        .map(|(_, k)| *k)
}

fn normalize(kind: Kind, v: &Value) -> Result<Value, String> {
    match kind {
        Kind::Flag => {
            let b = match v {
                Value::Bool(b) => *b,
                Value::Number(n) => match n.as_i64() {
                    Some(0) => false,
                    Some(1) => true,
                    _ => return Err(format!("expected 0/1, got {n}")),
                },
                Value::String(s) => match s.trim().to_ascii_lowercase().as_str() {
                    "true" | "1" | "yes" => true,
                    "false" | "0" | "no" => false,
                    other => return Err(format!("expected true/false, got {other:?}")),
                },
                other => return Err(format!("expected bool, got {other}")),
            };
            Ok(Value::Bool(b))
        }
        Kind::Int => {
            let n = match v {
                Value::Number(n) => n
                    .as_i64()
                    .or_else(|| n.as_f64().filter(|f| f.fract() == 0.0).map(|f| f as i64)),
                Value::String(s) => s.trim().parse::<i64>().ok(),
                _ => None,
            }
            .ok_or_else(|| format!("not an integer: {v}"))?;
            Ok(json!(n))
        }
        Kind::Str => match v {
            Value::String(s) => Ok(Value::String(s.clone())),
            _ => Err(format!("expected string, got {v}")),
        },
    }
}

/// Type implied by a value when the key has no prefix.
fn kind_from_value(v: &Value) -> Option<Kind> {
    match v {
        Value::Bool(_) => Some(Kind::Flag),
        Value::Number(_) => Some(Kind::Int),
        Value::String(s) => {
            let t = s.trim();
            let low = t.to_ascii_lowercase();
            if low == "true" || low == "false" {
                Some(Kind::Flag)
            } else if t.parse::<i64>().is_ok() {
                Some(Kind::Int)
            } else {
                Some(Kind::Str)
            }
        }
        _ => None,
    }
}

fn type_name(k: Kind) -> &'static str {
    match k {
        Kind::Flag => "Flag",
        Kind::Int => "Int",
        Kind::Str => "String",
    }
}

/// Import: keep every key we understand, report the rest instead of failing the whole file.
///
/// Flags are looked up by `prefix + baseName`, so keys without a prefix never apply. Two
/// cases show up in real flag files: a bare name that also appears prefixed in the same file
/// (drop it, the prefixed one is the live one) and an orphan bare name (write both the F and
/// the DF variant; the unregistered one is a no-op).
fn import_all(input: &str) -> Result<(Map<String, Value>, Vec<String>, Vec<String>, Vec<String>), String> {
    let parsed: Value = serde_json::from_str(input).map_err(|e| format!("invalid JSON: {e}"))?;
    let obj = parsed.as_object().ok_or("top level must be an object")?;
    let mut out = Map::new();
    let mut aliased = Vec::new();
    let mut inferred = Vec::new();
    let mut skipped = Vec::new();
    let prefixed: Vec<String> = obj
        .keys()
        .filter(|k| kind_of(k).is_some())
        .cloned()
        .collect();

    for (key, val) in obj {
        if let Some(kind) = kind_of(key) {
            match normalize(kind, val) {
                Ok(canon) => {
                    out.insert(key.clone(), canon);
                }
                Err(e) => skipped.push(format!("{key}: {e}")),
            }
        }
    }

    for (key, val) in obj {
        if kind_of(key).is_some() {
            continue;
        }
        let siblings: Vec<&String> = prefixed
            .iter()
            .filter(|k| k.len() > key.len() && k.ends_with(key.as_str()))
            .collect();
        if !siblings.is_empty() {
            aliased.push(format!(
                "{key} -> {}",
                siblings
                    .iter()
                    .map(|s| s.as_str())
                    .collect::<Vec<_>>()
                    .join(" / ")
            ));
            continue;
        }
        match kind_from_value(val) {
            Some(kind) => {
                let t = type_name(kind);
                for prefix in ["F", "DF"] {
                    let full = format!("{prefix}{t}{key}");
                    if !out.contains_key(&full) {
                        if let Ok(canon) = normalize(kind, val) {
                            out.insert(full, canon);
                        }
                    }
                }
                inferred.push(format!("{key} -> F{t}{key} + DF{t}{key}"));
            }
            None => skipped.push(format!("{key}: untyped value {val}")),
        }
    }
    Ok((out, aliased, inferred, skipped))
}

/// Normalise every flag, dropping unknown prefixes.
fn normalize_all(input: &str) -> Result<Map<String, Value>, String> {
    let parsed: Value = serde_json::from_str(input).map_err(|e| format!("invalid JSON: {e}"))?;
    let obj = parsed.as_object().ok_or("top level must be an object")?;
    let mut out = Map::new();
    for (key, val) in obj {
        let kind = kind_of(key)
            .ok_or_else(|| format!("{key}: unknown prefix (FFlag/DFFlag/FInt/DFInt/FString/DFString)"))?;
        let canon = normalize(kind, val).map_err(|e| format!("{key}: {e}"))?;
        out.insert(key.clone(), canon);
    }
    Ok(out)
}

fn reply(env: &mut JNIEnv, ok: bool, body: String) -> jstring {
    let payload = json!({ "ok": ok, "result": body }).to_string();
    env.new_string(payload)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn read(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|s| s.into())
        .map_err(|e| format!("bad string: {e}"))
}

#[no_mangle]
pub extern "system" fn Java_com_kizu_fflagstudio_Core_normalize(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let out = read(&mut env, &input).and_then(|text| {
        normalize_all(&text).map(|m| Value::Object(m).to_string())
    });
    match out {
        Ok(json) => reply(&mut env, true, json),
        Err(e) => reply(&mut env, false, e),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kizu_fflagstudio_Core_importFlags(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let out = read(&mut env, &input).and_then(|text| {
        import_all(&text).map(|(flags, aliased, inferred, skipped)| {
            json!({
                "flags": Value::Object(flags),
                "aliased": aliased,
                "inferred": inferred,
                "skipped": skipped
            })
            .to_string()
        })
    });
    match out {
        Ok(body) => reply(&mut env, true, body),
        Err(e) => reply(&mut env, false, e),
    }
}

/// One flag at a time: used by the add/edit dialog before the row exists.
#[no_mangle]
pub extern "system" fn Java_com_kizu_fflagstudio_Core_normalizeOne(
    mut env: JNIEnv,
    _class: JClass,
    key: JString,
    value: JString,
) -> jstring {
    let out = read(&mut env, &key).and_then(|k| {
        let v = read(&mut env, &value)?;
        let kind = kind_of(&k).ok_or_else(|| {
            format!("{k}: unknown prefix (FFlag/DFFlag/FInt/DFInt/FString/DFString)")
        })?;
        // bare text is the common case from a text field: try string, then JSON literal
        let raw: Value = serde_json::from_str(&v).unwrap_or(Value::String(v.clone()));
        let canon = normalize(kind, &raw).map_err(|e| format!("{k}: {e}"))?;
        Ok(json!({ k: canon }).to_string())
    });
    match out {
        Ok(json) => reply(&mut env, true, json),
        Err(e) => reply(&mut env, false, e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonicalises_types() {
        let m = normalize_all(r#"{"FFlagA":"false","DFIntB":"7","FStringC":"x"}"#).unwrap();
        assert_eq!(m["FFlagA"], json!(false));
        assert_eq!(m["DFIntB"], json!(7));
        assert_eq!(m["FStringC"], json!("x"));
    }

    #[test]
    fn rejects_bad_values_and_prefixes() {
        assert!(normalize_all(r#"{"FFlagA":"maybe"}"#).is_err());
        assert!(normalize_all(r#"{"DFIntB":"lots"}"#).is_err());
        assert!(normalize_all(r#"{"DebugThing":true}"#).is_err());
        assert!(normalize_all("[]").is_err());
    }

    #[test]
    fn log_prefixes_are_integers() {
        let m = normalize_all(r#"{"DFLogHttpTraceError":"0","FLogNetwork":3}"#).unwrap();
        assert_eq!(m["DFLogHttpTraceError"], json!(0));
        assert_eq!(m["FLogNetwork"], json!(3));
    }

    #[test]
    fn import_keeps_good_keys_and_reports_the_rest() {
        let (flags, aliased, inferred, skipped) = import_all(
            r#"{"DFIntA":"7","DFFlagB":"True","CSGLevelOfDetailSwitchingDistance":"0","FFlagC":"maybe"}"#,
        )
        .unwrap();
        assert_eq!(flags.len(), 4); // 2 valid + 2 variants for the orphan
        assert_eq!(flags["DFFlagB"], json!(true));
        assert_eq!(skipped.len(), 1); // only FFlagC has a bad value
        assert!(inferred.len() == 1 && inferred[0].starts_with("CSGLevelOfDetailSwitchingDistance"));
        assert!(aliased.is_empty());
    }

    #[test]
    fn bare_name_with_a_prefixed_twin_is_dropped_not_duplicated() {
        let (flags, aliased, inferred, _) = import_all(
            r#"{"DFIntCSGLevelOfDetailSwitchingDistance":"100","CSGLevelOfDetailSwitchingDistance":"0",
                "TaskSchedulerUnreliableSleepManufacturers":"True"}"#,
        )
        .unwrap();
        assert_eq!(flags["DFIntCSGLevelOfDetailSwitchingDistance"], json!(100));
        assert_eq!(aliased.len(), 1);
        assert_eq!(inferred.len(), 1);
        // orphan written as both variants, typed from the value
        assert_eq!(flags["FFlagTaskSchedulerUnreliableSleepManufacturers"], json!(true));
        assert_eq!(flags["DFFlagTaskSchedulerUnreliableSleepManufacturers"], json!(true));
    }

    #[test]
    fn keeps_the_verified_probe() {
        let m = normalize_all(
            r#"{"DFIntS2PhysicsSenderRate":"38760","FIntDebugTextureManagerSkipMips":8}"#,
        )
        .unwrap();
        assert_eq!(m["DFIntS2PhysicsSenderRate"], json!(38760));
        assert_eq!(m["FIntDebugTextureManagerSkipMips"], json!(8));
    }
}
