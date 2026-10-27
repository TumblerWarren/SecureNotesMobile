# Security contract and boundaries

The intended guarantee is confidentiality of locked vault contents against someone who copies phone or drive files, provided the password or recovery key stays secret and strong. The app never deliberately writes decrypted vault payloads to disk. Playback and rendering require plaintext in memory and graphics/decoder buffers. A compromised OS, an unlocked vault, an external camera, weak passwords, plaintext originals and historical copies remain outside this guarantee.

## Key hierarchy and formats

`header` is exactly 160 bytes: ASCII `SVLT0001` (8), vault UUID (16), Argon2id salt (16), password nonce (12), wrapped master (48), recovery nonce (12), wrapped master (48). Each AES-256-GCM wrapper authenticates magic, vault UUID, salt, and the role (`password` or `recovery`). Costs are fixed by format version, preventing a modified header from requesting arbitrary memory allocation. Nonces come from SecureRandom. Password rotation uses a new nonce and preserves the recovery wrapper.

The random master derives the database key through HKDF-SHA256 using a domain-separated context. SQLCipher receives a raw key to avoid repeating its password KDF for each connection. The raw key buffer is cleared after database closure. File keysets exist only inside the encrypted index; ciphertext uses Tink AES256_GCM_HKDF_1MB and authenticates vault UUID and object UUID. Objects are immutable; editing requires a new object/encryption stream.

This is authenticated encryption, not an authorization UI over plaintext files. Unknown header versions and malformed ciphertext fail closed. Neither Tink nor this local-only app provides reliable whole-vault rollback detection. Password changes cannot revoke a copy already decrypted or a historical snapshot for which someone knows the corresponding secret.

## Storage and lifecycle

Data is under noBackupFilesDir. Android backup rules also exclude files, databases, preferences and external app storage. Objects first enter `.pending`; an authenticated stream is finalized and fsynced, renamed atomically, and the object directory is fsynced before its metadata is committed. There is no second full import verification pass: subsequent reads authenticate accessed segments. A crash between rename and index commit can leave an encrypted orphan. Unreferenced objects are collected after unlock and failed migration; partial imports are cleaned separately. An existing index is never silently recreated on a key or schema error.

Native proxy descriptors use O_DIRECT to prevent FUSE page-cache hits from bypassing session checks after lock; devices that cannot enable uncached access fail closed for native viewing. Previously delivered decoder/graphics memory still cannot be recalled.

Lock revokes readers immediately, stops registered players and PDF connections, cancels active work and then closes native handles/databases on the I/O executor. Pending note writes are drained before database closure when possible; a sudden process kill may lose edits still pending. A cancelled unlock must not establish a session after locking. The JVM test suite covers reader revocation; device cancellation/lifecycle behavior still needs execution.

Android private storage, FLAG_SECURE and overlay protection supplement encryption. They do not establish protection against an attacker who controls the OS. Managed runtimes, Tink objects, decoder buffers and graphics allocations prevent a promise that every historical plaintext/key copy has been overwritten. Buffer clearing and disposal are best effort; the security claim does not include reading live process memory through an OS compromise.

Native PDF parsing runs in an isolated process receiving only a revocable descriptor. Legacy database decoding uses a separate isolated service and an in-memory SQLite snapshot; its old password is never used for new encryption. Converted metadata is committed in one transaction, and the original HTML remains encrypted for fidelity. Neither service receives the new vault master key. There are no exported vault providers or decrypted content URIs, external-view intents, plaintext export/share/print paths, automatic source deletion or network permission.

## Dependency and release policy

Gradle and native source downloads are pinned/checksummed. No native third-party wrapper binary is accepted without building the pinned source. The debug build has its own application identifier. Retain the old application and its encrypted source files until the read-only migration has been verified on the relevant data. Legacy app security issues have not been retroactively fixed by adding this separate preview.
