import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import { FolderOpen, PlusCircle } from 'lucide-react-native';

interface Props {
    onOpen: () => void;
    onCreate: () => void;
    loading: boolean;
    error: string;
}

export const WelcomeScreen: React.FC<Props> = ({ onOpen, onCreate, loading, error }) => {
    return (
        <View className="flex-1 justify-center items-center bg-gray-900 p-6">
            <Text className="text-4xl font-bold text-white mb-2">SecureNotes</Text>
            <Text className="text-gray-400 mb-12 text-center">
                Encrypted note-taking with direct cloud file access.
            </Text>

            <TouchableOpacity
                className="w-full bg-blue-600 p-4 rounded-xl mb-4 active:bg-blue-700 flex-row justify-center items-center"
                onPress={onOpen}
                disabled={loading}
            >
                <FolderOpen size={20} color="white" style={{ marginRight: 10 }} />
                <Text className="text-white text-center font-semibold text-lg">
                    Open Database
                </Text>
            </TouchableOpacity>

            <TouchableOpacity
                className="w-full bg-gray-700 p-4 rounded-xl active:bg-gray-600 flex-row justify-center items-center"
                onPress={onCreate}
                disabled={loading}
            >
                <PlusCircle size={20} color="white" style={{ marginRight: 10 }} />
                <Text className="text-white text-center font-semibold text-lg">
                    Create New Database
                </Text>
            </TouchableOpacity>

            {error ? (
                <Text className="text-red-500 mt-6 text-center">{error}</Text>
            ) : null}
        </View>
    );
};
