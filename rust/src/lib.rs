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
    [("DFFlag", Kind::Flag), ("FFlag", Kind::Flag), ("DFInt", Kind::Int),
     ("FInt", Kind::Int), ("DFString", Kind::Str), ("FString", Kind::Str)]
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

#[no_mangle]
pub extern "system" fn Java_com_kizu_fflagstudio_Core_preset(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let out = read(&mut env, &name).and_then(|name| {
        let entries: &[(&str, Value)] = match name.as_str() {
            "fps" => &[
                ("DFIntTaskSchedulerTargetFps", json!(240)),
                ("FFlagDebugDisplayFPS", json!(true)),
            ],
            "performance" => &[
                ("DFIntTaskSchedulerTargetFps", json!(60)),
                ("DFIntCSGLevelOfDetailSwitchingDistance", json!(0)),
                ("DFIntCSGLevelOfDetailSwitchingDistanceL12", json!(0)),
                ("DFIntCSGLevelOfDetailSwitchingDistanceL23", json!(0)),
                ("DFIntCSGLevelOfDetailSwitchingDistanceL34", json!(0)),
                ("DFFlagTextureQualityOverrideEnabled", json!(true)),
                ("DFIntTextureQualityOverride", json!(0)),
                ("FIntDebugForceMSAASamples", json!(1)),
                ("FIntRenderShadowIntensity", json!(0)),
                ("FFlagDebugSkyGray", json!(true)),
            ],
            "clean" => &[
                ("FFlagDebugSkyGray", json!(true)),
                ("FIntDebugForceMSAASamples", json!(1)),
                ("FIntRenderShadowIntensity", json!(0)),
            ],
            other => return Err(format!("unknown preset {other:?}")),
        };
        let mut map = Map::new();
        for (k, v) in entries {
            map.insert((*k).to_string(), v.clone());
        }
        Ok(Value::Object(map).to_string())
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
    fn keeps_the_verified_probe() {
        let m = normalize_all(
            r#"{"DFIntS2PhysicsSenderRate":"38760","FIntDebugTextureManagerSkipMips":8}"#,
        )
        .unwrap();
        assert_eq!(m["DFIntS2PhysicsSenderRate"], json!(38760));
        assert_eq!(m["FIntDebugTextureManagerSkipMips"], json!(8));
    }
}
