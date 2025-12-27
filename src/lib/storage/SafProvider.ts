import * as FileSystem from 'expo-file-system/legacy';
import { IStorageProvider } from './types';
const SecureStorage = require('../../../modules/secure-storage');

export class SafProvider implements IStorageProvider {
    name = 'Secure Cloud Storage';
    private fileUri: string | null = null;

    // With direct URI permission, we don't 'manage' a folder, we manage a persistent file
    isManaged() {
        return false; // We want to force "Sync" to just call write(), which now goes to cloud
    }

    async connect(): Promise<void> {
        // No-op: we connect per-file now via pickFile
    }

    async listFiles(): Promise<{ name: string, uri: string }[]> {
        return []; // Not supported in file-only mode
    }

    async selectFile(uri: string) {
        this.fileUri = uri; // Set internally but we usually pick via own method
    }

    // New helper to replace DocumentPicker
    async pickFile(): Promise<string | null> {
        const uri = await SecureStorage.pickFile();
        if (uri) {
            this.fileUri = uri;
        }
        return uri;
    }

    async createNewFile(filename: string) {
        const uri = await SecureStorage.createFile(filename);
        if (uri) {
            this.fileUri = uri;
        } else {
            throw new Error("File creation cancelled");
        }
    }

    async read(): Promise<Uint8Array | null> {
        if (!this.fileUri) return null;

        // For reading, we can usually still use expo-file-system if the URI is persistable
        // OR we might need to add a read method to the native module if permission is strict
        // Let's try standard FS first, as permissions should be granted by the Intent
        try {
            // Use native module to read content:// URIs with persistent permission
            const content = await SecureStorage.readFile(this.fileUri);

            const binaryString = atob(content);
            const bytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }
            return bytes;
        } catch (e) {
            console.warn("Read failed:", e);
            throw e;
        }
    }

    async write(data: Uint8Array): Promise<void> {
        if (!this.fileUri) throw new Error('No file selected');

        // Convert to Base64
        let binary = '';
        const len = data.byteLength;
        for (let i = 0; i < len; i++) {
            binary += String.fromCharCode(data[i]);
        }
        const base64 = btoa(binary);

        // Native Write
        await SecureStorage.saveFile(this.fileUri, base64);
    }
}
