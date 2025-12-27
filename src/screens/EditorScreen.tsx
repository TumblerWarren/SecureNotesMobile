import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { View, Text, TouchableOpacity, FlatList, TextInput, Alert, BackHandler, Modal, KeyboardAvoidingView, Platform, useColorScheme, Keyboard, Image } from 'react-native';
import DraggableFlatList, { ScaleDecorator, RenderItemParams } from 'react-native-draggable-flatlist';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { runQuery, runCommand, deleteNote } from '../lib/db';
import * as Sharing from 'expo-sharing';
import * as Print from 'expo-print';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system/legacy';
import * as DocumentPicker from 'expo-document-picker';
import { Audio } from 'expo-av';
import { RichEditor, RichEditorRef } from '../components/RichEditor';
import { runOnJS } from 'react-native-reanimated';
import { DrawingEditor, DrawingEditorRef } from '../components/DrawingEditor';
import {
    Pin,
    GripVertical,
    Pencil,
    FileText,
    Plus,
    FileSpreadsheet,
    File as FilePdf,
    Trash2,
    Type,
    Bold,
    Italic,
    List,
    Link,
    Table,
    Camera,
    Music,
    Square,
    Check,
    ChevronLeft,
    Search,
    RefreshCw,
    Lock,
    PinOff,
    Eraser,
    X,
    Cloud
} from 'lucide-react-native';

interface Note {
    id: string;
    title: string;
    content: string;
    updatedAt: number;
    isPinned?: number;
    type?: 'text' | 'drawing';
}

interface Props {
    onSync: (silent?: boolean) => void;
    onLogout: () => void;
    onExport: () => void;
}

