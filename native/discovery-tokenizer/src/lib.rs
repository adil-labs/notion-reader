use jni::{objects::{JClass, JString}, sys::{jboolean, jintArray, jlong}, JNIEnv};
use std::{collections::HashMap, sync::{Mutex, OnceLock, atomic::{AtomicI64, Ordering}}};
use tokenizers::Tokenizer;

fn load(path: &str) -> Result<Tokenizer, String> {
    let mut tokenizer = Tokenizer::from_file(path).map_err(|_| "Cannot load verified tokenizer".to_owned())?;
    tokenizer.with_padding(None);
    tokenizer.with_truncation(None).map_err(|_| "Cannot disable truncation".to_owned())?;
    Ok(tokenizer)
}

// Return id/start/end triples. HF char offsets are Unicode scalars; Kotlin offsets are UTF-16.
fn encode(tokenizer: &Tokenizer, text: &str, special: bool) -> Result<Vec<i32>, String> {
    let encoded = tokenizer.encode_char_offsets(text, special).map_err(|_| "Tokenizer encoding failed".to_owned())?;
    let mut offsets = Vec::with_capacity(text.chars().count() + 1);
    offsets.push(0i32);
    for c in text.chars() { offsets.push(offsets.last().unwrap() + c.len_utf16() as i32); }
    let mut result = Vec::with_capacity(encoded.len() * 3);
    for (&id, &(start,end)) in encoded.get_ids().iter().zip(encoded.get_offsets()) {
        if end >= offsets.len() || start > end { return Err("Invalid tokenizer offsets".to_owned()); }
        result.extend([id as i32, offsets[start], offsets[end]]);
    }
    Ok(result)
}

static TOKENIZERS: OnceLock<Mutex<HashMap<i64, Tokenizer>>> = OnceLock::new();
static NEXT_ID: AtomicI64 = AtomicI64::new(1);
fn registry() -> &'static Mutex<HashMap<i64, Tokenizer>> { TOKENIZERS.get_or_init(|| Mutex::new(HashMap::new())) }

#[no_mangle]
pub extern "system" fn Java_com_example_notionreader_data_discovery_NativeTokenizerBridge_open(mut env: JNIEnv, _: JClass, path: JString) -> jlong {
    let result = (|| {
        let path: String = env.get_string(&path).map_err(|_| "Invalid tokenizer path")?.into();
        let tokenizer = load(&path)?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        registry().lock().map_err(|_| "Tokenizer unavailable")?.insert(id, tokenizer);
        Ok::<i64,String>(id)
    })();
    match result { Ok(id) => id, Err(_) => { let _ = env.throw_new("java/lang/IllegalStateException", "Cannot open local tokenizer"); 0 } }
}

#[no_mangle]
pub extern "system" fn Java_com_example_notionreader_data_discovery_NativeTokenizerBridge_encode(mut env: JNIEnv, _: JClass, handle: jlong, text: JString, special: jboolean) -> jintArray {
    let result = (|| {
        let text: String = env.get_string(&text).map_err(|_| "Invalid input")?.into();
        let entries = registry().lock().map_err(|_| "Tokenizer unavailable")?;
        let tokenizer = entries.get(&handle).ok_or("Tokenizer is closed")?;
        let result = encode(tokenizer, &text, special != 0)?;
        let array = env.new_int_array(result.len() as i32).map_err(|_| "Cannot allocate tokens")?;
        env.set_int_array_region(&array, 0, &result).map_err(|_| "Cannot return tokens")?;
        Ok::<jintArray,String>(array.into_raw())
    })();
    match result { Ok(array) => array, Err(_) => { let _ = env.throw_new("java/lang/IllegalStateException", "Local tokenizer encoding failed"); std::ptr::null_mut() } }
}

#[no_mangle]
pub extern "system" fn Java_com_example_notionreader_data_discovery_NativeTokenizerBridge_close(mut env: JNIEnv, _: JClass, handle: jlong) {
    match registry().lock() {
        Ok(mut entries) => { entries.remove(&handle); },
        Err(_) => { let _ = env.throw_new("java/lang/IllegalStateException", "Tokenizer unavailable"); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pinned_reference_ids_and_utf16_offsets_match() {
        let reference: serde_json::Value = serde_json::from_str(include_str!("../../../tools/semantic-discovery/gemma2-android-reference.json")).unwrap();
        let file = std::env::var("GEMMA_TOKENIZER_JSON").expect("Set GEMMA_TOKENIZER_JSON to the verified tokenizer.json");
        let tokenizer = load(&file).unwrap();
        for case in reference["cases"].as_array().unwrap() {
            let text = case["text"].as_str().unwrap();
            let actual = encode(&tokenizer, text, false).unwrap();
            let ids: Vec<i32> = case["tokenIds"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap() as i32).collect();
            let offsets: Vec<i32> = case["utf16Offsets"].as_array().unwrap().iter().flat_map(|v| v.as_array().unwrap().iter().map(|n| n.as_i64().unwrap() as i32)).collect();
            assert_eq!(actual.chunks_exact(3).map(|x| x[0]).collect::<Vec<_>>(), ids, "ids {text:?}");
            assert_eq!(actual.chunks_exact(3).flat_map(|x| [x[1],x[2]]).collect::<Vec<_>>(), offsets, "offsets {text:?}");
            let instructed = format!("{}{}", reference["instruction"].as_str().unwrap(), text);
            let expected: Vec<i32> = case["instructedTokenIds"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap() as i32).collect();
            assert_eq!(encode(&tokenizer, &instructed, true).unwrap().chunks_exact(3).map(|x| x[0]).collect::<Vec<_>>(), expected);
        }
    }
}
