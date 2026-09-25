#!/usr/bin/env node
/**
 * WizzoPush - wire the Notification Service Extension into the generated Xcode project.
 *
 * A sender's picture on an iOS notification (the WhatsApp shape: her face where the app
 * icon sits, the app icon as the small badge) can only be drawn by a Notification Service
 * Extension, and an extension is a second BUILD TARGET. Nothing in config.xml or plugin.xml
 * can declare one, so the project file is edited after every `cordova prepare` (which
 * regenerates it whenever the platform is re-added). The pattern, and every gotcha named
 * below, comes from Kringl's share-extension hook (chat #825), proven on a signed build.
 *
 * OFF unless the app asks for it, in its config.xml:
 *
 *   <preference name="WizzoPushNotificationServiceExtension" value="true" />
 *       adds the target WizzoPushNSE (bundle id <app id>.nse) built from src/ios/nse/.
 *       The picture is attached as the notification's thumbnail. No entitlement needed;
 *       automatic signing registers the extension's App ID on the first build.
 *
 *   <preference name="WizzoPushCommunicationNotifications" value="true" />
 *       ALSO writes `com.apple.developer.usernotifications.communication` into both of the
 *       APP's entitlements files and INSendMessageIntent into its NSUserActivityTypes, so
 *       the extension may hand iOS a communication notification (the face where the app
 *       icon sits). The App ID must carry the "Communication Notifications" capability in
 *       the developer portal, or CodeSign fails at the very end of the archive with
 *       "...doesn't support the Communication Notifications capability".
 *
 * An app that sets neither is untouched: this hook exits at once, and the plugin behaves
 * exactly as before 1.2.0.
 *
 * Idempotent on purpose: a second target with the same name is a project Xcode cannot
 * open, so when the target exists only the copied sources are refreshed.
 */

const fs = require('fs');
const path = require('path');

const TARGET = 'WizzoPushNSE';
/**
 * The name as `xcode` stores it. addTarget() writes the target's name QUOTED and uses
 * that string as the section comment, and pbxTargetByName / updateBuildProperty look
 * that up: asked for the bare name they find nothing, and both answer by doing NOTHING
 * and returning. So the guard would never fire and no build setting would be applied,
 * silently. Ask with the quotes.
 */
const TARGET_KEY = `"${TARGET}"`;
const PREF_NSE = 'WizzoPushNotificationServiceExtension';
const PREF_COMM = 'WizzoPushCommunicationNotifications';
const COMM_ENTITLEMENT = 'com.apple.developer.usernotifications.communication';
const INTENT = 'INSendMessageIntent';

function log(msg) { console.log('[WizzoPush NSE] ' + msg); }
function fail(lines) {
    console.error('\n[WizzoPush NSE] ✖ ' + lines.join('\n    ') + '\n');
    process.exit(1);
}

/** cordova's own copies of `xcode` and `plist`; both ship with cordova-ios. */
function load(name, projectRoot) {
    const paths = [
        projectRoot,
        path.join(projectRoot, 'node_modules', 'cordova-ios'),
        path.join(projectRoot, 'platforms', 'ios', 'cordova'),
        __dirname,
    ];
    try {
        return require(require.resolve(name, { paths }));
    } catch (e) {
        fail([
            'the `' + name + '` module could not be resolved from ' + projectRoot + '.',
            'It ships with cordova-ios - run `npm install` in the project first.',
        ]);
    }
}

function readConfig(projectRoot) {
    const file = path.join(projectRoot, 'config.xml');
    return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
}

function preference(xml, name) {
    const re = new RegExp('<preference[^>]*name="' + name + '"[^>]*value="([^"]*)"', 'i');
    const m = xml.match(re);
    return m ? m[1].trim() : '';
}

function truthy(v) { return /^(true|1|yes|on)$/i.test(String(v || '')); }

/**
 * The directory of the app target inside platforms/ios: `App/` on cordova-ios 8 whatever
 * the app is called, `<Name>/` on cordova-ios 7. It is the one that holds *-Info.plist
 * and the two Entitlements files, which is how it is recognised.
 */
