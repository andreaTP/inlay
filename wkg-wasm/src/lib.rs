use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::collections::HashMap;
use wasm_pkg_common::digest::ContentDigest;
use wasm_pkg_common::label::Label;
use wasm_pkg_common::package::PackageRef;
use wasm_pkg_common::registry::Registry;

use semver::{Version, VersionReq};

// Config types — only RegistryMapping and CustomConfig are mirrored.
// The config module is behind the `registry-config` feature gate (pulls tokio).

#[derive(Debug, Deserialize)]
struct TomlConfig {
    #[serde(default)]
    default_registry: Option<Registry>,
    #[serde(default)]
    namespace_registries: HashMap<Label, RegistryMapping>,
    #[serde(default)]
    package_registry_overrides: HashMap<PackageRef, RegistryMapping>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
enum RegistryMapping {
    Registry(Registry),
    Custom(CustomConfig),
}

#[derive(Debug, Clone, Deserialize)]
struct CustomConfig {
    registry: Registry,
}

const DEFAULT_FALLBACK_NAMESPACE_REGISTRIES: &[(&str, &str)] =
    &[("wasi", "wasi.dev"), ("ba", "bytecodealliance.org")];

// Lock file types — mirrored from wasm-pkg-core (can't compile to wasm due to tokio).

#[derive(Debug, Serialize, Deserialize)]
struct LockFileData {
    version: u64,
    #[serde(alias = "package", default)]
    packages: BTreeSet<LockedPackage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct LockedPackage {
    name: PackageRef,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    registry: Option<String>,
    #[serde(alias = "version", default, skip_serializing_if = "Vec::is_empty")]
    versions: Vec<LockedPackageVersion>,
}

impl PartialEq for LockedPackage {
    fn eq(&self, other: &Self) -> bool {
        self.name == other.name && self.registry == other.registry
    }
}
impl Eq for LockedPackage {}

impl Ord for LockedPackage {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.name
            .cmp(&other.name)
            .then_with(|| self.registry.cmp(&other.registry))
    }
}
impl PartialOrd for LockedPackage {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct LockedPackageVersion {
    requirement: VersionReq,
    version: Version,
    digest: ContentDigest,
}

// Memory protocol: entries are packed as [count: i32] then for each entry
// 5 length-prefixed strings: name, registry, requirement, version, digest.
// Each string: [len: i32][bytes: u8*len]. Empty registry uses len=0.

fn write_str_to_buf(buf: &mut Vec<u8>, s: &str) {
    let bytes = s.as_bytes();
    buf.extend_from_slice(&(bytes.len() as i32).to_le_bytes());
    buf.extend_from_slice(bytes);
}

fn read_str_from_buf(data: &[u8], offset: &mut usize) -> String {
    let len = i32::from_le_bytes(data[*offset..*offset + 4].try_into().unwrap()) as usize;
    *offset += 4;
    let s = std::str::from_utf8(&data[*offset..*offset + len]).unwrap().to_string();
    *offset += len;
    s
}

// Exported functions

#[unsafe(no_mangle)]
pub extern "C" fn resolve_namespace(
    config_ptr: *const u8,
    config_len: usize,
    ns_ptr: *const u8,
    ns_len: usize,
) -> u64 {
    let config_str =
        unsafe { std::str::from_utf8_unchecked(std::slice::from_raw_parts(config_ptr, config_len)) };
    let namespace =
        unsafe { std::str::from_utf8_unchecked(std::slice::from_raw_parts(ns_ptr, ns_len)) };
    let result = resolve_namespace_impl(config_str, namespace);
    return_string(&result)
}

/// Reads TOML lock file, writes entries into allocated memory as packed fields.
/// Returns packed ptr|len pointing to the binary entry buffer.
/// On parse error, returns a string prefixed with "ERROR:" instead.
#[unsafe(no_mangle)]
pub extern "C" fn read_lock(ptr: *const u8, len: usize) -> u64 {
    let input = unsafe { std::str::from_utf8_unchecked(std::slice::from_raw_parts(ptr, len)) };

    let data: LockFileData = match toml::from_str(input) {
        Ok(d) => d,
        Err(e) => {
            let mut err_buf = vec![0x01u8];
            err_buf.extend_from_slice(format!("{}", e).as_bytes());
            return return_bytes(err_buf);
        }
    };

    let mut buf = Vec::new();
    // Tag byte: 0x00 = success (binary data)
    buf.push(0x00u8);
    let mut count: i32 = 0;
    // Reserve space for count
    buf.extend_from_slice(&0i32.to_le_bytes());

    for pkg in &data.packages {
        for ver in &pkg.versions {
            write_str_to_buf(&mut buf, &pkg.name.to_string());
            write_str_to_buf(&mut buf, pkg.registry.as_deref().unwrap_or(""));
            write_str_to_buf(&mut buf, &ver.requirement.to_string());
            write_str_to_buf(&mut buf, &ver.version.to_string());
            write_str_to_buf(&mut buf, &ver.digest.to_string());
            count += 1;
        }
    }

    // Patch count at offset 1 (after the tag byte)
    buf[1..5].copy_from_slice(&count.to_le_bytes());

    return_bytes(buf)
}

/// Takes a binary buffer of packed lock entries, produces TOML string.
#[unsafe(no_mangle)]
pub extern "C" fn write_lock(ptr: *const u8, len: usize) -> u64 {
    let data = unsafe { std::slice::from_raw_parts(ptr, len) };
    let mut offset = 0;

    let count = i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap()) as usize;
    offset += 4;

