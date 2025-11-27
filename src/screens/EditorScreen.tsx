import React, { useState, useEffect, useMemo, useRef } from 'react';
import { View, Text, TouchableOpacity, FlatList, TextInput, Alert, BackHandler, Modal, KeyboardAvoidingView, Platform, useColorScheme, Keyboard, Image } from 'react-native';
import DraggableFlatList, { ScaleDecorator, RenderItemParams } from 'react-native-draggable-flatlist';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { runQuery, runCommand, deleteNote } from '../lib/db';
import * as Sharing from 'expo-sharing';
import * as Print from 'expo-print';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system';
import { Audio } from 'expo-av';
import { RichEditor, RichEditorRef } from '../components/RichEditor';
import SignatureScreen, { SignatureViewRef } from 'react-native-signature-canvas';
import { runOnJS } from 'react-native-reanimated';
import Slider from '@react-native-community/slider';
import ColorPicker, { Panel1, Swatches, Preview, OpacitySlider, HueSlider } from 'reanimated-color-picker';

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

    // Voice Recording State
    const [recording, setRecording] = useState<Audio.Recording | null>(null);
    const [isRecording, setIsRecording] = useState(false);

    // Drawing Modal State (for Text notes - unused now but kept for reference if needed, though logic removed)
    // const [isDrawingModalVisible, setIsDrawingModalVisible] = useState(false);
    const signatureRef = useRef<SignatureViewRef>(null);

    // Toolbar State
    const [showFormatMenu, setShowFormatMenu] = useState(false);
    const [showAttachMenu, setShowAttachMenu] = useState(false);

    // FAB State
    const [showFabMenu, setShowFabMenu] = useState(false);

    // Drawing State
    const [initialDrawing, setInitialDrawing] = useState('');
    const [penColor, setPenColor] = useState('black');
    const [penWidth, setPenWidth] = useState(3);
    const [isEraser, setIsEraser] = useState(false);

    // Advanced Drawing Tools State
    const [isColorPickerVisible, setIsColorPickerVisible] = useState(false);
    const [isSizeSliderVisible, setIsSizeSliderVisible] = useState(false);
    const [tempPenWidth, setTempPenWidth] = useState(3);

    useEffect(() => {
        if (selectedNote?.type === 'drawing') {
            setInitialDrawing(selectedNote.content);
        }
    }, [selectedNote?.id]);

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

    const startCreate = (type: 'text' | 'drawing' = 'text') => {
        const newId = Math.random().toString(36).substring(7);
        const newNote: Note = { id: newId, title: '', content: '', updatedAt: Date.now(), type };
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

    const handleBulkPin = async () => {
        const now = Date.now();

        // Smart Pin Logic:
        // 1. Check if all selected notes are already pinned
        const selectedNotes = notes.filter(n => selectedIds.has(n.id));
        const allPinned = selectedNotes.every(n => n.isPinned && n.isPinned > 0);

        if (allPinned) {
            // Unpin all
            for (const id of selectedIds) {
                await runCommand('UPDATE notes SET isPinned = 0 WHERE id = ?', [id]);
            }
        } else {
            // Pin all
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

    const handleImagePick = async () => {
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
            Alert.alert("Error", "Image pick failed: " + String(e));
        }
    };

    const handleVoiceRecord = async () => {
        if (recording) {
            // Stop recording
            setIsRecording(false);
            try {
                await recording.stopAndUnloadAsync();
                const uri = recording.getURI();
                setRecording(null);

                if (uri) {
                    const base64 = await FileSystem.readAsStringAsync(uri, { encoding: 'base64' });
                    const audioHtml = `<br/><audio controls src="data:audio/m4a;base64,${base64}"></audio><br/>`;
                    editorRef.current?.insertHtml(audioHtml);
                }
            } catch (e) {
                Alert.alert("Error", "Failed to save voice note: " + String(e));
            }
        } else {
            // Start recording
            try {
                const permission = await Audio.requestPermissionsAsync();
                if (permission.status === 'granted') {
                    await Audio.setAudioModeAsync({
                        allowsRecordingIOS: true,
                        playsInSilentModeIOS: true,
                    });
                    const { recording } = await Audio.Recording.createAsync(
                        Audio.RecordingOptionsPresets.HIGH_QUALITY
                    );
                    setRecording(recording);
                    setIsRecording(true);
                } else {
                    Alert.alert("Permission Required", "Microphone permission is required to record voice notes.");
                }
            } catch (err) {
                console.error('Failed to start recording', err);
                Alert.alert("Error", "Failed to start recording: " + String(err));
            }
        }
    };

    const handleSignatureOK = (signature: string) => {
        // signature is a base64 string with data URI prefix
        const imageHtml = `<img src="${signature}" style="max-width: 100%; height: auto; border-radius: 8px; margin: 10px 0;" /><br/>`;
        editorRef.current?.insertHtml(imageHtml);
        // setIsDrawingModalVisible(false);
    };

    const handleSignatureEmpty = () => {
        Alert.alert("Empty Drawing", "Please draw something before saving.");
    };

    const handleSignatureClear = () => {
        signatureRef.current?.clearSignature();
        handleDrawingSave(''); // Save empty state immediately
    };

    const handleSignatureEnd = () => {
        signatureRef.current?.readSignature();
    };

    const handleColorChange = (color: string) => {
        setPenColor(color);
        setIsEraser(false);
        signatureRef.current?.changePenColor(color);
    };

    const handleWidthChange = (width: number) => {
        setPenWidth(width);
        signatureRef.current?.changePenSize(width, width);
    };

    const handleWidthConfirm = () => {
        handleWidthChange(tempPenWidth);
        setIsSizeSliderVisible(false);
    };

    const handleEraser = () => {
        setIsEraser(true);
        // Assuming white background for eraser
        signatureRef.current?.changePenColor('#ffffff');
        signatureRef.current?.changePenSize(20, 20); // Eraser is usually thicker
    };

    // Drawing Note Specific Handlers

    // Drawing Note Specific Handlers
    const handleDrawingSave = (signature: string) => {
        // For drawing notes, the content IS the signature (base64)
        setContent(signature);
        // Trigger save immediately
        if (selectedNote) {
            const now = Date.now();
            const isPinned = selectedNote.isPinned || 0;
            const type = 'drawing';
            runCommand('INSERT OR REPLACE INTO notes (id, title, content, updatedAt, isPinned, type) VALUES (?, ?, ?, ?, ?, ?)', [selectedNote.id, title, signature, now, isPinned, type])
                .then(() => {
                    const updatedNote: Note = { ...selectedNote, title, content: signature, updatedAt: now, isPinned, type };
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
    };

    if (selectedNote) {
        return (
            <SafeAreaView className="flex-1 bg-white dark:bg-gray-900" edges={['top', 'left', 'right']}>
                <View className="p-4 flex-row justify-between items-center border-b border-gray-200 dark:border-gray-800">
                    <TouchableOpacity onPress={handleBack} className="flex-row items-center">
                        <Text className="text-blue-600 dark:text-blue-400 text-lg mr-1">‹</Text>
                        <Text className="text-blue-600 dark:text-blue-400 text-lg">Back</Text>
                    </TouchableOpacity >

                    <View className="flex-row items-center gap-4">
                        {isSaving && (
                            <Text className="text-gray-500 text-xs">Saving...</Text>
                        )}
                        {selectedNote.type === 'drawing' && (
                            <TouchableOpacity onPress={handleSignatureClear} className="bg-gray-100 dark:bg-gray-800 px-3 py-1 rounded-lg border border-gray-200 dark:border-gray-700">
                                <Text className="text-gray-600 dark:text-gray-400 font-semibold">Clear</Text>
                            </TouchableOpacity>
                        )}
                        <TouchableOpacity onPress={handleDelete} className="bg-red-100 dark:bg-red-900/50 px-3 py-1 rounded-lg border border-red-200 dark:border-red-800">
                            <Text className="text-red-600 dark:text-red-400 font-semibold">Delete</Text>
                        </TouchableOpacity>
                        <TouchableOpacity onPress={togglePin} className={`px-3 py-1 rounded-lg border ${selectedNote.isPinned ? 'bg-yellow-100 dark:bg-yellow-900/50 border-yellow-200 dark:border-yellow-800' : 'bg-gray-100 dark:bg-gray-800 border-gray-200 dark:border-gray-700'}`}>
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
                            <SignatureScreen
                                ref={signatureRef}
                                onOK={handleDrawingSave}
                                onEmpty={handleSignatureEmpty}
                                onEnd={handleSignatureEnd}
                                dataURL={initialDrawing}
                                descriptionText="Draw"
                                clearText="Clear"
                                confirmText="Save"
                                penColor={penColor}
                                minWidth={penWidth}
                                maxWidth={penWidth}
                                webStyle={`
                                    .m-signature-pad { box-shadow: none; border: none; } 
                                    .m-signature-pad--body { border: none; }
                                    .m-signature-pad--footer { display: none; margin: 0px; }
                                    body,html { width: 100%; height: 100%; }
                                `}
                            />
                        </View>

                        {/* Drawing Toolbar */}
                        <View className="flex-row justify-between items-center p-4 border-t border-gray-200 dark:border-gray-800 bg-gray-50 dark:bg-gray-900 safe-area-pb">
                            {/* Color Button */}
                            <TouchableOpacity
                                onPress={() => setIsColorPickerVisible(true)}
                                className="w-10 h-10 rounded-full border-2 border-gray-300 dark:border-gray-600 shadow-sm"
                                style={{ backgroundColor: isEraser ? 'white' : penColor }}
                            />

                            {/* Width Button */}
                            <TouchableOpacity
                                onPress={() => {
                                    setTempPenWidth(penWidth);
                                    setIsSizeSliderVisible(true);
                                }}
                                className="bg-gray-200 dark:bg-gray-700 px-4 py-2 rounded-lg flex-row items-center gap-2"
                            >
                                <View
                                    className="bg-black dark:bg-white rounded-full"
                                    style={{ width: penWidth, height: penWidth }}
                                />
                                <Text className="text-black dark:text-white font-semibold">Size</Text>
                            </TouchableOpacity>

                            {/* Eraser */}
                            <TouchableOpacity
                                onPress={handleEraser}
                                className={`p-2 rounded-lg ${isEraser ? 'bg-blue-100 dark:bg-blue-900 border border-blue-300 dark:border-blue-700' : 'bg-gray-200 dark:bg-gray-700'}`}
                            >
                                <Text className="text-xl">🧹</Text>
                            </TouchableOpacity>
                        </View>

                        {/* Color Picker Modal */}
                        <Modal
                            visible={isColorPickerVisible}
                            transparent={true}
                            animationType="fade"
                            onRequestClose={() => setIsColorPickerVisible(false)}
                        >
                            <View className="flex-1 bg-black/50 justify-center items-center p-4">
                                <View className="bg-white dark:bg-gray-800 p-6 rounded-2xl w-full max-w-sm shadow-xl relative">
                                    {/* Close Button - Top Right */}
                                    <TouchableOpacity
                                        onPress={() => setIsColorPickerVisible(false)}
                                        className="absolute top-3 right-3 w-8 h-8 bg-gray-200 dark:bg-gray-700 rounded-full items-center justify-center z-10"
                                        style={{ zIndex: 10 }}
                                    >
                                        <Text className="text-black dark:text-white text-lg font-bold">×</Text>
                                    </TouchableOpacity>

                                    <Text className="text-xl font-bold text-black dark:text-white mb-4 text-center pr-8">Select Color</Text>

                                    <View className="w-full mb-4" style={{ height: 340 }}>
                                        <ColorPicker
                                            style={{ width: '100%', height: '100%' }}
                                            value={penColor}
                                            thumbSize={24}
                                            onChange={({ hex }) => {
                                                'worklet';
                                                runOnJS(handleColorChange)(hex);
                                            }}
                                        >
                                            <Preview style={{ marginBottom: 15 }} />
                                            <Panel1 style={{ marginBottom: 15 }} />
                                            <HueSlider style={{ marginBottom: 15 }} />
                                            <Swatches
                                                colors={['#000000', '#ffffff', '#ff0000', '#0000ff', '#ffff00']}
                                            />
                                        </ColorPicker>
                                    </View>
                                </View>
                            </View>
                        </Modal>

                        {/* Size Slider Modal */}
                        <Modal
                            visible={isSizeSliderVisible}
                            transparent={true}
                            animationType="fade"
                            onRequestClose={() => setIsSizeSliderVisible(false)}
                        >
                            <View className="flex-1 bg-black/50 justify-center items-center p-4">
                                <View className="bg-white dark:bg-gray-800 p-6 rounded-2xl w-full max-w-sm shadow-xl items-center">
                                    <Text className="text-xl font-bold text-black dark:text-white mb-6">Brush Size</Text>

                                    {/* Preview */}
                                    <View className="w-32 h-32 bg-gray-100 dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 justify-center items-center mb-6 overflow-hidden">
                                        <View
                                            className="rounded-full"
                                            style={{
                                                width: tempPenWidth,
                                                height: tempPenWidth,
                                                backgroundColor: penColor
                                            }}
                                        />
                                    </View>

                                    <Slider
                                        style={{ width: '100%', height: 40 }}
                                        minimumValue={1}
                                        maximumValue={50}
                                        step={1}
                                        value={tempPenWidth}
                                        onValueChange={setTempPenWidth}
                                        minimumTrackTintColor="#2563EB"
                                        maximumTrackTintColor="#9CA3AF"
                                        thumbTintColor="#2563EB"
                                    />
                                    <Text className="text-gray-500 dark:text-gray-400 mt-2 mb-6">{tempPenWidth}px</Text>

                                    <View className="flex-row gap-3 w-full">
                                        <TouchableOpacity
                                            onPress={() => setIsSizeSliderVisible(false)}
                                            className="flex-1 bg-gray-200 dark:bg-gray-700 p-3 rounded-xl items-center"
                                        >
                                            <Text className="text-black dark:text-white font-semibold">Cancel</Text>
                                        </TouchableOpacity>
                                        <TouchableOpacity
                                            onPress={handleWidthConfirm}
                                            className="flex-1 bg-blue-600 p-3 rounded-xl items-center"
                                        >
                                            <Text className="text-white font-semibold">Set Size</Text>
                                        </TouchableOpacity>
                                    </View>
                                </View>
                            </View>
                        </Modal>
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
                                        <Text className="text-black dark:text-white text-lg">Aa</Text>
                                    </TouchableOpacity>
                                    {showFormatMenu && (
                                        <View className="absolute bottom-12 left-0 bg-white dark:bg-gray-800 rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 p-2 flex-row gap-2 min-w-[200px]">
                                            <TouchableOpacity onPress={() => handleFormat('bold')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white font-bold text-lg">B</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('italic')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white italic text-lg">I</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('list')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white text-lg">•</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('link')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white text-lg">🔗</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('table')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white text-lg">▦</Text>
                                            </TouchableOpacity>
                                        </View>
                                    )}
                                </View>

                                {/* Attach Group */}
                                <View className="relative">
                                    <TouchableOpacity onPress={() => { setShowAttachMenu(!showAttachMenu); setShowFormatMenu(false); }} className="p-2 bg-gray-200 dark:bg-gray-700 rounded-lg">
                                        <Text className="text-black dark:text-white text-lg">+</Text>
                                    </TouchableOpacity>
                                    {showAttachMenu && (
                                        <View className="absolute bottom-12 right-0 bg-white dark:bg-gray-800 rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 p-2 flex-row gap-2">
                                            <TouchableOpacity onPress={handleImagePick} className="p-2 bg-gray-100 dark:bg-gray-700 rounded flex-row items-center justify-center w-10 h-10">
                                                <Text className="text-black dark:text-white text-lg">📷</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={handleVoiceRecord} className="p-2 bg-gray-100 dark:bg-gray-700 rounded flex-row items-center justify-center w-10 h-10">
                                                <Text className="text-black dark:text-white text-lg">{recording ? '⏹️' : '🎤'}</Text>
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
                                        <Text className="text-black dark:text-white text-lg">Aa</Text>
                                    </TouchableOpacity>
                                    {showFormatMenu && (
                                        <View className="absolute bottom-12 left-0 bg-white dark:bg-gray-800 rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 p-2 flex-row gap-2 min-w-[200px]">
                                            <TouchableOpacity onPress={() => handleFormat('bold')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white font-bold text-lg">B</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('italic')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white italic text-lg">I</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('list')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white text-lg">•</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('link')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white text-lg">🔗</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={() => handleFormat('table')} className="p-2 bg-gray-100 dark:bg-gray-700 rounded">
                                                <Text className="text-black dark:text-white text-lg">▦</Text>
                                            </TouchableOpacity>
                                        </View>
                                    )}
                                </View>

                                {/* Attach Group */}
                                <View className="relative">
                                    <TouchableOpacity onPress={() => { setShowAttachMenu(!showAttachMenu); setShowFormatMenu(false); }} className="p-2 bg-gray-200 dark:bg-gray-700 rounded-lg">
                                        <Text className="text-black dark:text-white text-lg">+</Text>
                                    </TouchableOpacity>
                                    {showAttachMenu && (
                                        <View className="absolute bottom-12 right-0 bg-white dark:bg-gray-800 rounded-xl shadow-xl border border-gray-200 dark:border-gray-700 p-2 flex-row gap-2">
                                            <TouchableOpacity onPress={handleImagePick} className="p-2 bg-gray-100 dark:bg-gray-700 rounded flex-row items-center justify-center w-10 h-10">
                                                <Text className="text-black dark:text-white text-lg">📷</Text>
                                            </TouchableOpacity>
                                            <TouchableOpacity onPress={handleVoiceRecord} className="p-2 bg-gray-100 dark:bg-gray-700 rounded flex-row items-center justify-center w-10 h-10">
                                                <Text className="text-black dark:text-white text-lg">{recording ? '⏹️' : '🎤'}</Text>
                                            </TouchableOpacity>
                                        </View>
                                    )}
                                </View>
                            </View>
                        </View>
                    )
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

                <DraggableFlatList
                    data={filteredNotes}
                    keyExtractor={item => item.id}
                    contentContainerStyle={{ padding: 16, paddingBottom: 100 }}
                    ItemSeparatorComponent={() => <View className="h-3" />}
                    onDragEnd={async ({ data }) => {
                        setNotes(data);
                        const pinnedNotes = data.filter(n => n.isPinned && n.isPinned > 0);
                        const baseTime = Date.now();

                        for (let i = 0; i < pinnedNotes.length; i++) {
                            const note = pinnedNotes[i];
                            const newPinnedTime = baseTime - (i * 1000);
                            await runCommand('UPDATE notes SET isPinned = ? WHERE id = ?', [newPinnedTime, note.id]);
                            note.isPinned = newPinnedTime;
                        }
                        onSync(true);
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
                                                {item.isPinned ? '📌 ' : ''}{item.title || 'Untitled'}
                                            </Text>
                                            <Text className="text-gray-600 dark:text-gray-400 text-base leading-5" numberOfLines={3}>
                                                {item.type === 'drawing' ? 'Drawing' : (stripHtml(item.content) || 'No content')}
                                            </Text>
                                        </View>
                                        {selectionMode ? (
                                            <View className={`w-6 h-6 rounded-full border-2 ml-3 justify-center items-center ${isSelected ? 'bg-blue-500 border-blue-500' : 'border-gray-400 dark:border-gray-500'}`}>
                                                {isSelected && <Text className="text-white text-xs">✓</Text>}
                                            </View>
                                        ) : (
                                            item.isPinned ? (
                                                <TouchableOpacity
                                                    onPressIn={drag}
                                                    className="ml-2 p-2 justify-center items-center"
                                                    hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
                                                >
                                                    <Text className="text-gray-400 dark:text-gray-500 text-xl">≡</Text>
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

                {!selectionMode && (
                    <View className="absolute right-6" style={{ bottom: 24 + insets.bottom }}>
                        {showFabMenu && (
                            <View className="mb-4 gap-3 items-end">
                                <TouchableOpacity
                                    onPress={() => { startCreate('drawing'); setShowFabMenu(false); }}
                                    className="flex-row items-center bg-white dark:bg-gray-800 px-4 py-2 rounded-full shadow-lg border border-gray-200 dark:border-gray-700"
                                >
                                    <Text className="text-black dark:text-white font-medium mr-2">Drawing</Text>
                                    <Text className="text-xl">✏️</Text>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={() => { startCreate('text'); setShowFabMenu(false); }}
                                    className="flex-row items-center bg-white dark:bg-gray-800 px-4 py-2 rounded-full shadow-lg border border-gray-200 dark:border-gray-700"
                                >
                                    <Text className="text-black dark:text-white font-medium mr-2">Text Note</Text>
                                    <Text className="text-xl">📝</Text>
                                </TouchableOpacity>
                            </View>
                        )}
                        <TouchableOpacity
                            className={`bg-blue-600 w-16 h-16 rounded-full justify-center items-center shadow-lg border border-blue-400 ${showFabMenu ? 'rotate-45' : ''}`}
                            onPress={() => setShowFabMenu(!showFabMenu)}
                        >
                            <Text className="text-white text-4xl font-light pb-1" style={{ transform: [{ rotate: showFabMenu ? '45deg' : '0deg' }] }}>+</Text>
                        </TouchableOpacity>
                    </View>
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
                        <TouchableOpacity onPress={handleBulkPin} className="items-center">
                            <Text className="text-2xl mb-1">📌</Text>
                            <Text className="text-gray-600 dark:text-gray-300 text-xs">
                                {notes.filter(n => selectedIds.has(n.id)).every(n => n.isPinned && n.isPinned > 0) ? 'Unpin' : 'Pin'}
                            </Text>
                        </TouchableOpacity>
                    </View>
                )}
            </SafeAreaView>
        </GestureHandlerRootView>
    );
};
