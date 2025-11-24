import React, { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, Keyboard } from 'react-native';

interface Props {
    onSubmit: (name: string) => void;
    onCancel: () => void;
}

export const CreateDbScreen: React.FC<Props> = ({ onSubmit, onCancel }) => {
    const [dbName, setDbName] = useState('secure_notes');
    const inputRef = React.useRef<TextInput>(null);

    const handleSubmit = () => {
        Keyboard.dismiss();
        if (dbName.trim()) {
            onSubmit(dbName.trim());
        }
    };

    return (
        <View className="flex-1 justify-center items-center bg-gray-900 p-6">
            <Text className="text-2xl font-bold text-white mb-2">New Database</Text>
            <Text className="text-gray-400 mb-8 text-center">
                Choose a name for your encrypted database file.
            </Text>

            <TextInput
                ref={inputRef}
                className="w-full bg-gray-800 text-white p-4 rounded-xl mb-6 border border-gray-700"
                placeholder="Database Name (e.g., my_notes)"
                placeholderTextColor="#9CA3AF"
                value={dbName}
                onChangeText={setDbName}
                autoCapitalize="none"
                autoFocus={true}
                onSubmitEditing={handleSubmit}
                returnKeyType="next"
            />

            <TouchableOpacity
                className="w-full bg-emerald-600 p-4 rounded-xl mb-4 active:bg-emerald-700"
                onPress={handleSubmit}
                disabled={!dbName.trim()}
            >
                <Text className="text-white text-center font-semibold text-lg">
                    Continue
                </Text>
            </TouchableOpacity>

            <TouchableOpacity
                className="w-full p-4"
                onPress={onCancel}
            >
                <Text className="text-gray-400 text-center">Cancel</Text>
            </TouchableOpacity>
        </View>
    );
};