function appTargetDir(iosDir) {
    const candidates = fs.readdirSync(iosDir).filter((f) => {
        const full = path.join(iosDir, f);
        if (!fs.statSync(full).isDirectory()) return false;
        if (['CordovaLib', 'Pods', 'build', 'www', 'cordova', 'platform_www', TARGET].includes(f)) return false;
        if (f.endsWith('.xcodeproj') || f.endsWith('.xcworkspace')) return false;
        return fs.readdirSync(full).some((n) => n.endsWith('-Info.plist'));
    });
    return candidates[0] || null;
}

function infoPlistPath(iosDir, dir) {
    const name = fs.readdirSync(path.join(iosDir, dir)).find((n) => n.endsWith('-Info.plist'));
    return name ? path.join(iosDir, dir, name) : null;
}

function copySources(iosDir) {
    const from = path.join(__dirname, '..', 'src', 'ios', 'nse');
    const to = path.join(iosDir, TARGET);
    if (!fs.existsSync(from)) fail(['src/ios/nse is missing from the plugin - nothing to install.']);
    if (!fs.existsSync(to)) fs.mkdirSync(to, { recursive: true });
    const names = fs.readdirSync(from);
    for (const name of names) fs.copyFileSync(path.join(from, name), path.join(to, name));
    return names;
}

/** Communication notifications: the entitlement on BOTH of the app's entitlements files. */
function addCommunicationEntitlement(iosDir, dir, plist) {
    const files = ['Entitlements-Debug.plist', 'Entitlements-Release.plist']
        .map((n) => path.join(iosDir, dir, n))
        .filter((p) => fs.existsSync(p));
    if (!files.length) {
        log('no entitlements files yet - the communication entitlement is not written');
        return;
    }
    for (const file of files) {
        const data = plist.parse(fs.readFileSync(file, 'utf8')) || {};
        if (data[COMM_ENTITLEMENT] === true) continue;
        data[COMM_ENTITLEMENT] = true;
        fs.writeFileSync(file, plist.build(data));
        log(path.basename(file) + ' <- ' + COMM_ENTITLEMENT);
    }
}

/** ...and INSendMessageIntent in the app's Info.plist, or iOS ignores the intent. */
function addIntentActivityType(iosDir, dir, plist) {
    const file = infoPlistPath(iosDir, dir);
    if (!file) return;
    const data = plist.parse(fs.readFileSync(file, 'utf8')) || {};
    const types = Array.isArray(data.NSUserActivityTypes) ? data.NSUserActivityTypes : [];
    if (types.includes(INTENT)) return;
    types.push(INTENT);
    data.NSUserActivityTypes = types;
    fs.writeFileSync(file, plist.build(data));
    log(path.basename(file) + ' <- NSUserActivityTypes ' + INTENT);
}