export const EditorScreen: React.FC<Props> = ({ onSync, onLogout, onExport }) => {
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

    // Toolbar State
    const [showFormatMenu, setShowFormatMenu] = useState(false);
    const [showAttachMenu, setShowAttachMenu] = useState(false);

    // FAB State
    const [showFabMenu, setShowFabMenu] = useState(false);

    // Drawing State
    const [initialDrawing, setInitialDrawing] = useState('');
    const drawingRef = useRef<DrawingEditorRef>(null);

    // Text Formatting State
    const [activeFormats, setActiveFormats] = useState<string[]>([]);

    useEffect(() => {
        if (selectedNote?.type === 'drawing') {
            console.log('[EditorScreen] Initializing drawing for note:', selectedNote.id);
            setInitialDrawing(selectedNote.content);
        }
    }, [selectedNote?.id]);

    const editorRef = useRef<RichEditorRef>(null);
    const insets = useSafeAreaInsets();
    const colorScheme = useColorScheme();



    // Handling Selection Change from RichEditor
    const handleSelectionChange = useCallback((formats: string[]) => {
        setActiveFormats(formats);
    }, []);





    const isDarkMode = colorScheme === 'dark';

    useEffect(() => {
        console.log('[EditorScreen] Mounted');
        loadNotes();
        return () => console.log('[EditorScreen] Unmounted');
    }, []);

    // Keyboard listeners
    useEffect(() => {
        const showSubscription = Keyboard.addListener('keyboardDidShow', (e) => {
            console.log('[EditorScreen] Keyboard shown');
            setKeyboardVisible(true);
            if (Platform.OS === 'android') {
                setKeyboardHeight(e.endCoordinates.height + 50);
            }
        });
        const hideSubscription = Keyboard.addListener('keyboardDidHide', () => {
            console.log('[EditorScreen] Keyboard hidden');
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
            console.log('[EditorScreen] Auto-save triggered');
            saveNote();
        }, 1000); // 1 second debounce

        return () => clearTimeout(timer);
    }, [title, content, selectedNote]);

    // Handle Back Button
    useEffect(() => {
        const backAction = () => {
            console.log('[EditorScreen] Hardware back press');
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
        console.log('[EditorScreen] Loading notes');
        const result = await runQuery('SELECT * FROM notes ORDER BY isPinned DESC, updatedAt DESC');
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
        console.log('[EditorScreen] saveNote called');
        if (!selectedNote) return;
        setIsSaving(true);
        try {
            const now = Date.now();
            const isPinned = selectedNote.isPinned || 0;
            const type: 'text' | 'drawing' = selectedNote.type || 'text';

            await runCommand('INSERT OR REPLACE INTO notes (id, title, content, updatedAt, isPinned, type) VALUES (?, ?, ?, ?, ?, ?)', [selectedNote.id, title, content, now, isPinned, type]);

            const updatedNote: Note = { ...selectedNote, title, content, updatedAt: now, isPinned, type };
            setSelectedNote(updatedNote); // Update reference to stop auto-save loop

            setNotes(prev => {
                const filtered = prev.filter(n => n.id !== updatedNote.id);
                return [updatedNote, ...filtered].sort((a, b) => {
                    if (a.isPinned !== b.isPinned) return (b.isPinned || 0) - (a.isPinned || 0);
                    return b.updatedAt - a.updatedAt;
                });
            });

            // Auto-sync to file silently
            onSync(true);
        } catch (e) {
            console.error('Auto-save failed:', e);
        } finally {
            setIsSaving(false);
        }
    };

    const togglePin = async () => {
        console.log('[EditorScreen] togglePin called');
        if (!selectedNote) return;
        const newPinnedState = selectedNote.isPinned ? 0 : 1;
        const updatedNote = { ...selectedNote, isPinned: newPinnedState };

        setSelectedNote(updatedNote);

        // Update local list immediately for responsiveness
        setNotes(prev => {
            const filtered = prev.filter(n => n.id !== updatedNote.id);
            return [updatedNote, ...filtered].sort((a, b) => {
                if (a.isPinned !== b.isPinned) return (b.isPinned || 0) - (a.isPinned || 0);
                return b.updatedAt - a.updatedAt;
            });
        });

        // Persist to DB
        await runCommand('UPDATE notes SET isPinned = ? WHERE id = ?', [newPinnedState, selectedNote.id]);
        onSync(true);
    };

    const handleBack = async () => {
        console.log('[EditorScreen] handleBack called');
        if (selectedNote) {
            // Force save if pending changes exist
            if (title !== selectedNote.title || content !== selectedNote.content) {
                console.log('[EditorScreen] Saving pending changes before back');
                await saveNote();
            }
            setSelectedNote(null);
            // Sync when leaving the note
            onSync(true);
        }
    };

    const handleDelete = async () => {
        console.log('[EditorScreen] handleDelete called');
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
                        console.log('[EditorScreen] Deleting note:', selectedNote.id);
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
        console.log('[EditorScreen] Opening note:', note.id);
        setSelectedNote(note);
        setTitle(note.title);
        setContent(note.content);
    };

    const startCreate = (type: 'text' | 'drawing' = 'text') => {
        console.log('[EditorScreen] Creating new note:', type);
        const newId = Math.random().toString(36).substring(7);
        const newNote: Note = { id: newId, title: '', content: '', updatedAt: Date.now(), type };
        setSelectedNote(newNote);
        setTitle('');
        setContent('');
    };

    const handleLongPress = (id: string) => {
        console.log('[EditorScreen] handleLongPress:', id);
        if (!selectionMode) {
            setSelectionMode(true);
            const newSet = new Set<string>();
            newSet.add(id);
            setSelectedIds(newSet);
        }
    };

    const handlePress = (note: Note) => {
        if (selectionMode) {
            console.log('[EditorScreen] handlePress (selection mode):', note.id);
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
            console.log('[EditorScreen] handlePress (open mode):', note.id);
            openNote(note);
        }
    };

    const handleBulkDelete = () => {
        console.log('[EditorScreen] handleBulkDelete called');
        Alert.alert(
            "Delete Selected",
            `Are you sure you want to delete ${selectedIds.size} notes?`,
            [
                { text: "Cancel", style: "cancel" },
                {
                    text: "Delete",
                    style: "destructive",
                    onPress: async () => {
                        console.log('[EditorScreen] Executing bulk delete');
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

    const handleBulkPin = async () => {
        console.log('[EditorScreen] handleBulkPin called');
        const now = Date.now();

        // Smart Pin Logic:
        // 1. Check if all selected notes are already pinned
        const selectedNotes = notes.filter(n => selectedIds.has(n.id));
        const allPinned = selectedNotes.every(n => n.isPinned && n.isPinned > 0);

        if (allPinned) {
            // Unpin all
            console.log('[EditorScreen] Unpinning selected notes');
            for (const id of selectedIds) {
                await runCommand('UPDATE notes SET isPinned = 0 WHERE id = ?', [id]);
            }
        } else {
            // Pin all
            console.log('[EditorScreen] Pinning selected notes');
            for (const id of selectedIds) {
                await runCommand('UPDATE notes SET isPinned = ? WHERE id = ?', [now, id]);
            }
        }

        await loadNotes();
        setSelectionMode(false);
        setSelectedIds(new Set());
        onSync(true);
    };

    const stripHtml = (html: string) => {
        return html.replace(/<[^>]*>?/gm, '');
    };

    const getFirstImage = (html: string) => {
        const match = html.match(/<img[^>]+src="([^">]+)"/);
        return match ? match[1] : null;
    };

    const handleExport = async (format: 'text' | 'csv' | 'pdf') => {
        console.log('[EditorScreen] handleExport called:', format);
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
                console.log('[EditorScreen] Sharing exported file:', uri);
                await Sharing.shareAsync(uri);
            }
        } catch (e) {
            console.error('[EditorScreen] Export failed:', e);
            Alert.alert('Export Failed', String(e));
        }
    };

    const handleFormat = (type: 'bold' | 'italic' | 'list' | 'link' | 'table') => {
        console.log('[EditorScreen] handleFormat:', type);
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
        console.log('[EditorScreen] insertTable called');
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
    };

    const insertLink = () => {
        console.log('[EditorScreen] insertLink called');
        if (linkUrl) {
            const text = linkText || linkUrl;
            const html = `<a href="${linkUrl}">${text}</a>`;
            editorRef.current?.insertHtml(html);
        }
        setIsLinkModalVisible(false);
        setLinkText('');
        setLinkUrl('');
    };

    const handleImagePick = async () => {
        console.log('[EditorScreen] handleImagePick called');
        try {
            const permissionResult = await ImagePicker.requestMediaLibraryPermissionsAsync();

            if (permissionResult.granted === false) {
                Alert.alert("Permission Required", "You need to grant camera roll permissions to make this work!");
                return;
            }

            const result = await ImagePicker.launchImageLibraryAsync({
                mediaTypes: ImagePicker.MediaTypeOptions.Images,
                allowsEditing: false,
                quality: 0.5,
                base64: true,
            });

            if (!result.canceled && result.assets && result.assets.length > 0) {
                const asset = result.assets[0];
                console.log('[EditorScreen] Image picked, size:', asset.fileSize);
                if (asset.base64) {
                    // Ensure focus before inserting
                    editorRef.current?.format('focus');
                    setTimeout(() => {
                        const imageHtml = `<img src="data:image/jpeg;base64,${asset.base64}" style="max-width: 100%; height: auto; border-radius: 8px; margin: 10px 0;" /><br/>`;
                        editorRef.current?.insertHtml(imageHtml);
                    }, 100);
                } else {
                    Alert.alert("Error", "Could not get image data");
                }
            }
        } catch (e) {
            console.error('[EditorScreen] Image pick failed:', e);
            Alert.alert("Error", "Image pick failed: " + String(e));
        }
    };

    const handleAudioPick = async () => {
        try {
            const result = await DocumentPicker.getDocumentAsync({
                type: 'audio/*',
                copyToCacheDirectory: true
            });

            if (!result.canceled) {
                const asset = result.assets[0];
                console.log('[EditorScreen] Audio selected, URI:', asset.uri);

                const base64 = await FileSystem.readAsStringAsync(asset.uri, { encoding: 'base64' });
                const mimeType = asset.mimeType || 'audio/mpeg';

                // Ensure focus before inserting
                editorRef.current?.format('focus');

                setTimeout(() => {
                    const audioHtml = `<br/><audio controls src="data:${mimeType};base64,${base64}"></audio><br/>`;
                    editorRef.current?.insertHtml(audioHtml);
                }, 100);
            }
        } catch (e) {
            console.error('[EditorScreen] Audio pick failed:', e);
            Alert.alert("Error", "Audio pick failed: " + String(e));
        }
    };



    // Refs for callback access without re-rendering
    const selectedNoteRef = useRef(selectedNote);
    const titleRef = useRef(title);



    useEffect(() => {
        selectedNoteRef.current = selectedNote;
        titleRef.current = title;
    }, [selectedNote, title]);

    // Drawing Note Specific Handlers
    const handleDrawingSave = useCallback((signature: string) => {
        console.log('[EditorScreen] handleDrawingSave called, signature length:', signature.length);
        const currentNote = selectedNoteRef.current;
        const currentTitle = titleRef.current;

        // Update local state content
        setContent(signature);

        // Trigger save immediately
        if (currentNote) {
            const now = Date.now();
            const isPinned = currentNote.isPinned || 0;
            const type = 'drawing';
            runCommand('INSERT OR REPLACE INTO notes (id, title, content, updatedAt, isPinned, type) VALUES (?, ?, ?, ?, ?, ?)', [currentNote.id, currentTitle, signature, now, isPinned, type])
                .then(() => {
                    const updatedNote: Note = { ...currentNote, title: currentTitle, content: signature, updatedAt: now, isPinned, type };
                    setSelectedNote(updatedNote);
                    setNotes(prev => {
                        const filtered = prev.filter(n => n.id !== updatedNote.id);
                        return [updatedNote, ...filtered].sort((a, b) => {
                            if (a.isPinned !== b.isPinned) return (b.isPinned || 0) - (a.isPinned || 0);
                            return b.updatedAt - a.updatedAt;
                        });
                    });
                    onSync(true);
                });
        }
    }, []); // Empty dependency array! Uses refs.

    const handleSignatureClear = useCallback(() => {
        console.log('[EditorScreen] handleSignatureClear called');
        drawingRef.current?.clear();
        handleDrawingSave('');
    }, [handleDrawingSave]);


    // Drawing Note Specific Handlers


    if (selectedNote) {
        return (
            <SafeAreaView className="flex-1 bg-white dark:bg-gray-900" edges={['top', 'left', 'right']}>
                <View className="p-4 flex-row justify-between items-center border-b border-gray-200 dark:border-gray-800">
                    <TouchableOpacity onPress={handleBack} className="flex-row items-center">
                        <ChevronLeft size={24} color={isDarkMode ? '#60A5FA' : '#2563EB'} />
                        <Text className="text-blue-600 dark:text-blue-400 text-lg">Back</Text>
                    </TouchableOpacity >

                    <View className="flex-row items-center gap-4">
                        {isSaving && (
                            <Text className="text-gray-500 text-xs">Saving...</Text>
                        )}
                        {selectedNote.type === 'drawing' && (
                            <TouchableOpacity onPress={handleSignatureClear} className="flex-row items-center bg-gray-100 dark:bg-gray-800 px-3 py-1.5 rounded-lg border border-gray-200 dark:border-gray-700">
                                <Eraser size={16} color={isDarkMode ? '#9CA3AF' : '#6B7280'} style={{ marginRight: 4 }} />
                                <Text className="text-gray-600 dark:text-gray-400 font-semibold">Clear</Text>
                            </TouchableOpacity>
                        )}
                        <TouchableOpacity onPress={handleDelete} className="flex-row items-center bg-red-50 dark:bg-red-900/30 px-3 py-1.5 rounded-lg border border-red-100 dark:border-red-900/50">
                            <Trash2 size={16} color={isDarkMode ? '#F87171' : '#B91C1C'} style={{ marginRight: 4 }} />
                            <Text className="text-red-600 dark:text-red-400 font-semibold">Delete</Text>
                        </TouchableOpacity>
                        <TouchableOpacity onPress={togglePin} className={`flex-row items-center px-3 py-1.5 rounded-lg border ${selectedNote.isPinned ? 'bg-yellow-50 dark:bg-yellow-900/30 border-yellow-100 dark:border-yellow-900/50' : 'bg-gray-100 dark:bg-gray-800 border-gray-200 dark:border-gray-700'}`}>
                            {selectedNote.isPinned ? (
                                <PinOff size={16} color={isDarkMode ? '#FBBF24' : '#B45309'} style={{ marginRight: 4 }} />
                            ) : (
                                <Pin size={16} color={isDarkMode ? '#9CA3AF' : '#6B7280'} style={{ marginRight: 4 }} />
                            )}
                            <Text className={`${selectedNote.isPinned ? 'text-yellow-700 dark:text-yellow-400' : 'text-gray-600 dark:text-gray-400'} font-semibold`}>
                                {selectedNote.isPinned ? 'Unpin' : 'Pin'}
                            </Text>
                        </TouchableOpacity>
                    </View>
                </View>

                {selectedNote.type === 'drawing' ? (
                    <View className="flex-1 bg-white dark:bg-gray-900">
                        <TextInput
                            className="text-2xl font-bold text-black dark:text-white p-4 pb-2"
                            placeholder="Title"
                            placeholderTextColor="#9CA3AF"
                            value={title}
                            onChangeText={setTitle}
                        />
                        <View className="flex-1">
                            <DrawingEditor
                                ref={drawingRef}
                                initialContent={initialDrawing}
                                onSave={handleDrawingSave}
                                isDarkMode={isDarkMode}
                            />
                        </View>
                    </View>
                ) : (
                    Platform.OS === 'ios' ? (
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
                                className="flex-row bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 p-2 justify-between items-center"
                                style={{ paddingBottom: isKeyboardVisible ? 0 : insets.bottom }}
                            >
                                {/* Format Group */}
                                <View className="relative">
                                    <TouchableOpacity onPress={() => { setShowFormatMenu(!showFormatMenu); setShowAttachMenu(false); }} className="p-2 bg-gray-200 dark:bg-gray-700 rounded-lg">
                                        <Type size={20} color={isDarkMode ? 'white' : 'black'} />
                                    </TouchableOpacity>
                                    {showFormatMenu && (
                                        <View className="absolute bottom-12 left-0 bg-white dark:bg-gray-800 rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 p-2 flex-row gap-2 min-w-[200px]">
                                            <TouchableOpacity onPress={() => handleFormat('bold')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Bold size={18} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('italic')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Italic size={18} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('list')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <List size={18} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('link')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Link size={18} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('table')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Table size={18} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                        </View>
                                    )}
                                </View>

                                {/* Attach Group */}
                                <View className="relative">
                                    <TouchableOpacity onPress={() => { setShowAttachMenu(!showAttachMenu); setShowFormatMenu(false); }} className="p-2 bg-gray-200 dark:bg-gray-700 rounded-lg">
                                        <Plus size={20} color={isDarkMode ? 'white' : 'black'} />
                                    </TouchableOpacity>
                                    {showAttachMenu && (
                                        <View className="absolute bottom-12 right-0 bg-white dark:bg-gray-800 rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 p-2 flex-row gap-2">
                                            <TouchableOpacity onPress={handleImagePick} className="p-2 bg-gray-100 dark:bg-gray-700 rounded flex-row items-center justify-center w-10 h-10">
                                                <Camera size={20} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={handleAudioPick} className="p-2 bg-gray-100 dark:bg-gray-700 rounded flex-row items-center justify-center w-10 h-10">
                                                <Music size={20} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                        </View>
                                    )}
                                </View>
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
                                onSelectionChange={handleSelectionChange}
                                isDarkMode={isDarkMode}
                            />

                            {/* Toolbar */}
                            <View
                                className="flex-row bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 p-2 justify-between items-center"
                                style={{ paddingBottom: isKeyboardVisible ? 0 : insets.bottom }}
                            >
                                {/* Format Group */}
                                <View className="relative">
                                    <TouchableOpacity onPress={() => { setShowFormatMenu(!showFormatMenu); setShowAttachMenu(false); }} className="p-2 bg-gray-200 dark:bg-gray-700 rounded-lg">
                                        <Type size={20} color={isDarkMode ? 'white' : 'black'} />
                                    </TouchableOpacity>
                                    {showFormatMenu && (
                                        <View className="absolute bottom-12 left-0 bg-white dark:bg-gray-800 rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 p-2 flex-row gap-2 min-w-[200px]">
                                            <TouchableOpacity
                                                onPress={() => handleFormat('bold')}
                                                className={`p-2 rounded ${activeFormats.includes('bold') ? 'bg-blue-200 dark:bg-blue-900 border-blue-500 border' : 'bg-gray-100 dark:bg-gray-700'}`}
                                            >
                                                <Bold size={18} color={activeFormats.includes('bold') ? (isDarkMode ? '#93C5FD' : '#1D4ED8') : (isDarkMode ? 'white' : 'black')} />
                                            </TouchableOpacity>
                                            <TouchableOpacity
                                                onPress={() => handleFormat('italic')}
                                                className={`p-2 rounded ${activeFormats.includes('italic') ? 'bg-blue-200 dark:bg-blue-900 border-blue-500 border' : 'bg-gray-100 dark:bg-gray-700'}`}
                                            >
                                                <Italic size={18} color={activeFormats.includes('italic') ? (isDarkMode ? '#93C5FD' : '#1D4ED8') : (isDarkMode ? 'white' : 'black')} />
                                            </TouchableOpacity>
                                            <TouchableOpacity
                                                onPress={() => handleFormat('list')}
                                                className={`p-2 rounded ${activeFormats.includes('list') ? 'bg-blue-200 dark:bg-blue-900 border-blue-500 border' : 'bg-gray-100 dark:bg-gray-700'}`}
                                            >
                                                <List size={18} color={activeFormats.includes('list') ? (isDarkMode ? '#93C5FD' : '#1D4ED8') : (isDarkMode ? 'white' : 'black')} />
                                            </TouchableOpacity>
                                            <TouchableOpacity
                                                onPress={() => handleFormat('link')}
                                                className={`p-2 rounded ${activeFormats.includes('link') ? 'bg-blue-200 dark:bg-blue-900 border-blue-500 border' : 'bg-gray-100 dark:bg-gray-700'}`}
                                            >
                                                <Link size={18} color={activeFormats.includes('link') ? (isDarkMode ? '#93C5FD' : '#1D4ED8') : (isDarkMode ? 'white' : 'black')} />
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('table')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Table size={18} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                        </View>
                                    )}
                                </View>

                                {/* Attach Group */}
                                <View className="relative">
                                    <TouchableOpacity onPress={() => { setShowAttachMenu(!showAttachMenu); setShowFormatMenu(false); }} className="p-2 bg-gray-200 dark:bg-gray-700 rounded-lg">
                                        <Plus size={20} color={isDarkMode ? 'white' : 'black'} />
                                    </TouchableOpacity>
                                    {showAttachMenu && (
                                        <View className="absolute bottom-12 right-0 bg-white dark:bg-gray-800 rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 p-2 flex-row gap-2">
                                            <TouchableOpacity onPress={handleImagePick} className="p-2 bg-gray-100 dark:bg-gray-700 rounded flex-row items-center justify-center w-10 h-10">
                                                <Camera size={20} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={handleAudioPick} className="p-2 bg-gray-100 dark:bg-gray-700 rounded flex-row items-center justify-center w-10 h-10">
                                                <Music size={20} color={isDarkMode ? 'white' : 'black'} />
                                            </TouchableOpacity>
                                        </View>
                                    )}
                                </View>
                            </View>
                        </View>
                    )
                )
                }

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
            </SafeAreaView >
        );
    }

    return (
        <GestureHandlerRootView style={{ flex: 1 }}>
            <SafeAreaView className="flex-1 bg-white dark:bg-gray-900" edges={['top', 'left', 'right']}>
                <View className="p-4 border-b border-gray-200 dark:border-gray-800">
                    <View className="flex-row justify-between items-center mb-4">
                        <Text className="text-2xl font-bold text-black dark:text-white">
                            {selectionMode ? `${selectedIds.size} Selected` : 'My Notes'}
                        </Text>
                        <View className="flex-row gap-4">
                            {!selectionMode && (
                                <>
                                    <TouchableOpacity onPress={() => onSync(false)} className="flex-row items-center bg-gray-100 dark:bg-gray-800 px-3 py-1.5 rounded-full">
                                        <RefreshCw size={14} color={isDarkMode ? '#60A5FA' : '#2563EB'} style={{ marginRight: 4 }} />
                                        <Text className="text-blue-600 dark:text-blue-400 font-medium">Sync</Text>
                                    </TouchableOpacity>
                                    <TouchableOpacity onPress={onExport} className="flex-row items-center bg-gray-100 dark:bg-gray-800 px-3 py-1.5 rounded-full">
                                        <Cloud size={14} color={isDarkMode ? '#A78BFA' : '#7C3AED'} style={{ marginRight: 4 }} />
                                        <Text className="text-purple-600 dark:text-purple-400 font-medium">Backup</Text>
                                    </TouchableOpacity>
                                    <TouchableOpacity onPress={onLogout} className="flex-row items-center bg-gray-100 dark:bg-gray-800 px-3 py-1.5 rounded-full">
                                        <Lock size={14} color={isDarkMode ? '#F87171' : '#EF4444'} style={{ marginRight: 4 }} />
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
                        <View className="bg-gray-100 dark:bg-gray-800 rounded-xl px-3 py-2 flex-row items-center">
                            <Search size={20} color={isDarkMode ? '#9CA3AF' : '#6B7280'} style={{ marginRight: 8 }} />
                            <TextInput
                                className="flex-1 text-black dark:text-white text-base py-1"
                                placeholder="Search notes..."
                                placeholderTextColor="#9CA3AF"
                                value={searchQuery}
                                onChangeText={setSearchQuery}
                            />
                            {searchQuery.length > 0 && (
                                <TouchableOpacity onPress={() => setSearchQuery('')} className="p-1">
                                    <X size={18} color={isDarkMode ? '#9CA3AF' : '#6B7280'} />
                                </TouchableOpacity>
                            )}
                        </View>
                    )}
                </View>

                <View className="flex-1">
                    <DraggableFlatList
                        data={filteredNotes}
                        keyExtractor={item => item.id}
                        contentContainerStyle={{ padding: 16, paddingBottom: 150 + insets.bottom }}
                        ItemSeparatorComponent={() => <View className="h-3" />}
                        onDragEnd={async ({ data }) => {
                            const baseTime = Date.now();
                            const updatedData = [...data];
                            const dbUpdates: Promise<any>[] = [];

                            let pinnedCount = 0;
                            updatedData.forEach((note, index) => {
                                if (note.isPinned && note.isPinned > 0) {
                                    const newPinnedTime = baseTime - (pinnedCount * 1000);
                                    // Create new object to ensure React detects change if needed, 
                                    // though DraggableFlatList's data prop change should be enough.
                                    // Crucially, we update the isPinned value to maintain the sort order logic.
                                    updatedData[index] = { ...note, isPinned: newPinnedTime };
                                    dbUpdates.push(runCommand('UPDATE notes SET isPinned = ? WHERE id = ?', [newPinnedTime, note.id]));
                                    pinnedCount++;
                                }
                            });

                            setNotes(updatedData);

                            try {
                                await Promise.all(dbUpdates);
                                onSync(true);
                            } catch (e) {
                                console.error('Failed to save pinned order:', e);
                            }
                        }}
                        renderItem={({ item, drag, isActive }: RenderItemParams<Note>) => {
                            const isSelected = selectedIds.has(item.id);
                            return (
                                <ScaleDecorator>
                                    <TouchableOpacity
                                        className={`p-4 rounded-xl border ${isSelected ? 'bg-blue-100 dark:bg-blue-900/30 border-blue-500' : 'bg-white dark:bg-gray-800 border-gray-200 dark:border-gray-700'} active:bg-gray-100 dark:active:bg-gray-700 shadow-sm ${isActive ? 'opacity-70' : ''}`}
                                        onPress={() => handlePress(item)}
                                        onLongPress={() => {
                                            handleLongPress(item.id);
                                        }}
                                        delayLongPress={200}
                                        disabled={isActive}
                                    >
                                        <View className="flex-row justify-between items-start">
                                            {(() => {
                                                if (item.type === 'drawing') {
                                                    // For drawing notes, the content is the base64 image
                                                    return (
                                                        <Image
                                                            source={{ uri: item.content }}
                                                            style={{ width: 60, height: 60, borderRadius: 8, marginRight: 10, backgroundColor: '#f0f0f0' }}
                                                            resizeMode="contain"
                                                        />
                                                    );
                                                }
                                                const img = getFirstImage(item.content);
                                                return img ? (
                                                    <Image
                                                        source={{ uri: img }}
                                                        style={{ width: 60, height: 60, borderRadius: 8, marginRight: 10 }}
                                                    />
                                                ) : null;
                                            })()}
                                            <View className="flex-1">
                                                <Text className="text-black dark:text-white font-bold text-lg mb-1" numberOfLines={1}>
                                                    {item.isPinned ? <Pin size={16} color={isDarkMode ? '#FBBF24' : '#B45309'} style={{ marginRight: 4 }} /> : ''}{item.title || 'Untitled'}
                                                </Text>
                                                <Text className="text-gray-600 dark:text-gray-400 text-base leading-5" numberOfLines={3}>
                                                    {item.type === 'drawing' ? 'Drawing' : (stripHtml(item.content) || 'No content')}
                                                </Text>
                                            </View>
                                            {selectionMode ? (
                                                <View className={`w-6 h-6 rounded-full border-2 ml-3 justify-center items-center ${isSelected ? 'bg-blue-500 border-blue-500' : 'border-gray-400 dark:border-gray-500'}`}>
                                                    {isSelected && <Check size={14} color="white" />}
                                                </View>
                                            ) : (
                                                item.isPinned ? (
                                                    <TouchableOpacity
                                                        onPressIn={drag}
                                                        className="ml-2 p-2 justify-center items-center"
                                                        hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
                                                    >
                                                        <GripVertical size={20} color={isDarkMode ? '#6B7280' : '#9CA3AF'} />
                                                    </TouchableOpacity>
                                                ) : null
                                            )}
                                        </View>
                                        <Text className="text-gray-500 dark:text-gray-600 text-xs mt-3 text-right">
                                            {new Date(item.updatedAt).toLocaleDateString()} • {new Date(item.updatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                                        </Text>
                                    </TouchableOpacity>
                                </ScaleDecorator>
                            );
                        }}
                        ListEmptyComponent={() => (
                            <View className="flex-1 justify-center items-center mt-20">
                                <Text className="text-gray-500 dark:text-gray-600 text-lg">No notes found</Text>
                            </View>
                        )}
                    />
                </View>

                {!selectionMode && (
                    <View className="absolute right-6" style={{ bottom: 24 + insets.bottom }}>
                        {showFabMenu && (
                            <View className="mb-4 gap-3 items-end">
                                <TouchableOpacity
                                    onPress={() => { startCreate('drawing'); setShowFabMenu(false); }}
                                    className="flex-row items-center bg-white dark:bg-gray-800 px-4 py-2 rounded-full shadow-lg border border-gray-200 dark:border-gray-700"
                                >
                                    <Text className="text-black dark:text-white font-medium mr-2">Drawing</Text>
                                    <Pencil size={18} color={isDarkMode ? 'white' : 'black'} />
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={() => { startCreate('text'); setShowFabMenu(false); }}
                                    className="flex-row items-center bg-white dark:bg-gray-800 px-4 py-2 rounded-full shadow-lg border border-gray-200 dark:border-gray-700"
                                >
                                    <Text className="text-black dark:text-white font-medium mr-2">Text Note</Text>
                                    <FileText size={18} color={isDarkMode ? 'white' : 'black'} />
                                </TouchableOpacity>
                            </View>
                        )}
                        <TouchableOpacity
                            className={`bg-blue-600 w-16 h-16 rounded-full justify-center items-center shadow-lg border border-blue-400 ${showFabMenu ? 'rotate-45' : ''}`}
                            onPress={() => setShowFabMenu(!showFabMenu)}
                        >
                            <Plus size={32} color="white" style={{ transform: [{ rotate: showFabMenu ? '45deg' : '0deg' }] }} />
                        </TouchableOpacity>
                    </View>
                )}

                {selectionMode && (
                    <View
                        className="absolute bottom-0 left-0 right-0 bg-white dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 p-4 flex-row justify-around"
                        style={{ paddingBottom: 16 + insets.bottom }}
                    >
                        <TouchableOpacity onPress={() => handleExport('text')} className="items-center">
                            <FileText size={24} color={isDarkMode ? '#D1D5DB' : '#4B5563'} />
                            <Text className="text-gray-600 dark:text-gray-300 text-xs mt-1">Text</Text>
                        </TouchableOpacity>
                        <TouchableOpacity onPress={() => handleExport('csv')} className="items-center">
                            <FileSpreadsheet size={24} color={isDarkMode ? '#D1D5DB' : '#4B5563'} />
                            <Text className="text-gray-600 dark:text-gray-300 text-xs mt-1">CSV</Text>
                        </TouchableOpacity>
                        <TouchableOpacity onPress={() => handleExport('pdf')} className="items-center">
                            <FilePdf size={24} color={isDarkMode ? '#D1D5DB' : '#4B5563'} />
                            <Text className="text-gray-600 dark:text-gray-300 text-xs mt-1">PDF</Text>
                        </TouchableOpacity>
                        <TouchableOpacity onPress={handleBulkDelete} className="items-center">
                            <Trash2 size={24} color="#EF4444" />
                            <Text className="text-red-600 dark:text-red-400 text-xs mt-1">Delete</Text>
                        </TouchableOpacity>
                        <TouchableOpacity onPress={handleBulkPin} className="items-center">
                            <Pin size={24} color={isDarkMode ? '#D1D5DB' : '#4B5563'} />
                            <Text className="text-gray-600 dark:text-gray-300 text-xs mt-1">
                                {notes.filter(n => selectedIds.has(n.id)).every(n => n.isPinned && n.isPinned > 0) ? 'Unpin' : 'Pin'}
                            </Text>
                        </TouchableOpacity>
                    </View>
                )}
            </SafeAreaView>
        </GestureHandlerRootView>
    );
};
