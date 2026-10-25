fn main() {
    // The Rust JNI library links Google's SentencePiece C++ implementation statically.
    // NDK libc++_static keeps the C++ ABI support in a separate archive.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rerun-if-env-changed=DISCOVERY_CXX_LIBRARY_DIR");
        let directory = std::env::var("DISCOVERY_CXX_LIBRARY_DIR")
            .expect("Use tools/build-discovery-tokenizer.ps1 to select the NDK C++ archives");
        assert!(std::path::Path::new(&directory).join("libc++abi.a").is_file());
        // Do not add this directory to -L: its libc.a would shadow Android API-level
        // shared stubs on x86_64. Link only the required archive by absolute path.
        println!("cargo:rustc-link-arg={}", std::path::Path::new(&directory).join("libc++abi.a").display());
    }
}