    let mut packages = BTreeSet::new();

    for _ in 0..count {
        let name_str = read_str_from_buf(data, &mut offset);
        let registry_str = read_str_from_buf(data, &mut offset);
        let requirement_str = read_str_from_buf(data, &mut offset);
        let version_str = read_str_from_buf(data, &mut offset);
        let digest_str = read_str_from_buf(data, &mut offset);

        let name: PackageRef = match name_str.parse() {
            Ok(n) => n,
            Err(e) => return return_string(&format!("ERROR:invalid package name '{}': {}", name_str, e)),
        };
        let requirement: VersionReq = match requirement_str.parse() {
            Ok(r) => r,
            Err(e) => return return_string(&format!("ERROR:invalid version requirement '{}': {}", requirement_str, e)),
        };
        let version: Version = match version_str.parse() {
            Ok(v) => v,
            Err(e) => return return_string(&format!("ERROR:invalid version '{}': {}", version_str, e)),
        };
        let digest: ContentDigest = match digest_str.parse() {
            Ok(d) => d,
            Err(e) => return return_string(&format!("ERROR:invalid digest '{}': {}", digest_str, e)),
        };

        let registry = if registry_str.is_empty() {
            None
        } else {
            Some(registry_str)
        };

        let locked_version = LockedPackageVersion {
            requirement,
            version,
            digest,
        };

        let existing = packages.take(&LockedPackage {
            name: name.clone(),
            registry: registry.clone(),
            versions: vec![],
        });
        let mut pkg = existing.unwrap_or(LockedPackage {
            name,
            registry,
            versions: vec![],
        });
        pkg.versions.push(locked_version);
        packages.insert(pkg);
    }

    let lock = LockFileData {
        version: 1,
        packages,
    };

    let header =
        "# This file is automatically generated.\n# It is not intended for manual editing.\n";
    match toml::to_string_pretty(&lock) {
        Ok(toml) => return_string(&format!("{}{}", header, toml)),
        Err(e) => return_string(&format!("ERROR:failed to serialize lock file: {}", e)),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn alloc(len: usize) -> *mut u8 {
    if len == 0 {
        return std::ptr::null_mut();
    }
    let layout = std::alloc::Layout::from_size_align(len, 1).unwrap();
    unsafe { std::alloc::alloc(layout) }
}

#[unsafe(no_mangle)]
pub extern "C" fn dealloc(ptr: *mut u8, len: usize) {
    if len == 0 || ptr.is_null() {
        return;
    }
    let layout = std::alloc::Layout::from_size_align(len, 1).unwrap();
    unsafe { std::alloc::dealloc(ptr, layout) }
}

fn return_string(value: &str) -> u64 {
    if value.is_empty() {
        return 0;
    }
    return_bytes(value.as_bytes().to_vec())
}

fn return_bytes(bytes: Vec<u8>) -> u64 {
    if bytes.is_empty() {
        return 0;
    }
    let len = bytes.len();
    let ptr = bytes.as_ptr() as u64;
    std::mem::forget(bytes);
    (ptr << 32) | (len as u64)
}

fn resolve_namespace_impl(config_str: &str, namespace: &str) -> String {
    let config: TomlConfig = toml::from_str(config_str).unwrap_or(TomlConfig {
        default_registry: None,
        namespace_registries: HashMap::new(),
        package_registry_overrides: HashMap::new(),
    });

    let mut ns_map = HashMap::new();
    for (label, mapping) in &config.namespace_registries {
        ns_map.insert(label.to_string(), registry_url(mapping));
    }
    for (ns, reg) in DEFAULT_FALLBACK_NAMESPACE_REGISTRIES {
        ns_map.entry(ns.to_string()).or_insert_with(|| reg.to_string());
    }

    ns_map.get(namespace).cloned().unwrap_or_default()
}

fn registry_url(mapping: &RegistryMapping) -> String {
    match mapping {
        RegistryMapping::Registry(r) => r.to_string(),
        RegistryMapping::Custom(c) => c.registry.to_string(),
    }
}