function afterPrepare(projectRoot) {
    const xml = readConfig(projectRoot);
    const wantNse = truthy(preference(xml, PREF_NSE));
    const wantComm = truthy(preference(xml, PREF_COMM));
    if (!wantNse && !wantComm) return;

    const iosDir = path.join(projectRoot, 'platforms', 'ios');
    if (!fs.existsSync(iosDir)) return;

    const dir = appTargetDir(iosDir);
    if (!dir) fail(['no app target directory (one holding *-Info.plist) under platforms/ios.']);

    const projName = fs.readdirSync(iosDir).find((n) => n.endsWith('.xcodeproj'));
    const pbxPath = projName ? path.join(iosDir, projName, 'project.pbxproj') : null;
    if (!pbxPath || !fs.existsSync(pbxPath)) fail(['no .xcodeproj under platforms/ios.']);

    const xcode = load('xcode', projectRoot);
    const plist = load('plist', projectRoot);

    if (wantComm) {
        addCommunicationEntitlement(iosDir, dir, plist);
        addIntentActivityType(iosDir, dir, plist);
    }
    if (!wantNse) return;

    const copied = copySources(iosDir);

    const proj = xcode.project(pbxPath);
    proj.parseSync();

    if (proj.pbxTargetByName(TARGET_KEY) || proj.pbxTargetByName(TARGET)) {
        log(TARGET + ' is already a target - sources refreshed');
        return;
    }

    const appId = (xml.match(/<widget[^>]*\sid="([^"]+)"/) || [])[1] || 'app';
    const version = (xml.match(/<widget[^>]*\sversion="([^"]+)"/) || [])[1] || '1.0.0';
    const team = preference(xml, 'development-team');
    const deployment = preference(xml, 'deployment-target') || '13.0';
    const swift = preference(xml, 'SwiftVersion') || '5.0';
    const bundleId = appId + '.nse';

    const group = proj.addPbxGroup([], TARGET, TARGET);
    proj.addToPbxGroup(group.uuid, proj.getFirstProject().firstProject.mainGroup);

    // addTarget also adds the "Copy Files" phase on the FIRST target (the app) that embeds
    // the extension: an extension that is built but not embedded never runs, silently.
    const target = proj.addTarget(TARGET, 'app_extension', TARGET, bundleId);
    proj.addBuildPhase([], 'PBXSourcesBuildPhase', 'Sources', target.uuid);
    proj.addBuildPhase([], 'PBXFrameworksBuildPhase', 'Frameworks', target.uuid);
    proj.addBuildPhase([], 'PBXResourcesBuildPhase', 'Resources', target.uuid);

    for (const name of copied) {
        if (name.endsWith('.swift')) proj.addSourceFile(name, { target: target.uuid }, group.uuid);
    }

    const settings = {
        INFOPLIST_FILE: `"${TARGET}/Info.plist"`,
        PRODUCT_BUNDLE_IDENTIFIER: `"${bundleId}"`,
        PRODUCT_NAME: `"${TARGET}"`,
        MARKETING_VERSION: `"${version}"`,
        CURRENT_PROJECT_VERSION: `"${version}"`,
        IPHONEOS_DEPLOYMENT_TARGET: deployment,
        SWIFT_VERSION: swift,
        TARGETED_DEVICE_FAMILY: '"1,2"',
        CODE_SIGN_STYLE: 'Automatic',
        SKIP_INSTALL: 'YES',
        // The Swift runtime comes from the host app; a second copy gets the app rejected.
        ALWAYS_EMBED_SWIFT_STANDARD_LIBRARIES: 'NO',
        GENERATE_INFOPLIST_FILE: 'NO',
    };
    if (team) settings.DEVELOPMENT_TEAM = team;
    for (const [key, value] of Object.entries(settings)) {
        proj.updateBuildProperty(key, value, null, TARGET_KEY);
    }
    if (team) proj.addTargetAttribute('DevelopmentTeam', team, target);
    proj.addTargetAttribute('ProvisioningStyle', 'Automatic', target);

    fs.writeFileSync(pbxPath, proj.writeSync());
    verify(xcode, pbxPath);
    log(`${TARGET} added to the Xcode project (${bundleId}, v${version}, iOS ${deployment}+)`);
}

/** Read the project back; refuse to pass a half-made target (every failure above is silent). */
function verify(xcode, pbxPath) {
    const proj = xcode.project(pbxPath);
    proj.parseSync();
    const section = proj.pbxNativeTargetSection();
    const mine = Object.keys(section).filter((k) =>
        !/_comment$/.test(k) && String(section[k] && section[k].name).replace(/"/g, '') === TARGET);
    if (mine.length !== 1) fail([`the project now has ${mine.length} targets called ${TARGET}.`]);

    const target = section[mine[0]];
    const lists = proj.pbxXCConfigurationList();
    const configs = proj.pbxXCBuildConfigurationSection();
    const variants = (lists[target.buildConfigurationList] || {}).buildConfigurations || [];
    if (!variants.length) fail([`${TARGET} has no build configurations.`]);
    for (const variant of variants) {
        const settings = (configs[variant.value] || {}).buildSettings || {};
        for (const key of ['INFOPLIST_FILE', 'PRODUCT_BUNDLE_IDENTIFIER']) {
            if (!settings[key]) fail([`${TARGET}'s ${configs[variant.value].name} configuration has no ${key} (see TARGET_KEY).`]);
        }
    }
    const phases = (target.buildPhases || []).map((b) => b.comment);
    if (!phases.includes('Sources')) fail([`${TARGET} has no Sources phase - it would build an empty bundle.`]);
}

module.exports = function (context) {
    const projectRoot = (context && context.opts && context.opts.projectRoot) || process.cwd();
    const platforms = (context && context.opts && (context.opts.platforms || (context.opts.cordova && context.opts.cordova.platforms))) || ['ios'];
    if (platforms.indexOf('ios') === -1) return;
    afterPrepare(projectRoot);
};

if (require.main === module) module.exports({ opts: { projectRoot: process.cwd(), platforms: ['ios'] } });
