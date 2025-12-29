const fsLegacy = require('expo-file-system/legacy');
console.log('Legacy exports:', Object.keys(fsLegacy));
if (fsLegacy.documentDirectory) {
    console.log('documentDirectory found in legacy!');
} else {
    console.log('documentDirectory NOT found in legacy.');
}
