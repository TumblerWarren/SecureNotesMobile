import React, { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, Keyboard, ActivityIndicator, Modal } from 'react-native';
import { Lock, ShieldAlert } from 'lucide-react-native';

interface Props {
    onSubmit: (password: string) => void;
    isNew: boolean;
    loading: boolean;
    error: string;
    onCancel: () => void;
    onErrorDismiss: () => void;
}

export const UnlockScreen: React.FC<Props> = ({ onSubmit, isNew, loading, error, onCancel, onErrorDismiss }) => {
    const [password, setPassword] = useState('');

    const inputRef = React.useRef<TextInput>(null);

    const handleSubmit = () => {
        Keyboard.dismiss();
        inputRef.current?.blur();
        // Small delay to allow keyboard to start dismissing before state changes
        setTimeout(() => {
            onSubmit(password);
        }, 100);
    };

    return (
        <View className="flex-1 justify-center items-center bg-gray-900 p-6">
            <Text className="text-2xl font-bold text-white mb-6">
                {isNew ? 'Set Master Password' : 'Unlock Database'}
            </Text>

            <TextInput
                ref={inputRef}
                className="w-full bg-gray-800 text-white p-4 rounded-xl mb-6 border border-gray-700"
                placeholder="Enter Master Password"
                placeholderTextColor="#9CA3AF"
                secureTextEntry
                value={password}
                onChangeText={setPassword}
                autoCapitalize="none"
                editable={!loading}
                onSubmitEditing={handleSubmit}
                returnKeyType="done"
            />

            <TouchableOpacity
                className={`w-full bg-blue-600 p-4 rounded-xl mb-4 ${loading ? 'opacity-50' : 'active:bg-blue-700'}`}
                onPress={handleSubmit}
                disabled={loading || !password}
            >
                {loading ? (
                    <View className="flex-row justify-center items-center">
                        <ActivityIndicator color="white" className="mr-2" />
                        <Text className="text-white text-center font-semibold text-lg ml-2">
                            Processing...
                        </Text>
                    </View>
                ) : (
                    <Text className="text-white text-center font-semibold text-lg">
                        {isNew ? 'Create Database' : 'Unlock'}
                    </Text>
                )}
            </TouchableOpacity>

            <TouchableOpacity
                className="w-full p-4"
                onPress={onCancel}
                disabled={loading}
            >
                <Text className="text-gray-400 text-center">Cancel</Text>
            </TouchableOpacity>

            <Modal
                visible={!!error}
                transparent={true}
                animationType="fade"
                onRequestClose={onErrorDismiss}
            >
                <View className="flex-1 justify-center items-center bg-black/80 p-6">
                    <View className="w-full bg-gray-800 rounded-2xl p-6 items-center border border-red-900/50">
                        <View className="w-16 h-16 rounded-full bg-red-900/30 justify-center items-center mb-4">
                            <ShieldAlert size={32} color="#EF4444" />
                        </View>

                        <Text className="text-xl font-bold text-white mb-2 text-center">
                            Access Denied
                        </Text>

                        <Text className="text-gray-400 text-center mb-6">
                            {error}
                        </Text>

                        <TouchableOpacity
                            className="w-full bg-red-600 p-4 rounded-xl active:bg-red-700"
                            onPress={() => {
                                setPassword('');
                                onErrorDismiss();
                                // Focus input again after a short delay
                                setTimeout(() => inputRef.current?.focus(), 100);
                            }}
                        >
                            <Text className="text-white text-center font-semibold text-lg">
                                Try Again
                            </Text>
                        </TouchableOpacity>
                    </View>
                </View>
            </Modal>
        </View>
    );
};
