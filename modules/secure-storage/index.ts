import { requireNativeModule } from 'expo-modules-core';

// It loads the native module object from the JSI or falls back to
// the bridge module (from NativeModulesProxy) if the remote debugger is on.
const SecureStorageModule = requireNativeModule('SecureStorage');

export async function pickFile(): Promise<string | null> {
    return await SecureStorageModule.pickFile();
}

export async function createFile(filename: string): Promise<string | null> {
    return await SecureStorageModule.createFile(filename);
}

export async function readFile(uri: string): Promise<string> {
    return await SecureStorageModule.readFile(uri);
}

export async function saveFile(uri: string, base64Data: string): Promise<void> {
    return await SecureStorageModule.saveFile(uri, base64Data);
}
