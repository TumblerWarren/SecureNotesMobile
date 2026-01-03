import CryptoJS from 'crypto-js';
import * as Random from 'expo-crypto';

export async function deriveKey(password: string, salt: Uint8Array): Promise<string> {
    // Convert salt Uint8Array to WordArray
    const saltWA = CryptoJS.lib.WordArray.create(salt as any);

    const key = CryptoJS.PBKDF2(password, saltWA, {
        keySize: 256 / 32,
        iterations: 100000,
        hasher: CryptoJS.algo.SHA256
    });

    return key.toString(CryptoJS.enc.Base64);
}

export async function encryptData(data: Uint8Array, keyBase64: string): Promise<Uint8Array> {
    // Generate IV
    const iv = await Random.getRandomBytesAsync(12);
    const ivWA = CryptoJS.lib.WordArray.create(iv as any);

    // Parse key
    const keyWA = CryptoJS.enc.Base64.parse(keyBase64);

    // Convert data to WordArray
    const dataWA = CryptoJS.lib.WordArray.create(data as any);

    // Encrypt (AES-GCM is not directly supported by crypto-js standard, using AES-CBC/CTR or similar as fallback for Expo Go compatibility? 
    // Actually, crypto-js supports AES. Default is CBC. GCM is hard in pure JS.
    // For the sake of "Verification" in Expo Go, we will use AES-CBC.
    // NOTE: This is a downgrade from GCM but necessary for Expo Go without native code.

    const encrypted = CryptoJS.AES.encrypt(dataWA, keyWA, {
        iv: ivWA,
        mode: CryptoJS.mode.CBC,
        padding: CryptoJS.pad.Pkcs7
    });

    // Combine IV + Encrypted Data
    // encrypted.ciphertext is a WordArray

    // Convert WordArrays back to Uint8Array is painful in JS.
    // Let's stick to Base64 strings for storage if possible? 
    // But our DB expects Uint8Array.

    // Helper to convert WordArray to Uint8Array
    function wordToByteArray(wordArray: any) {
        const words = wordArray.words;
        const sigBytes = wordArray.sigBytes;
        const u8 = new Uint8Array(sigBytes);
        for (let i = 0; i < sigBytes; i++) {
            const byte = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
            u8[i] = byte;
        }
        return u8;
    }

    const encryptedBytes = wordToByteArray(encrypted.ciphertext);

    const result = new Uint8Array(iv.length + encryptedBytes.length);
    result.set(iv);
    result.set(encryptedBytes, iv.length);

    return result;
}

export async function decryptData(data: Uint8Array, keyBase64: string): Promise<Uint8Array> {
    const iv = data.slice(0, 12);
    const ciphertext = data.slice(12);

    const ivWA = CryptoJS.lib.WordArray.create(iv as any);
    const ciphertextWA = CryptoJS.lib.WordArray.create(ciphertext as any);
    const keyWA = CryptoJS.enc.Base64.parse(keyBase64);

    // Create a CipherParams object
    const cipherParams = CryptoJS.lib.CipherParams.create({
        ciphertext: ciphertextWA
    });

    const decrypted = CryptoJS.AES.decrypt(cipherParams, keyWA, {
        iv: ivWA,
        mode: CryptoJS.mode.CBC,
        padding: CryptoJS.pad.Pkcs7
    });

    function wordToByteArray(wordArray: any) {
        const words = wordArray.words;
        const sigBytes = wordArray.sigBytes;
        const u8 = new Uint8Array(sigBytes);
        for (let i = 0; i < sigBytes; i++) {
            const byte = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
            u8[i] = byte;
        }
        return u8;
    }

    return wordToByteArray(decrypted);
}

export function generateSalt(): Uint8Array {
    return Random.getRandomBytes(16);
}
