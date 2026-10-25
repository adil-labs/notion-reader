use jni::{objects::{JClass, JString}, sys::{jboolean, jintArray, jlong}, JNIEnv};
use std::{collections::HashMap, fs::File, io::{Read, Seek, SeekFrom}, sync::{Mutex, OnceLock, atomic::{AtomicI64, Ordering}}};
use sentencepiece::SentencePieceProcessor;

// Read the pinned published bundle section into memory. Never writes or converts a model.
fn load_bundle_section(path: &str, offset: i64, length: i32) -> Result<SentencePieceProcessor, String> {
    if offset < 32 || !(1..=16 * 1024 * 1024).contains(&length) { return Err("Invalid tokenizer section".into()); }
    let mut file = File::open(path).map_err(|_| "Cannot open verified bundle")?;
    let end = (offset as u64).checked_add(length as u64).ok_or("Invalid tokenizer bounds")?;
    if end > file.metadata().map_err(|_| "Cannot inspect bundle")?.len() { return Err("Truncated tokenizer section".into()); }
    file.seek(SeekFrom::Start(offset as u64)).map_err(|_| "Cannot read tokenizer section")?;
    let mut bytes = vec![0; length as usize];
    file.read_exact(&mut bytes).map_err(|_| "Cannot read tokenizer section")?;
    let processor = SentencePieceProcessor::from_serialized_proto(&bytes).map_err(|_| "Invalid bundled SentencePiece tokenizer")?;
    if processor.bos_id() != Some(2) || processor.eos_id() != Some(1) { return Err("Incompatible tokenizer special tokens".into()); }
    Ok(processor)
}

// SentencePiece offsets are UTF-8 bytes; Kotlin source evidence uses UTF-16 code units.
fn encode(tokenizer: &SentencePieceProcessor, text: &str, special: bool) -> Result<Vec<i32>, String> {
    let pieces = tokenizer.encode(text).map_err(|_| "Tokenizer encoding failed")?;
    let mut offsets = vec![-1i32; text.len() + 1];
    let mut utf16 = 0i32;
    for (byte, character) in text.char_indices() { offsets[byte] = utf16; utf16 += character.len_utf16() as i32; }
    offsets[text.len()] = utf16;
    let mut result = Vec::with_capacity((pieces.len() + 2) * 3);
    if special { result.extend([2, 0, 0]); }
    for piece in pieces {
        let (begin, end) = (piece.span.0 as usize, piece.span.1 as usize);
        if begin > end || end > text.len() || offsets[begin] < 0 || offsets[end] < 0 { return Err("Invalid tokenizer source offsets".into()); }
        result.extend([piece.id as i32, offsets[begin], offsets[end]]);
    }
    if special { result.extend([1, utf16, utf16]); }
    Ok(result)
}

static TOKENIZERS: OnceLock<Mutex<HashMap<i64, SentencePieceProcessor>>> = OnceLock::new();
static NEXT_ID: AtomicI64 = AtomicI64::new(1);
fn registry() -> &'static Mutex<HashMap<i64, SentencePieceProcessor>> { TOKENIZERS.get_or_init(|| Mutex::new(HashMap::new())) }

#[no_mangle]
pub extern "system" fn Java_com_example_notionreader_data_discovery_NativeTokenizerBridge_open(mut env: JNIEnv, _: JClass, path: JString, offset: jlong, length: i32) -> jlong {
    let result = (|| {
        let path: String = env.get_string(&path).map_err(|_| "Invalid tokenizer path")?.into();
        let tokenizer = load_bundle_section(&path, offset, length)?;
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
    fn bundled() -> SentencePieceProcessor {
        load_bundle_section(&std::env::var("GEMMA_LITERT_BUNDLE").expect("Set GEMMA_LITERT_BUNDLE"), 32768, 4689013).unwrap()
    }
    #[test]
    fn published_sentencepiece_ids_offsets_special_strings_and_context_boundaries_match() {
        let tokenizer = bundled();
        let reference: serde_json::Value = serde_json::from_str(include_str!("../../../tools/semantic-discovery/litert-tokenizer-reference.json")).unwrap();
        for case in reference["cases"].as_array().unwrap() {
            let text = case["text"].as_str().unwrap();
            let actual = encode(&tokenizer, text, false).unwrap();
            let expected: Vec<i32> = case["ids"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap() as i32).collect();
            assert_eq!(actual.chunks_exact(3).map(|x| x[0]).collect::<Vec<_>>(), expected, "bundle ids {text:?}");
            let expected: Vec<i32> = case["utf16Offsets"].as_array().unwrap().iter().flat_map(|v| v.as_array().unwrap().iter().map(|v| v.as_i64().unwrap() as i32)).collect();
            assert_eq!(actual.chunks_exact(3).flat_map(|x| [x[1],x[2]]).collect::<Vec<_>>(), expected, "bundle offsets {text:?}");
            let instructed = format!("task: clustering | text: {}", text.trim());
            let expected: Vec<i32> = case["instructedIds"].as_array().unwrap().iter().map(|v| v.as_i64().unwrap() as i32).collect();
            assert_eq!(encode(&tokenizer, &instructed, true).unwrap().chunks_exact(3).map(|x| x[0]).collect::<Vec<_>>(), expected);
        }
        for boundary in reference["boundaries"].as_array().unwrap() {
            let text = format!("task: clustering | text: {}", " token".repeat(boundary["repeats"].as_u64().unwrap() as usize).trim());
            assert_eq!(encode(&tokenizer, &text, true).unwrap().len() / 3, boundary["tokens"].as_u64().unwrap() as usize);
        }
    }
    #[test]
    fn invalid_section_bounds_are_rejected_before_reading_or_allocating() {
        for (offset,length) in [(-1,1),(0,1),(32,0),(32,-1),(32,16777217),(i64::MAX,1)] {
            assert!(load_bundle_section("missing-bundle",offset,length).is_err());
        }
        let path = std::env::temp_dir().join(format!("tokenizer-truncated-{}", std::process::id()));
        std::fs::write(&path, [0;64]).unwrap();
        assert!(load_bundle_section(path.to_str().unwrap(),32,64).is_err());
        assert!(load_bundle_section(path.to_str().unwrap(),32,32).is_err());
        std::fs::remove_file(path).unwrap();
    }
}
