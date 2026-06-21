#import "AppDelegate+WizzoPush.h"
#import "WizzoPushPlugin.h"
#import <FirebaseCore/FirebaseCore.h>
#import <FirebaseMessaging/FirebaseMessaging.h>
#import <objc/runtime.h>
@import GoogleSignIn;
@import MSAL;

@implementation AppDelegate (WizzoPush)

+ (void)load {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        Class class = [self class];
        
        // Swizzle application:didFinishLaunchingWithOptions:
        {
            SEL originalSelector = @selector(application:didFinishLaunchingWithOptions:);
            SEL swizzledSelector = @selector(wizzopush_application:didFinishLaunchingWithOptions:);
            
            Method originalMethod = class_getInstanceMethod(class, originalSelector);
            Method swizzledMethod = class_getInstanceMethod(class, swizzledSelector);
            
            BOOL didAddMethod = class_addMethod(class,
                                               originalSelector,
                                               method_getImplementation(swizzledMethod),
                                               method_getTypeEncoding(swizzledMethod));
            
            if (didAddMethod) {
                class_replaceMethod(class,
                                  swizzledSelector,
                                  method_getImplementation(originalMethod),
                                  method_getTypeEncoding(originalMethod));
            } else {
                method_exchangeImplementations(originalMethod, swizzledMethod);
            }
        }
        
        // Swizzle application:openURL:options: (for Google Sign-In URL handling)
        {
            SEL originalSelector = @selector(application:openURL:options:);
            SEL swizzledSelector = @selector(wizzopush_application:openURL:options:);
            
            Method originalMethod = class_getInstanceMethod(class, originalSelector);
            Method swizzledMethod = class_getInstanceMethod(class, swizzledSelector);
            
            BOOL didAddMethod = class_addMethod(class,
                                               originalSelector,
                                               method_getImplementation(swizzledMethod),
                                               method_getTypeEncoding(swizzledMethod));
            
            if (didAddMethod) {
                class_replaceMethod(class,
                                  swizzledSelector,
                                  method_getImplementation(originalMethod),
                                  method_getTypeEncoding(originalMethod));
            } else {
                method_exchangeImplementations(originalMethod, swizzledMethod);
            }
        }
    });
}

- (BOOL)wizzopush_application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    
    // Configure Firebase
    if ([FIRApp defaultApp] == nil) {
        [FIRApp configure];
    }
    
    // Check if launched from notification
    NSDictionary *remoteNotification = launchOptions[UIApplicationLaunchOptionsRemoteNotificationKey];
    if (remoteNotification) {
        NSLog(@"[WizzoPush] App launched from notification: %@", remoteNotification);
        
        // Save notification for getInitialPushPayload
        NSMutableDictionary *payload = [remoteNotification mutableCopy];
        payload[@"tap"] = @"background";
        
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
        [defaults setObject:payload forKey:@"WizzoPush_InitialPayload"];
        [defaults synchronize];
    }
    
    // Call original implementation
    return [self wizzopush_application:application didFinishLaunchingWithOptions:launchOptions];
}

// Handle APNS token registration
- (void)application:(UIApplication *)application didRegisterForRemoteNotificationsWithDeviceToken:(NSData *)deviceToken {
    NSLog(@"[WizzoPush] APNS token received");
    
    // Pass token to Firebase
    [FIRMessaging messaging].APNSToken = deviceToken;

    // Now that APNS is set, proactively fetch the FCM token and fire
    // the token-refresh callback so POOSH gets it immediately,
    // without relying on getToken polling.
    [[FIRMessaging messaging] tokenWithCompletion:^(NSString *fcmToken, NSError *error) {
        WizzoPushPlugin *p = [WizzoPushPlugin getInstance];
        if (fcmToken && p) {
            [p onFCMTokenRefresh:fcmToken];
        }
    }];

    // Convert token to string for WizzoPushPlugin
    const unsigned char *tokenBytes = (const unsigned char *)[deviceToken bytes];
    NSMutableString *tokenString = [NSMutableString stringWithCapacity:deviceToken.length * 2];
    for (NSUInteger i = 0; i < deviceToken.length; i++) {
        [tokenString appendFormat:@"%02x", tokenBytes[i]];
    }
    
    WizzoPushPlugin *plugin = [WizzoPushPlugin getInstance];
    if (plugin) {
        [plugin onNewToken:tokenString];
    }
}

- (void)application:(UIApplication *)application didFailToRegisterForRemoteNotificationsWithError:(NSError *)error {
    NSLog(@"[WizzoPush] Failed to register for remote notifications: %@", error);
}

// Handle silent push / background fetch
- (void)application:(UIApplication *)application didReceiveRemoteNotification:(NSDictionary *)userInfo fetchCompletionHandler:(void (^)(UIBackgroundFetchResult))completionHandler {
    NSLog(@"[WizzoPush] Background notification received: %@", userInfo);
    
    WizzoPushPlugin *plugin = [WizzoPushPlugin getInstance];
    if (plugin) {
        [plugin onNotificationReceived:userInfo fromTap:NO];
    }
    
    completionHandler(UIBackgroundFetchResultNewData);
}

// Handle Google Sign-In URL callback (swizzled to preserve Cordova's URL handling)
- (BOOL)wizzopush_application:(UIApplication *)application openURL:(NSURL *)url options:(NSDictionary<UIApplicationOpenURLOptionsKey,id> *)options {
    // Let Google Sign-In handle the URL first
    if ([GIDSignIn.sharedInstance handleURL:url]) {
        return YES;
    }

    // Let MSAL (Microsoft Sign-In) handle the redirect / broker callback
    NSString *sourceApplication = options[UIApplicationOpenURLOptionsSourceApplicationKey];
    if ([MSALPublicClientApplication handleMSALResponse:url sourceApplication:sourceApplication]) {
        return YES;
    }

    // Call original implementation (CDVAppDelegate's URL handling for other plugins)
    return [self wizzopush_application:application openURL:url options:options];
}

@end
