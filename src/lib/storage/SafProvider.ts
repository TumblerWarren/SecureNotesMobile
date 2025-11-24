import * as FileSystem from 'expo-file-system/legacy';
import { IStorageProvider } from './types';

const { StorageAccessFramework } = FileSystem;

export class SafProvider implements IStorageProvider {
    name = 'Android SAF';
    private directoryUri: string | null = null;
    private fileUri: string | null = null;

    async connect(): Promise<void> {
        // Request permission to a directory (e.g. Google Drive root or specific folder)
        const permissions = await StorageAccessFramework.requestDirectoryPermissionsAsync();

        if (!permissions.granted) {
            throw new Error('Permission denied');
        }

        this.directoryUri = permissions.directoryUri;
    }

    async listFiles(): Promise<{ name: string, uri: string }[]> {
        if (!this.directoryUri) throw new Error('Not connected');

        const files = await StorageAccessFramework.readDirectoryAsync(this.directoryUri);

        return files.map((uri: string) => ({
            name: decodeURIComponent(uri.split('/').pop() || 'unknown'),
            uri
        }));
    }

    async selectFile(uri: string) {
        this.fileUri = uri;
    }

    async createNewFile(filename: string) {
        if (!this.directoryUri) throw new Error('Not connected');
        this.fileUri = await StorageAccessFramework.createFileAsync(this.directoryUri, filename, 'application/octet-stream');
    }

    async read(): Promise<Uint8Array | null> {
        if (!this.fileUri) return null;

        try {
            const content = await FileSystem.readAsStringAsync(this.fileUri, {
                encoding: FileSystem.EncodingType.Base64
            });

            // Convert Base64 to Uint8Array
            const binaryString = atob(content);
            const bytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }
            return bytes;
        } catch (e) {
            console.error('Read error:', e);
            return null;
        }
    }

    async write(data: Uint8Array): Promise<void> {
        if (!this.fileUri) throw new Error('No file selected');

        // Convert Uint8Array to Base64
        let binary = '';
        const len = data.byteLength;
        for (let i = 0; i < len; i++) {
            binary += String.fromCharCode(data[i]);
        }
        const base64 = btoa(binary);

        await FileSystem.writeAsStringAsync(this.fileUri, base64, {
            encoding: FileSystem.EncodingType.Base64
        });
    }
}
