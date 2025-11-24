import React, { useState, useEffect, useMemo, useRef } from 'react';
import { View, Text, TouchableOpacity, FlatList, TextInput, Alert, BackHandler, Modal, KeyboardAvoidingView, Platform, useColorScheme, Keyboard } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { runQuery, runCommand, deleteNote } from '../lib/db';
import * as Sharing from 'expo-sharing';
import * as Print from 'expo-print';
import { RichEditor, RichEditorRef } from '../components/RichEditor';

interface Note {
    id: string;
    title: string;
    content: string;
    updatedAt: number;
}

interface Props {
    onSync: (silent?: boolean) => void;
    onLogout: () => void;
}

export const EditorScreen: React.FC<Props> = ({ onSync, onLogout }) => {
    const [notes, setNotes] = useState<Note[]>([]);
    const [selectedNote, setSelectedNote] = useState<Note | null>(null);
    const [title, setTitle] = useState('');
    const [content, setContent] = useState('');
    const [searchQuery, setSearchQuery] = useState('');
    const [isSaving, setIsSaving] = useState(false);
    const [isKeyboardVisible, setKeyboardVisible] = useState(false);
    const [keyboardHeight, setKeyboardHeight] = useState(0);

    // Multi-select state
    const [selectionMode, setSelectionMode] = useState(false);
    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

    // Table Modal State
    const [isTableModalVisible, setIsTableModalVisible] = useState(false);
    const [tableRows, setTableRows] = useState('2');
    const [tableCols, setTableCols] = useState('2');

    // Link Modal State
    const [isLinkModalVisible, setIsLinkModalVisible] = useState(false);
    const [linkText, setLinkText] = useState('');
    const [linkUrl, setLinkUrl] = useState('');

    const editorRef = useRef<RichEditorRef>(null);
    const insets = useSafeAreaInsets();
    const colorScheme = useColorScheme();
    const isDarkMode = colorScheme === 'dark';

    useEffect(() => {
        loadNotes();
    }, []);

    // Keyboard listeners
    useEffect(() => {
        const showSubscription = Keyboard.addListener('keyboardDidShow', (e) => {
            setKeyboardVisible(true);
            if (Platform.OS === 'android') {
                setKeyboardHeight(e.endCoordinates.height + 50);
            }
        });
        const hideSubscription = Keyboard.addListener('keyboardDidHide', () => {
            setKeyboardVisible(false);
            if (Platform.OS === 'android') {
                setKeyboardHeight(0);
            }
        });

        return () => {
            showSubscription.remove();
            hideSubscription.remove();
        };
    }, []);

    // Auto-save effect
    useEffect(() => {
        if (!selectedNote) return;

        // Check if there are actual changes
        if (title === selectedNote.title && content === selectedNote.content) return;

        const timer = setTimeout(() => {
            saveNote();
        }, 1000); // 1 second debounce

        return () => clearTimeout(timer);
    }, [title, content, selectedNote]);

    // Handle Back Button
    useEffect(() => {
        const backAction = () => {
            if (selectedNote) {
                handleBack();
                return true;
            }
            if (selectionMode) {
                setSelectionMode(false);
                setSelectedIds(new Set());
                return true;
            }
            return false;
        };

        const backHandler = BackHandler.addEventListener(
            'hardwareBackPress',
            backAction
        );

        return () => backHandler.remove();
    }, [selectedNote, title, content, selectionMode]);

    const loadNotes = async () => {
        const result = await runQuery('SELECT * FROM notes ORDER BY updatedAt DESC');
        setNotes(result as Note[]);
    };

    const filteredNotes = useMemo(() => {
        if (!searchQuery) return notes;
        const lowerQuery = searchQuery.toLowerCase();
        return notes.filter(note =>
            (note.title && note.title.toLowerCase().includes(lowerQuery)) ||
            (note.content && note.content.toLowerCase().includes(lowerQuery))
        );
    }, [notes, searchQuery]);

    const saveNote = async () => {
        if (!selectedNote) return;
        setIsSaving(true);
        try {
            const now = Date.now();
            await runCommand('INSERT OR REPLACE INTO notes (id, title, content, updatedAt) VALUES (?, ?, ?, ?)', [selectedNote.id, title, content, now]);

            const updatedNote = { ...selectedNote, title, content, updatedAt: now };
            setSelectedNote(updatedNote); // Update reference to stop auto-save loop

            setNotes(prev => {
                const filtered = prev.filter(n => n.id !== updatedNote.id);
                return [updatedNote, ...filtered].sort((a, b) => b.updatedAt - a.updatedAt);
            });

            // Auto-sync to file silently
            onSync(true);
        } catch (e) {
            console.error('Auto-save failed:', e);
        } finally {
            setIsSaving(false);
        }
    };

    const handleBack = async () => {
        if (selectedNote) {
            // Force save if pending changes exist
            if (title !== selectedNote.title || content !== selectedNote.content) {
                await saveNote();
            }
            setSelectedNote(null);
            // Sync when leaving the note
            onSync(true);
        }
    };

    const handleDelete = async () => {
        if (!selectedNote) return;

        Alert.alert(
            "Delete Note",
            "Are you sure you want to delete this note?",
            [
                { text: "Cancel", style: "cancel" },
                {
                    text: "Delete",
                    style: "destructive",
                    onPress: async () => {
                        await deleteNote(selectedNote.id);
                        await loadNotes();
                        setSelectedNote(null);
                        onSync();
                    }
                }
            ]
        );
    };

    const openNote = (note: Note) => {
        setSelectedNote(note);
        setTitle(note.title);
        setContent(note.content);
    };

    const startCreate = () => {
        const newId = Math.random().toString(36).substring(7);
        const newNote = { id: newId, title: '', content: '', updatedAt: Date.now() };
        setSelectedNote(newNote);
        setTitle('');
        setContent('');
    };

    const handleLongPress = (id: string) => {
        if (!selectionMode) {
            setSelectionMode(true);
            const newSet = new Set<string>();
            newSet.add(id);
            setSelectedIds(newSet);
        }
    };

    const handlePress = (note: Note) => {
        if (selectionMode) {
            const newSet = new Set(selectedIds);
            if (newSet.has(note.id)) {
                newSet.delete(note.id);
                if (newSet.size === 0) {
                    setSelectionMode(false);
                }
            } else {
                newSet.add(note.id);
            }
            setSelectedIds(newSet);
        } else {
            openNote(note);
        }
    };

    const handleBulkDelete = () => {
        Alert.alert(
            "Delete Selected",
            `Are you sure you want to delete ${selectedIds.size} notes?`,
            [
                { text: "Cancel", style: "cancel" },
                {
                    text: "Delete",
                    style: "destructive",
                    onPress: async () => {
                        for (const id of selectedIds) {
                            await deleteNote(id);
                        }
                        await loadNotes();
                        setSelectionMode(false);
                        setSelectedIds(new Set());
                        onSync();
                    }
                }
            ]
        );
    };

    const stripHtml = (html: string) => {
        return html.replace(/<[^>]*>?/gm, '');
    };

    const handleExport = async (format: 'text' | 'csv' | 'pdf') => {
        if (selectedIds.size === 0) return;

        const selectedNotes = notes.filter(n => selectedIds.has(n.id));
        let uri = '';

        try {
            if (format === 'text') {
                const textContent = selectedNotes.map(n =>
                    `Title: ${n.title}\nDate: ${new Date(n.updatedAt).toLocaleString()}\n\n${stripHtml(n.content)}\n\n-------------------\n\n`
                ).join('');

                const html = `<html><body><pre>${textContent}</pre></body></html>`;
                const { uri: fileUri } = await Print.printToFileAsync({ html: html });
                uri = fileUri;

            } else if (format === 'csv') {
                const html = `<html><body><pre>${selectedNotes.map(n => `${n.id},${n.title},${stripHtml(n.content).replace(/,/g, ' ')}`).join('\\n')}</pre></body></html>`;
                const { uri: fileUri } = await Print.printToFileAsync({ html: html });
                uri = fileUri;
            } else if (format === 'pdf') {
                const htmlContent = `
                    <html>
                        <head>
                            <style>
                                body { font-family: sans-serif; padding: 20px; }
                                .note { margin-bottom: 30px; border-bottom: 1px solid #ccc; padding-bottom: 20px; }
                                h1 { font-size: 24px; color: #333; }
                                .meta { color: #666; font-size: 12px; margin-bottom: 10px; }
                                .content { font-size: 14px; line-height: 1.6; }
                                table { border-collapse: collapse; width: 100%; margin: 10px 0; border: 1px solid #ccc; }
                                th, td { border: 1px solid #ccc; padding: 8px; text-align: left; }
                            </style>
                        </head>
                        <body>
                            ${selectedNotes.map(n => `
                                <div class="note">
                                    <h1>${n.title || 'Untitled'}</h1>
                                    <div class="meta">${new Date(n.updatedAt).toLocaleString()}</div>
                                    <div class="content">${n.content}</div>
                                </div>
                            `).join('')}
                        </body>
                    </html>
                `;
                const { uri: fileUri } = await Print.printToFileAsync({ html: htmlContent });
                uri = fileUri;
            }

            if (uri) {
                await Sharing.shareAsync(uri);
            }
        } catch (e) {
            Alert.alert('Export Failed', String(e));
        }
    };

    const handleFormat = (type: 'bold' | 'italic' | 'list' | 'link' | 'table') => {
        if (!editorRef.current) return;

        switch (type) {
            case 'bold':
                editorRef.current.format('bold');
                break;
            case 'italic':
                editorRef.current.format('italic');
                break;
            case 'list':
                editorRef.current.format('insertUnorderedList');
                break;
            case 'link':
                setLinkText('');
                setLinkUrl('');
                setIsLinkModalVisible(true);
                break;
            case 'table':
                setIsTableModalVisible(true);
                break;
        }
    };

    const insertTable = () => {
        const rows = parseInt(tableRows) || 2;
        const cols = parseInt(tableCols) || 2;

        let html = '<table style="width:100%"><thead><tr>';
        for (let i = 0; i < cols; i++) {
            html += '<th>Header</th>';
        }
        html += '</tr></thead><tbody>';
        for (let i = 0; i < rows; i++) {
            html += '<tr>';
            for (let j = 0; j < cols; j++) {
                html += '<td>Cell</td>';
            }
            html += '</tr>';
        }
        html += '</tbody></table><p><br/></p>';

        editorRef.current?.insertHtml(html);
        setIsTableModalVisible(false);
        setTableRows('2');
        setTableCols('2');
    };

    const insertLink = () => {
        if (linkUrl) {
            const text = linkText || linkUrl;
            const html = `<a href="${linkUrl}">${text}</a>`;
            editorRef.current?.insertHtml(html);
        }
        setIsLinkModalVisible(false);
        setLinkText('');
        setLinkUrl('');
    };

    if (selectedNote) {
        return (
            <SafeAreaView className="flex-1 bg-white dark:bg-gray-900" edges={['top', 'left', 'right']}>
                <View className="p-4 flex-row justify-between items-center border-b border-gray-200 dark:border-gray-800">
                    <TouchableOpacity onPress={handleBack} className="flex-row items-center">
                        <Text className="text-blue-600 dark:text-blue-400 text-lg mr-1">‹</Text>
                        <Text className="text-blue-600 dark:text-blue-400 text-lg">Back</Text>
                    </TouchableOpacity>

                    <View className="flex-row items-center gap-4">
                        {isSaving && (
                            <Text className="text-gray-500 text-xs">Saving...</Text>
                        )}
                        <TouchableOpacity onPress={handleDelete} className="bg-red-100 dark:bg-red-900/50 px-3 py-1 rounded-lg border border-red-200 dark:border-red-800">
                            <Text className="text-red-600 dark:text-red-400 font-semibold">Delete</Text>
                        </TouchableOpacity>
                    </View>
                </View>

                {Platform.OS === 'ios' ? (
                    <KeyboardAvoidingView
                        behavior="padding"
                        className="flex-1"
                        keyboardVerticalOffset={0}
                    >
                        <TextInput
                            className="text-2xl font-bold text-black dark:text-white p-4 pb-2"
                            placeholder="Title"
                            placeholderTextColor="#9CA3AF"
                            value={title}
                            onChangeText={setTitle}
                        />

                        <RichEditor
                            ref={editorRef}
                            initialContent={content}
                            onChange={setContent}
                            isDarkMode={isDarkMode}
                        />

                        {/* Toolbar */}
                        <View
                            className="flex-row bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 p-2 justify-around items-center"
                            style={{ paddingBottom: isKeyboardVisible ? 0 : insets.bottom }}
                        >
                            <TouchableOpacity onPress={() => handleFormat('bold')} className="p-2">
                                <Text className="text-black dark:text-white font-bold text-lg">B</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => handleFormat('italic')} className="p-2">
                                <Text className="text-black dark:text-white italic text-lg">I</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => handleFormat('list')} className="p-2">
                                <Text className="text-black dark:text-white text-lg">•</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => handleFormat('link')} className="p-2">
                                <Text className="text-black dark:text-white text-lg">🔗</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => handleFormat('table')} className="p-2">
                                <Text className="text-black dark:text-white text-lg">▦</Text>
                            </TouchableOpacity>
                        </View>
                    </KeyboardAvoidingView>
                ) : (
                    <View style={{ flex: 1, paddingBottom: keyboardHeight }}>
                        <TextInput
                            className="text-2xl font-bold text-black dark:text-white p-4 pb-2"
                            placeholder="Title"
                            placeholderTextColor="#9CA3AF"
                            value={title}
                            onChangeText={setTitle}
                        />

                        <RichEditor
                            ref={editorRef}
                            initialContent={content}
                            onChange={setContent}
                            isDarkMode={isDarkMode}
                        />

                        {/* Toolbar */}
                        <View
                            className="flex-row bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 p-2 justify-around items-center"
                            style={{ paddingBottom: isKeyboardVisible ? 0 : insets.bottom }}
                        >
                            <TouchableOpacity onPress={() => handleFormat('bold')} className="p-2">
                                <Text className="text-black dark:text-white font-bold text-lg">B</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => handleFormat('italic')} className="p-2">
                                <Text className="text-black dark:text-white italic text-lg">I</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => handleFormat('list')} className="p-2">
                                <Text className="text-black dark:text-white text-lg">•</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => handleFormat('link')} className="p-2">
                                <Text className="text-black dark:text-white text-lg">🔗</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={() => handleFormat('table')} className="p-2">
                                <Text className="text-black dark:text-white text-lg">▦</Text>
                            </TouchableOpacity>
                        </View>
                    </View>
                )}

                {/* Table Modal */}
                <Modal
                    visible={isTableModalVisible}
                    transparent={true}
                    animationType="fade"
                    onRequestClose={() => setIsTableModalVisible(false)}
                >
                    <View className="flex-1 bg-black/50 justify-center items-center p-4">
                        <View className="bg-white dark:bg-gray-800 p-6 rounded-2xl w-full max-w-sm border border-gray-200 dark:border-gray-700 shadow-xl">
                            <Text className="text-xl font-bold text-black dark:text-white mb-4">Insert Table</Text>

                            <View className="flex-row gap-4 mb-6">
                                <View className="flex-1">
                                    <Text className="text-gray-600 dark:text-gray-400 mb-2 text-sm">Rows</Text>
                                    <TextInput
                                        className="bg-gray-100 dark:bg-gray-900 text-black dark:text-white p-3 rounded-lg border border-gray-200 dark:border-gray-700 text-center text-lg"
                                        keyboardType="number-pad"
                                        value={tableRows}
                                        onChangeText={setTableRows}
                                        selectTextOnFocus
                                    />
                                </View>
                                <View className="flex-1">
                                    <Text className="text-gray-600 dark:text-gray-400 mb-2 text-sm">Columns</Text>
                                    <TextInput
                                        className="bg-gray-100 dark:bg-gray-900 text-black dark:text-white p-3 rounded-lg border border-gray-200 dark:border-gray-700 text-center text-lg"
                                        keyboardType="number-pad"
                                        value={tableCols}
                                        onChangeText={setTableCols}
                                        selectTextOnFocus
                                    />
                                </View>
                            </View>

                            <View className="flex-row gap-3">
                                <TouchableOpacity
                                    onPress={() => setIsTableModalVisible(false)}
                                    className="flex-1 bg-gray-200 dark:bg-gray-700 p-3 rounded-xl items-center"
                                >
                                    <Text className="text-black dark:text-white font-semibold">Cancel</Text>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={insertTable}
                                    className="flex-1 bg-blue-600 p-3 rounded-xl items-center"
                                >
                                    <Text className="text-white font-semibold">Insert</Text>
                                </TouchableOpacity>
                            </View>
                        </View>
                    </View>
                </Modal>

                {/* Link Modal */}
                <Modal
                    visible={isLinkModalVisible}
                    transparent={true}
                    animationType="fade"
                    onRequestClose={() => setIsLinkModalVisible(false)}
                >
                    <View className="flex-1 bg-black/50 justify-center items-center p-4">
                        <View className="bg-white dark:bg-gray-800 p-6 rounded-2xl w-full max-w-sm border border-gray-200 dark:border-gray-700 shadow-xl">
                            <Text className="text-xl font-bold text-black dark:text-white mb-4">Insert Link</Text>

                            <View className="mb-4">
                                <Text className="text-gray-600 dark:text-gray-400 mb-2 text-sm">Text to display (optional)</Text>
                                <TextInput
                                    className="bg-gray-100 dark:bg-gray-900 text-black dark:text-white p-3 rounded-lg border border-gray-200 dark:border-gray-700 text-lg"
                                    placeholder="e.g. My Website"
                                    placeholderTextColor="#9CA3AF"
                                    value={linkText}
                                    onChangeText={setLinkText}
                                />
                            </View>

                            <View className="mb-6">
                                <Text className="text-gray-600 dark:text-gray-400 mb-2 text-sm">URL</Text>
                                <TextInput
                                    className="bg-gray-100 dark:bg-gray-900 text-black dark:text-white p-3 rounded-lg border border-gray-200 dark:border-gray-700 text-lg"
                                    placeholder="https://example.com"
                                    placeholderTextColor="#9CA3AF"
                                    value={linkUrl}
                                    onChangeText={setLinkUrl}
                                    autoCapitalize="none"
                                    keyboardType="url"
                                />
                            </View>

                            <View className="flex-row gap-3">
                                <TouchableOpacity
                                    onPress={() => setIsLinkModalVisible(false)}
                                    className="flex-1 bg-gray-200 dark:bg-gray-700 p-3 rounded-xl items-center"
                                >
                                    <Text className="text-black dark:text-white font-semibold">Cancel</Text>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={insertLink}
                                    className="flex-1 bg-blue-600 p-3 rounded-xl items-center"
                                >
                                    <Text className="text-white font-semibold">Insert</Text>
                                </TouchableOpacity>
                            </View>
                        </View>
                    </View>
                </Modal>
            </SafeAreaView>
        );
    }

    return (
        <SafeAreaView className="flex-1 bg-white dark:bg-gray-900" edges={['top', 'left', 'right']}>
            <View className="p-4 border-b border-gray-200 dark:border-gray-800">
                <View className="flex-row justify-between items-center mb-4">
                    <Text className="text-2xl font-bold text-black dark:text-white">
                        {selectionMode ? `${selectedIds.size} Selected` : 'My Notes'}
                    </Text>
                    <View className="flex-row gap-4">
                        {!selectionMode && (
                            <>
                                <TouchableOpacity onPress={() => onSync(false)} className="bg-gray-100 dark:bg-gray-800 px-3 py-1 rounded-full">
                                    <Text className="text-blue-600 dark:text-blue-400 font-medium">Sync</Text>
                                </TouchableOpacity>
                                <TouchableOpacity onPress={onLogout} className="bg-gray-100 dark:bg-gray-800 px-3 py-1 rounded-full">
                                    <Text className="text-red-600 dark:text-red-400 font-medium">Lock</Text>
                                </TouchableOpacity>
                            </>
                        )}
                        {selectionMode && (
                            <TouchableOpacity onPress={() => {
                                setSelectionMode(false);
                                setSelectedIds(new Set());
                            }}>
                                <Text className="text-blue-600 dark:text-blue-400 text-lg">Done</Text>
                            </TouchableOpacity>
                        )}
                    </View>
                </View>

                {/* Search Bar */}
                {!selectionMode && (
                    <View className="bg-gray-100 dark:bg-gray-800 rounded-xl p-3 flex-row items-center">
                        <Text className="text-gray-500 dark:text-gray-400 mr-2">🔍</Text>
                        <TextInput
                            className="flex-1 text-black dark:text-white text-base"
                            placeholder="Search notes..."
                            placeholderTextColor="#9CA3AF"
                            value={searchQuery}
                            onChangeText={setSearchQuery}
                        />
                        {searchQuery.length > 0 && (
                            <TouchableOpacity onPress={() => setSearchQuery('')}>
                                <Text className="text-gray-500 dark:text-gray-400">✕</Text>
                            </TouchableOpacity>
                        )}
                    </View>
                )}
            </View>

            <FlatList
                data={filteredNotes}
                keyExtractor={item => item.id}
                contentContainerStyle={{ padding: 16, paddingBottom: 100 }}
                ItemSeparatorComponent={() => <View className="h-3" />}
                renderItem={({ item }) => {
                    const isSelected = selectedIds.has(item.id);
                    return (
                        <TouchableOpacity
                            className={`p-4 rounded-xl border ${isSelected ? 'bg-blue-100 dark:bg-blue-900/30 border-blue-500' : 'bg-white dark:bg-gray-800 border-gray-200 dark:border-gray-700'} active:bg-gray-100 dark:active:bg-gray-700 shadow-sm`}
                            onPress={() => handlePress(item)}
                            onLongPress={() => handleLongPress(item.id)}
                            delayLongPress={300}
                        >
                            <View className="flex-row justify-between items-start">
                                <View className="flex-1">
                                    <Text className="text-black dark:text-white font-bold text-lg mb-1" numberOfLines={1}>
                                        {item.title || 'Untitled'}
                                    </Text>
                                    <Text className="text-gray-600 dark:text-gray-400 text-base leading-5" numberOfLines={3}>
                                        {stripHtml(item.content) || 'No content'}
                                    </Text>
                                </View>
                                {selectionMode && (
                                    <View className={`w-6 h-6 rounded-full border-2 ml-3 justify-center items-center ${isSelected ? 'bg-blue-500 border-blue-500' : 'border-gray-400 dark:border-gray-500'}`}>
                                        {isSelected && <Text className="text-white text-xs">✓</Text>}
                                    </View>
                                )}
                            </View>
                            <Text className="text-gray-500 dark:text-gray-600 text-xs mt-3 text-right">
                                {new Date(item.updatedAt).toLocaleDateString()} • {new Date(item.updatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                            </Text>
                        </TouchableOpacity>
                    );
                }}
                ListEmptyComponent={() => (
                    <View className="flex-1 justify-center items-center mt-20">
                        <Text className="text-gray-500 dark:text-gray-600 text-lg">No notes found</Text>
                    </View>
                )}
            />

            {!selectionMode && (
                <TouchableOpacity
                    className="absolute right-6 bg-blue-600 w-16 h-16 rounded-full justify-center items-center shadow-lg border border-blue-400"
                    style={{ bottom: 24 + insets.bottom }}
                    onPress={startCreate}
                >
                    <Text className="text-white text-4xl font-light pb-1">+</Text>
                </TouchableOpacity>
            )}

            {selectionMode && (
                <View
                    className="absolute bottom-0 left-0 right-0 bg-white dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 p-4 flex-row justify-around"
                    style={{ paddingBottom: 16 + insets.bottom }}
                >
                    <TouchableOpacity onPress={() => handleExport('text')} className="items-center">
                        <Text className="text-2xl mb-1">📝</Text>
                        <Text className="text-gray-600 dark:text-gray-300 text-xs">Text</Text>
                    </TouchableOpacity>
                    <TouchableOpacity onPress={() => handleExport('csv')} className="items-center">
                        <Text className="text-2xl mb-1">📊</Text>
                        <Text className="text-gray-600 dark:text-gray-300 text-xs">CSV</Text>
                    </TouchableOpacity>
                    <TouchableOpacity onPress={() => handleExport('pdf')} className="items-center">
                        <Text className="text-2xl mb-1">📄</Text>
                        <Text className="text-gray-600 dark:text-gray-300 text-xs">PDF</Text>
                    </TouchableOpacity>
                    <TouchableOpacity onPress={handleBulkDelete} className="items-center">
                        <Text className="text-2xl mb-1">🗑️</Text>
                        <Text className="text-red-600 dark:text-red-400 text-xs">Delete</Text>
                    </TouchableOpacity>
                </View>
            )}
        </SafeAreaView>
    );
};
