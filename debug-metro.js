try {
    console.log("Requiring metro.config.js...");
    const config = require("./metro.config.js");
    console.log("Success. Config loaded:", typeof config);
} catch (e) {
    console.error("Failed to require metro.config.js:", e);
}
