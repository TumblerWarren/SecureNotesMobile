import React, { useState } from 'react';
import * as DocumentPicker from 'expo-document-picker';
import { View, StatusBar, Alert } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { WelcomeScreen } from './src/screens/WelcomeScreen';
import { UnlockScreen } from './src/screens/UnlockScreen';
import { EditorScreen } from './src/screens/EditorScreen';
import { SafProvider } from './src/lib/storage/SafProvider';
import { initDB, importDB, exportDB, resetDB } from './src/lib/db';
import { deriveKey, encryptData, decryptData, generateSalt } from './src/lib/crypto';
import './global.css';
import { CreateDbScreen } from './src/screens/CreateDbScreen';

type AppState = 'WELCOME' | 'CREATE_DB_NAME' | 'UNLOCK' | 'CREATE_PASSWORD' | 'READY';

export default function App() {
  const [state, setState] = useState<AppState>('WELCOME');
  const [provider] = useState(() => new SafProvider());
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const [encryptedData, setEncryptedData] = useState<Uint8Array | null>(null);
  const [masterKey, setMasterKey] = useState<string | null>(null);
  const [salt, setSalt] = useState<Uint8Array | null>(null);

  const handleOpen = async () => {
    setLoading(true);
    setError('');
    try {
      await resetDB(); // Clear any previous session

      const result = await DocumentPicker.getDocumentAsync({
        type: [
          'application/x-sqlite3',
          'application/vnd.sqlite3',
          'application/octet-stream',
          'public.database'
        ],
        copyToCacheDirectory: false, // We want the original URI to write back to it
      });

      if (result.canceled) {
        setLoading(false);
        return;
      }

      const asset = result.assets[0];
      const uri = asset.uri;
      const fileName = asset.name || '';

      // Secondary check for .db extension
      if (!fileName.toLowerCase().endsWith('.db')) {
        setLoading(false);
        Alert.alert("Invalid File", "Please select a valid SQLite database file (.db)");
        return;
      }

      // Select the file in the provider
      await provider.selectFile(uri);

      // Read and unlock
      const data = await provider.read();
      if (data && data.length > 0) {
        setEncryptedData(data);
        setState('UNLOCK');
      } else {
        // Empty file? Should not happen for existing, but if so, treat as new
        setState('CREATE_PASSWORD');
      }
    } catch (e) {
      console.error(e);
      setError('Failed to open file: ' + e);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateNew = async (customName: string) => {
    setLoading(true);
    setError('');

    // Yield to UI thread to allow loading spinner to render
    await new Promise(resolve => setTimeout(resolve, 100));

    try {
      await resetDB(); // Clear any previous session
      await provider.connect();
      const filename = customName.endsWith('.db') ? customName : `${customName}.db`;
      await provider.createNewFile(filename);
      setState('CREATE_PASSWORD');
    } catch (e) {
      console.error(e);
      setError('Failed to create: ' + e);
    } finally {
      setLoading(false);
    }
  };

  const handleUnlock = async (password: string) => {
    if (!encryptedData) return;
    setLoading(true);
    setError('');

    // Yield to UI thread to allow loading spinner to render
    await new Promise(resolve => setTimeout(resolve, 100));

    try {
      const s = encryptedData.slice(0, 16);
      setSalt(s);
      const derivedKey = await deriveKey(password, s);

      const ciphertext = encryptedData.slice(16);
      const dbData = await decryptData(ciphertext, derivedKey);

      // Verify SQLite Header (SQLite format 3)
      // Bytes: 53 51 4c 69 74 65 20 66 6f 72 6d 61 74 20 33 00
      const headerBytes = [0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33];
      let isValid = true;
      for (let i = 0; i < headerBytes.length; i++) {
        if (dbData[i] !== headerBytes[i]) {
          isValid = false;
          break;
        }
      }

      if (!isValid) {
        throw new Error('Invalid password or corrupted database file.');
      }

      await importDB(dbData);
      setMasterKey(derivedKey);
      setState('READY');
    } catch (e) {
      console.error(e);
      setError('Incorrect password or corrupted file.');
      await resetDB(); // Ensure we don't leave any partial state
    } finally {
      setLoading(false);
    }
  };

  const handleCreatePassword = async (password: string) => {
    setLoading(true);

    // Yield to UI thread to allow loading spinner to render
    await new Promise(resolve => setTimeout(resolve, 100));

    try {
      const s = generateSalt();
      setSalt(s);
      const derivedKey = await deriveKey(password, s);

      await initDB();
      setMasterKey(derivedKey);

      // Initial save
      await performSync(derivedKey, s);

      setState('READY');
    } catch (e) {
      console.error(e);
      setError('Failed to create database: ' + e);
    } finally {
      setLoading(false);
    }
  };

  const performSync = async (k: string, s: Uint8Array) => {
    const dbData = await exportDB();
    const encrypted = await encryptData(dbData, k);

    const finalData = new Uint8Array(s.length + encrypted.length);
    finalData.set(s);
    finalData.set(encrypted, s.length);

    await provider.write(finalData);
  };

  const handleSync = async (silent = false) => {
    if (masterKey && salt) {
      if (!silent) setLoading(true);
      try {
        await performSync(masterKey, salt);
      } catch (e) {
        console.error(e);
        if (!silent) alert('Sync failed: ' + e);
      } finally {
        if (!silent) setLoading(false);
      }
    }
  };

  const handleLogout = () => {
    setMasterKey(null);
    setSalt(null);
    setEncryptedData(null);
    setState('WELCOME');
  };

  return (
    <SafeAreaProvider>
      <View className="flex-1 bg-gray-900">
        <StatusBar barStyle="light-content" backgroundColor="#111827" />

        {state === 'WELCOME' && (
          <WelcomeScreen
            onOpen={handleOpen}
            onCreate={() => setState('CREATE_DB_NAME')}
            loading={loading}
            error={error}
          />
        )}

        {state === 'CREATE_DB_NAME' && (
          <CreateDbScreen
            onSubmit={handleCreateNew}
            onCancel={() => setState('WELCOME')}
          />
        )}

        {state === 'UNLOCK' && (
          <UnlockScreen
            onSubmit={handleUnlock}
            isNew={false}
            loading={loading}
            error={error}
            onCancel={() => setState('WELCOME')}
            onErrorDismiss={() => setError('')}
          />
        )}

        {state === 'CREATE_PASSWORD' && (
          <UnlockScreen
            onSubmit={handleCreatePassword}
            isNew={true}
            loading={loading}
            error={error}
            onCancel={() => setState('WELCOME')}
            onErrorDismiss={() => setError('')}
          />
        )}

        {state === 'READY' && (
          <EditorScreen
            onSync={handleSync}
            onLogout={handleLogout}
          />
        )}
      </View>
    </SafeAreaProvider>
  );
}
