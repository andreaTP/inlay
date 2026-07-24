TARGET_DIR = target/wasm32-wasip1/release
OUTPUT = wkg.wasm

.PHONY: build clean

build: $(OUTPUT)

$(OUTPUT): src/lib.rs Cargo.toml
	cargo build --target wasm32-wasip1 --release
	cp $(TARGET_DIR)/inlay_wkg_wasm.wasm $(OUTPUT)

clean:
	cargo clean
	rm -f $(OUTPUT)
