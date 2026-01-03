import React from 'react';
import { View, Text, TouchableOpacity, FlatList, Modal } from 'react-native';

interface Props {
    visible: boolean;
    files: { name: string; uri: string }[];
    onSelect: (uri: string) => void;
    onCancel: () => void;
}

export const FilePicker: React.FC<Props> = ({ visible, files, onSelect, onCancel }) => {
    return (
        <Modal visible={visible} animationType="slide" transparent>
            <View className="flex-1 justify-end bg-black/50">
                <View className="bg-gray-900 rounded-t-3xl p-6 h-2/3">
                    <Text className="text-xl font-bold text-white mb-4">Select Database File</Text>

                    <FlatList
                        data={files}
                        keyExtractor={item => item.uri}
                        renderItem={({ item }) => (
                            <TouchableOpacity
                                className="p-4 border-b border-gray-800 active:bg-gray-800"
                                onPress={() => onSelect(item.uri)}
                            >
                                <Text className="text-white text-lg">{item.name}</Text>
                            </TouchableOpacity>
                        )}
                        ListEmptyComponent={
                            <Text className="text-gray-500 text-center mt-10">No files found in this folder.</Text>
                        }
                    />

                    <TouchableOpacity
                        className="mt-4 bg-gray-700 p-4 rounded-xl"
                        onPress={onCancel}
                    >
                        <Text className="text-white text-center font-semibold">Cancel</Text>
                    </TouchableOpacity>
                </View>
            </View>
        </Modal>
    );
};
