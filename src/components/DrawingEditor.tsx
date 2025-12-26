
import React, { useRef, useState, useMemo, useEffect, forwardRef, useImperativeHandle } from 'react';
import { View, TouchableOpacity, Text, Modal, StyleSheet } from 'react-native';
import SignatureScreen, { SignatureViewRef } from 'react-native-signature-canvas';
import ColorPicker, { Panel1, Swatches, Preview, HueSlider } from 'reanimated-color-picker';
import Slider from '@react-native-community/slider';
import { runOnJS } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Eraser, X, Palette, Brush, Check } from 'lucide-react-native';

interface DrawingEditorProps {
    initialContent?: string;
    onSave: (content: string) => void;
    onEmpty?: () => void;
    isDarkMode?: boolean;
}

export interface DrawingEditorRef {
    save: () => void;
    clear: () => void;
}

export const DrawingEditor = forwardRef<DrawingEditorRef, DrawingEditorProps>(({
    initialContent = '',
    onSave,
    onEmpty,
    isDarkMode = false
}, ref) => {
    // Refs
    const signatureRef = useRef<SignatureViewRef>(null);
    const insets = useSafeAreaInsets();

    // State
    const [penColor, setPenColor] = useState('black');
    const [brushSize, setBrushSize] = useState(3);
    const [eraserSize, setEraserSize] = useState(10);
    const [isEraser, setIsEraser] = useState(false);

    // UI State
    const [isColorPickerVisible, setIsColorPickerVisible] = useState(false);
    const [isSizeSliderVisible, setIsSizeSliderVisible] = useState(false);
    const [tempSize, setTempSize] = useState(3);

    // Expose methods to parent
    useImperativeHandle(ref, () => ({
        save: () => {
            signatureRef.current?.readSignature();
        },
        clear: () => {
            signatureRef.current?.clearSignature();
            // We might want to clear the 'content' but for now just clear UI
        }
    }));

    // Initialize
    useEffect(() => {
        // We can optionally set initial settings here if needed, 
        // but SignatureScreen usually takes defaults.
        // However, we want to ensure we start in 'pen' mode with correct color.
        if (signatureRef.current) {
            // Give a small delay for WebView to be ready
            setTimeout(() => {
                signatureRef.current?.changePenColor(penColor);
                signatureRef.current?.changePenSize(brushSize, brushSize);
            }, 500);
        }
    }, []);

    // Handlers
    const handleSignatureOK = (signature: string) => {
        onSave(signature);
    };

    const handleSignatureEmpty = () => {
        if (onEmpty) {
            onEmpty();
        }
    };

    const handleColorChange = (hex: string) => {
        setPenColor(hex);
        // If we were using eraser, switch back to brush
        if (isEraser) {
            setIsEraser(false);
            signatureRef.current?.changePenColor(hex);
            signatureRef.current?.changePenSize(brushSize, brushSize);
        } else {
            signatureRef.current?.changePenColor(hex);
        }
    };

    const handleToolToggle = (tool: 'brush' | 'eraser') => {
        if (tool === 'brush') {
            setIsEraser(false);
            signatureRef.current?.changePenColor(penColor);
            signatureRef.current?.changePenSize(brushSize, brushSize);
        } else {
            setIsEraser(true);
            const eraserColor = '#ffffff'; // Always white to match forced white canvas
            signatureRef.current?.changePenColor(eraserColor);
            signatureRef.current?.changePenSize(eraserSize, eraserSize);
        }
    };

    const handleSizeConfirm = () => {
        if (isEraser) {
            setEraserSize(tempSize);
            signatureRef.current?.changePenSize(tempSize, tempSize);
        } else {
            setBrushSize(tempSize);
            // Only apply if currently brush
            if (!isEraser) {
                signatureRef.current?.changePenSize(tempSize, tempSize);
            }
        }
        setIsSizeSliderVisible(false);
    };

    const webStyle = useMemo(() => `
        .m-signature-pad { box-shadow: none; border: none; } 
        .m-signature-pad--body { border: none; }
        .m-signature-pad--footer { display: none; margin: 0px; }
        body,html { width: 100%; height: 100%; }
        body { background-color: #ffffff; }
    `, []);

    return (
        <View className="flex-1 bg-white dark:bg-gray-900">
            {/* Canvas */}
            <View className="flex-1">
                <SignatureScreen
                    ref={signatureRef}
                    onOK={handleSignatureOK}
                    onEmpty={handleSignatureEmpty}
                    onEnd={() => signatureRef.current?.readSignature()}
                    dataURL={initialContent}
                    webStyle={webStyle}
                    backgroundColor="#ffffff"
                    descriptionText="Draw"
                    clearText="Clear"
                    confirmText="Save"
                />
            </View>

            {/* Toolbar */}
            <View
                className="flex-row justify-between items-center p-4 border-t border-gray-200 dark:border-gray-800 bg-gray-50 dark:bg-gray-900"
                style={{ paddingBottom: Math.max(insets.bottom, 16) }}
            >
                {/* Color Button (Brush) */}
                <TouchableOpacity
                    onPress={() => {
                        handleToolToggle('brush');
                        setIsColorPickerVisible(true);
                    }}
                    className={`w-10 h-10 rounded-full shadow-sm justify-center items-center ${!isEraser ? 'border-4 border-blue-500 dark:border-blue-400' : 'border-2 border-gray-300 dark:border-gray-600'}`}
                    style={{ backgroundColor: isEraser ? (isDarkMode ? '#111827' : 'white') : penColor }}
                >
                    {!isEraser ? (
                        <Palette size={20} color={isDarkMode ? 'white' : 'black'} style={{ opacity: 0.5 }} />
                    ) : (
                        <Palette size={20} color={isDarkMode ? '#6B7280' : '#9CA3AF'} />
                    )}
                </TouchableOpacity>

                {/* Size Button */}
                <TouchableOpacity
                    onPress={() => {
                        setTempSize(isEraser ? eraserSize : brushSize);
                        setIsSizeSliderVisible(true);
                    }}
                    className="bg-gray-200 dark:bg-gray-700 px-4 py-2 rounded-lg flex-row items-center gap-2"
                >
                    <View
                        className="bg-black dark:bg-white rounded-full"
                        style={{ width: 10, height: 10 }}
                    />
                    <Text className="text-black dark:text-white font-semibold">
                        {isEraser ? `Eraser: ${eraserSize}px` : `Size: ${brushSize}px`}
                    </Text>
                </TouchableOpacity>

                {/* Eraser Button */}
                <TouchableOpacity
                    onPress={() => {
                        if (isEraser) {
                            handleToolToggle('brush'); // Initial logic was toggle back
                        } else {
                            handleToolToggle('eraser');
                        }
                    }}
                    className={`p-2 rounded-lg ${isEraser ? 'bg-blue-100 dark:bg-blue-900 border-2 border-blue-500 dark:border-blue-400' : 'bg-gray-200 dark:bg-gray-700 border-2 border-transparent'}`}
                >
                    <Eraser size={24} color={isEraser ? (isDarkMode ? '#60A5FA' : '#2563EB') : (isDarkMode ? '#9CA3AF' : '#6B7280')} />
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
                        <TouchableOpacity
                            onPress={() => setIsColorPickerVisible(false)}
                            className="absolute top-3 right-3 w-8 h-8 bg-gray-200 dark:bg-gray-700 rounded-full items-center justify-center z-10"
                        >
                            <X size={20} color={isDarkMode ? 'white' : 'black'} />
                        </TouchableOpacity>

                        <Text className="text-xl font-bold text-black dark:text-white mb-4 text-center">Select Color</Text>

                        <View className="w-full mb-4" style={{ height: 340 }}>
                            <ColorPicker
                                style={{ width: '100%', height: '100%' }}
                                value={penColor}
                                thumbSize={24}
                                onChange={({ hex }) => {
                                    runOnJS(handleColorChange)(hex);
                                }}
                            >
                                <Preview style={{ marginBottom: 15 }} />
                                <Panel1 style={{ marginBottom: 15 }} />
                                <HueSlider style={{ marginBottom: 15 }} />
                                <Swatches colors={['#000000', '#ffffff', '#ff0000', '#0000ff', '#ffff00', '#00ff00']} />
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
                        <Text className="text-xl font-bold text-black dark:text-white mb-6">
                            {isEraser ? 'Eraser Size' : 'Brush Size'}
                        </Text>

                        {/* Preview */}
                        <View className="w-32 h-32 bg-gray-100 dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 justify-center items-center mb-6 overflow-hidden">
                            <View
                                className="rounded-full"
                                style={{
                                    width: tempSize,
                                    height: tempSize,
                                    backgroundColor: isEraser ? 'white' : penColor,
                                    borderWidth: isEraser ? 1 : 0,
                                    borderColor: '#ccc'
                                }}
                            />
                        </View>

                        <Slider
                            style={{ width: '100%', height: 40 }}
                            minimumValue={1}
                            maximumValue={50}
                            step={1}
                            value={tempSize}
                            onValueChange={setTempSize}
                            minimumTrackTintColor="#2563EB"
                            maximumTrackTintColor="#9CA3AF"
                            thumbTintColor="#2563EB"
                        />
                        <Text className="text-gray-500 dark:text-gray-400 mt-2 mb-6">{tempSize}px</Text>

                        <View className="flex-row gap-3 w-full">
                            <TouchableOpacity
                                onPress={() => setIsSizeSliderVisible(false)}
                                className="flex-1 bg-gray-200 dark:bg-gray-700 p-3 rounded-xl items-center"
                            >
                                <Text className="text-black dark:text-white font-semibold">Cancel</Text>
                            </TouchableOpacity>
                            <TouchableOpacity
                                onPress={handleSizeConfirm}
                                className="flex-1 bg-blue-600 p-3 rounded-xl items-center"
                            >
                                <Text className="text-white font-semibold">Set Size</Text>
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>
        </View>
    );
});
