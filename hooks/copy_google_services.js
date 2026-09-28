#!/usr/bin/env node

/**
 * WizzoPush - Hook script to copy Firebase configuration files
 * 
 * Copies:
 *   - GoogleService-Info.plist → iOS project (as resource-file in Xcode)
 *   - google-services.json → Android app directory
 *   - msal_auth_config.json → Android res/raw (Microsoft sign-in, optional)
 * 
 * Place these files in your Cordova project root:
 *   myapp/
 *     GoogleService-Info.plist   ← from Firebase Console (iOS)
 *     google-services.json      ← from Firebase Console (Android)
 *     msal_auth_config.json     ← from Azure (Android, only if you use authenticateUserWithMicrosoft)
 *     config.xml
 *     ...
 */

var fs = require('fs');
var path = require('path');

module.exports = function(context) {
    var projectRoot = context.opts.projectRoot;
    var platforms = context.opts.platforms || context.opts.cordova.platforms;
    
    // iOS: Copy GoogleService-Info.plist
    if (platforms.indexOf('ios') !== -1) {
        copyGoogleServiceiOS(projectRoot);
    }
    
    // Android: Copy google-services.json
    if (platforms.indexOf('android') !== -1) {
        copyGoogleServicesAndroid(projectRoot);
        copyMsalConfigAndroid(projectRoot);
    }
};

// Microsoft sign-in (MSAL) reads its config from res/raw/msal_auth_config.json. The plugin
// ships a placeholder there (client_id of zeros) so the resource always exists; an app that
// uses authenticateUserWithMicrosoft puts ITS OWN msal_auth_config.json in the project root
// (client_id + the msauth redirect of its package name and signing key) and this copies it
// over the placeholder on every prepare. No file: the placeholder stays, push and everything
// else work, and only a Microsoft sign-in fails with an MSAL error.
function copyMsalConfigAndroid(projectRoot) {
    var srcFile = path.join(projectRoot, 'msal_auth_config.json');
    if (!fs.existsSync(srcFile)) return;

    var destDir = path.join(projectRoot, 'platforms', 'android', 'app', 'src', 'main', 'res', 'raw');
    if (!fs.existsSync(path.join(projectRoot, 'platforms', 'android'))) return;
    if (!fs.existsSync(destDir)) {
        fs.mkdirSync(destDir, { recursive: true });
    }

    var destFile = path.join(destDir, 'msal_auth_config.json');
    fs.copyFileSync(srcFile, destFile);
    console.log('[WizzoPush] Copied msal_auth_config.json → ' + destFile);
}

function copyGoogleServiceiOS(projectRoot) {
    var srcFile = path.join(projectRoot, 'GoogleService-Info.plist');
    
    if (!fs.existsSync(srcFile)) {
        console.warn('[WizzoPush] WARNING: GoogleService-Info.plist not found in project root.');
        console.warn('[WizzoPush] Download it from Firebase Console and place it at: ' + srcFile);
        return;
    }
    
    // Find the iOS app directory (could be named differently per project)
    var platformPath = path.join(projectRoot, 'platforms', 'ios');
    if (!fs.existsSync(platformPath)) return;
    
    var appName = getIOSAppName(projectRoot);
    if (!appName) {
        console.warn('[WizzoPush] WARNING: Could not determine iOS app name');
        return;
    }
    
    var destDir = path.join(platformPath, appName, 'Resources');
    if (!fs.existsSync(destDir)) {
        fs.mkdirSync(destDir, { recursive: true });
    }
    
    var destFile = path.join(destDir, 'GoogleService-Info.plist');
    fs.copyFileSync(srcFile, destFile);
    console.log('[WizzoPush] Copied GoogleService-Info.plist → ' + destFile);
}

function copyGoogleServicesAndroid(projectRoot) {
    var srcFile = path.join(projectRoot, 'google-services.json');
    
    if (!fs.existsSync(srcFile)) {
        console.warn('[WizzoPush] WARNING: google-services.json not found in project root.');
        console.warn('[WizzoPush] Download it from Firebase Console and place it at: ' + srcFile);
        return;
    }
    
    var destDir = path.join(projectRoot, 'platforms', 'android', 'app');
    if (!fs.existsSync(destDir)) return;
    
    var destFile = path.join(destDir, 'google-services.json');
    fs.copyFileSync(srcFile, destFile);
    console.log('[WizzoPush] Copied google-services.json → ' + destFile);
}

function getIOSAppName(projectRoot) {
    // Read config.xml to get the app name/widget id
    var configXml = path.join(projectRoot, 'config.xml');
    if (!fs.existsSync(configXml)) return null;
    
    var content = fs.readFileSync(configXml, 'utf8');
    
    // Try <name> tag first
    var nameMatch = content.match(/<name>([^<]+)<\/name>/);
    if (nameMatch) return nameMatch[1].trim();
    
    // Fallback: look at existing directories in platforms/ios
    var platformPath = path.join(projectRoot, 'platforms', 'ios');
    if (!fs.existsSync(platformPath)) return null;
    
    var dirs = fs.readdirSync(platformPath).filter(function(f) {
        var fullPath = path.join(platformPath, f);
        return fs.statSync(fullPath).isDirectory() 
            && f !== 'CordovaLib' 
            && f !== 'Pods'
            && !f.endsWith('.xcodeproj')
            && !f.endsWith('.xcworkspace')
            && f !== 'build'
            && f !== 'www';
    });
    
    return dirs.length > 0 ? dirs[0] : null;
}
