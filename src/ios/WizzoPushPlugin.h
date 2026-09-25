#import <Cordova/CDV.h>
#import <UserNotifications/UserNotifications.h>

@interface WizzoPushPlugin : CDVPlugin <UNUserNotificationCenterDelegate>

// Singleton access
+ (WizzoPushPlugin *)getInstance;

// Token Management
- (void)getToken:(CDVInvokedUrlCommand*)command;
- (void)onTokenRefresh:(CDVInvokedUrlCommand*)command;
- (void)getAPNSToken:(CDVInvokedUrlCommand*)command;

// Permissions
- (void)hasPermission:(CDVInvokedUrlCommand*)command;
- (void)grantPermission:(CDVInvokedUrlCommand*)command;

// Message Handling
- (void)onMessageReceived:(CDVInvokedUrlCommand*)command;
- (void)getInitialPushPayload:(CDVInvokedUrlCommand*)command;

// Notification Management
- (void)clearAllNotifications:(CDVInvokedUrlCommand*)command;
- (void)clearConversation:(CDVInvokedUrlCommand*)command;
- (void)setBadgeNumber:(CDVInvokedUrlCommand*)command;
- (void)getBadgeNumber:(CDVInvokedUrlCommand*)command;

// Topics
- (void)subscribe:(CDVInvokedUrlCommand*)command;
- (void)unsubscribe:(CDVInvokedUrlCommand*)command;

// Token Deletion
- (void)unregister:(CDVInvokedUrlCommand*)command;

// Auto-init
- (void)isAutoInitEnabled:(CDVInvokedUrlCommand*)command;
- (void)setAutoInitEnabled:(CDVInvokedUrlCommand*)command;

// Diagnostics
- (void)getDiagnostics:(CDVInvokedUrlCommand*)command;
- (void)clearDiagnostics:(CDVInvokedUrlCommand*)command;

// Firebase Analytics
- (void)logEvent:(CDVInvokedUrlCommand*)command;
- (void)setAnalyticsCollectionEnabled:(CDVInvokedUrlCommand*)command;
- (void)setUserId:(CDVInvokedUrlCommand*)command;
- (void)setUserProperty:(CDVInvokedUrlCommand*)command;
- (void)setScreenName:(CDVInvokedUrlCommand*)command;

// Google Sign-In
- (void)authenticateUserWithGoogle:(CDVInvokedUrlCommand*)command;
- (void)signOutGoogle:(CDVInvokedUrlCommand*)command;

// Microsoft Sign-In
- (void)authenticateUserWithMicrosoft:(CDVInvokedUrlCommand*)command;

// Internal callbacks (called from AppDelegate category)
- (void)onNewToken:(NSString *)token;
- (void)onFCMTokenRefresh:(NSString *)fcmToken;
- (void)onNotificationReceived:(NSDictionary *)payload fromTap:(BOOL)fromTap;

@end
