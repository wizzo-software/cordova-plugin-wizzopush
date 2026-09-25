#import "WizzoPushPlugin.h"
#import <FirebaseCore/FirebaseCore.h>
#import <FirebaseMessaging/FirebaseMessaging.h>
#import <FirebaseAnalytics/FirebaseAnalytics.h>
@import FirebaseAuth;
@import GoogleSignIn;
@import MSAL;

@interface WizzoPushPlugin () <FIRMessagingDelegate>

@property (nonatomic, strong) NSString *messageReceivedCallbackId;
@property (nonatomic, strong) NSString *tokenRefreshCallbackId;
@property (nonatomic, strong) NSDictionary *pendingNotification;
@property (nonatomic, strong) NSString *currentAPNSToken;
@property (nonatomic, strong) NSString *googleSignInCallbackId;
@property (nonatomic, strong) NSString *microsoftSignInCallbackId;

@end

static WizzoPushPlugin *sharedInstance = nil;

@implementation WizzoPushPlugin

+ (WizzoPushPlugin *)getInstance {
    return sharedInstance;
}

- (void)pluginInitialize {
    [super pluginInitialize];
    sharedInstance = self;
    
    NSLog(@"[WizzoPush] Plugin initialized");
    
    // Configure Firebase if not already done
    if ([FIRApp defaultApp] == nil) {
        [FIRApp configure];
    }
    
    // Set messaging delegate
    [FIRMessaging messaging].delegate = self;
    
    // Set notification center delegate
    [UNUserNotificationCenter currentNotificationCenter].delegate = self;

    // Request APNS token early (silent, no permission popup) so Firebase can
    // issue an FCM token immediately — just like Android. Without this,
    // getToken fails until the user goes through grantPermission, and the
    // POOSH auto-register never receives a token.
    dispatch_async(dispatch_get_main_queue(), ^{
        [[UIApplication sharedApplication] registerForRemoteNotifications];
    });

    // Check for pending notification from app launch
    [self checkLaunchNotification];
}

- (void)checkLaunchNotification {
    // Check if app was launched from notification
    NSDictionary *launchOptions = self.commandDelegate.settings;
    // Launch notification will be handled by AppDelegate category
}

#pragma mark - Token Management

- (void)getToken:(CDVInvokedUrlCommand*)command {
    [[FIRMessaging messaging] tokenWithCompletion:^(NSString *token, NSError *error) {
        CDVPluginResult *result;
        if (error) {
            NSLog(@"[WizzoPush] Error getting FCM token: %@", error);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR 
                                       messageAsString:error.localizedDescription];
        } else {
            NSLog(@"[WizzoPush] FCM token: %@", token);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                       messageAsString:token];
        }
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

- (void)onTokenRefresh:(CDVInvokedUrlCommand*)command {
    self.tokenRefreshCallbackId = command.callbackId;
    
    // Keep callback for persistent use
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_NO_RESULT];
    [result setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)getAPNSToken:(CDVInvokedUrlCommand*)command {
    CDVPluginResult *result;
    if (self.currentAPNSToken) {
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                   messageAsString:self.currentAPNSToken];
    } else {
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                   messageAsString:nil];
    }
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

#pragma mark - FIRMessagingDelegate

- (void)messaging:(FIRMessaging *)messaging didReceiveRegistrationToken:(NSString *)fcmToken {
    NSLog(@"[WizzoPush] FCM registration token: %@", fcmToken);
    
    if (self.tokenRefreshCallbackId) {
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                                    messageAsString:fcmToken];
        [result setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:result callbackId:self.tokenRefreshCallbackId];
    }
}

#pragma mark - Permissions

- (void)hasPermission:(CDVInvokedUrlCommand*)command {
    [[UNUserNotificationCenter currentNotificationCenter] getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
        BOOL hasPermission = (settings.authorizationStatus == UNAuthorizationStatusAuthorized ||
                             settings.authorizationStatus == UNAuthorizationStatusProvisional);
        
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                                    messageAsString:hasPermission ? @"true" : @"false"];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

- (void)grantPermission:(CDVInvokedUrlCommand*)command {
    UNAuthorizationOptions options = UNAuthorizationOptionAlert | 
                                     UNAuthorizationOptionBadge | 
                                     UNAuthorizationOptionSound;
    
    [[UNUserNotificationCenter currentNotificationCenter] requestAuthorizationWithOptions:options 
                                                                        completionHandler:^(BOOL granted, NSError *error) {
        if (granted) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [[UIApplication sharedApplication] registerForRemoteNotifications];
            });
        }
        
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                                    messageAsString:granted ? @"true" : @"false"];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

#pragma mark - Message Handling

- (void)onMessageReceived:(CDVInvokedUrlCommand*)command {
    self.messageReceivedCallbackId = command.callbackId;
    
    // Keep callback for persistent use
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_NO_RESULT];
    [result setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    
    // If there's a pending notification, send it now
    if (self.pendingNotification) {
        [self onNotificationReceived:self.pendingNotification fromTap:YES];
        self.pendingNotification = nil;
    }
}

- (void)getInitialPushPayload:(CDVInvokedUrlCommand*)command {
    CDVPluginResult *result;
    
    // Check UserDefaults for saved notification
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSDictionary *savedPayload = [defaults objectForKey:@"WizzoPush_InitialPayload"];
    
    if (savedPayload) {
        NSLog(@"[WizzoPush] Initial push payload: %@", savedPayload);
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                               messageAsDictionary:savedPayload];
        // Clear saved payload
        [defaults removeObjectForKey:@"WizzoPush_InitialPayload"];
        [defaults synchronize];
    } else if (self.pendingNotification) {
        NSMutableDictionary *payload = [self.pendingNotification mutableCopy];
        payload[@"tap"] = @"background";
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                               messageAsDictionary:payload];
        self.pendingNotification = nil;
    } else {
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:nil];
    }
    
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)onNotificationReceived:(NSDictionary *)payload fromTap:(BOOL)fromTap {
    NSLog(@"[WizzoPush] Notification received, fromTap: %d, payload: %@", fromTap, payload);
    
    if (self.messageReceivedCallbackId) {
        NSMutableDictionary *mutablePayload = [payload mutableCopy];
        if (fromTap) {
            mutablePayload[@"tap"] = @"background";
        }
        
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                                messageAsDictionary:mutablePayload];
        [result setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:result callbackId:self.messageReceivedCallbackId];
    } else {
        // Save for later if no callback registered yet
        if (fromTap) {
            self.pendingNotification = payload;
        }
    }
}

- (void)onNewToken:(NSString *)token {
    NSLog(@"[WizzoPush] APNS token received");
    self.currentAPNSToken = token;
}

// Public entry point for the AppDelegate category to forward a freshly-fetched
// FCM token. Routes through the FIRMessagingDelegate handler (which is declared
// in a private class extension and therefore not visible outside this file).
- (void)onFCMTokenRefresh:(NSString *)fcmToken {
    [self messaging:[FIRMessaging messaging] didReceiveRegistrationToken:fcmToken];
}

#pragma mark - UNUserNotificationCenterDelegate

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    
    NSDictionary *userInfo = notification.request.content.userInfo;
    NSLog(@"[WizzoPush] Will present notification: %@", userInfo);
    
    // Notify JS about foreground notification
    [self onNotificationReceived:userInfo fromTap:NO];
    
    // Show notification even when app is in foreground
    if (@available(iOS 14.0, *)) {
        completionHandler(UNNotificationPresentationOptionBanner | 
                         UNNotificationPresentationOptionSound | 
                         UNNotificationPresentationOptionBadge);
    } else {
        completionHandler(UNNotificationPresentationOptionAlert | 
                         UNNotificationPresentationOptionSound | 
                         UNNotificationPresentationOptionBadge);
    }
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
didReceiveNotificationResponse:(UNNotificationResponse *)response
         withCompletionHandler:(void (^)(void))completionHandler {
    
    NSDictionary *userInfo = response.notification.request.content.userInfo;
    NSLog(@"[WizzoPush] Notification tapped: %@", userInfo);
    
    // Notify JS about notification tap
    [self onNotificationReceived:userInfo fromTap:YES];
    
    completionHandler();
}

#pragma mark - Notification Management

- (void)clearAllNotifications:(CDVInvokedUrlCommand*)command {
    [[UNUserNotificationCenter currentNotificationCenter] removeAllDeliveredNotifications];
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

/**
 * Dismiss the delivered notifications of ONE conversation (1.2.0). The service extension
 * files every message under its conversation_id as the threadIdentifier, so the cards of
 * a thread the user just opened inside the app can go without touching the others.
 * An unknown or empty id removes nothing and still answers OK.
 */
- (void)clearConversation:(CDVInvokedUrlCommand*)command {
    NSString *conversationId = [command.arguments count] ? [command argumentAtIndex:0] : @"";
    if (![conversationId isKindOfClass:[NSString class]]) conversationId = @"";
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    [center getDeliveredNotificationsWithCompletionHandler:^(NSArray<UNNotification *> *notifications) {
        NSMutableArray<NSString *> *ids = [NSMutableArray array];
        if (conversationId.length) {
            for (UNNotification *n in notifications) {
                if ([n.request.content.threadIdentifier isEqualToString:conversationId]) {
                    [ids addObject:n.request.identifier];
                }
            }
        }
        if (ids.count) [center removeDeliveredNotificationsWithIdentifiers:ids];
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

- (void)setBadgeNumber:(CDVInvokedUrlCommand*)command {
    NSInteger number = [[command argumentAtIndex:0] integerValue];
    
    dispatch_async(dispatch_get_main_queue(), ^{
        [UIApplication sharedApplication].applicationIconBadgeNumber = number;
    });
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)getBadgeNumber:(CDVInvokedUrlCommand*)command {
    NSInteger badge = [UIApplication sharedApplication].applicationIconBadgeNumber;
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                                  messageAsInt:(int)badge];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

#pragma mark - Topics

- (void)subscribe:(CDVInvokedUrlCommand*)command {
    NSString *topic = [command argumentAtIndex:0];
    
    [[FIRMessaging messaging] subscribeToTopic:topic completion:^(NSError *error) {
        CDVPluginResult *result;
        if (error) {
            NSLog(@"[WizzoPush] Error subscribing to topic: %@", error);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR 
                                       messageAsString:error.localizedDescription];
        } else {
            NSLog(@"[WizzoPush] Subscribed to topic: %@", topic);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        }
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

- (void)unsubscribe:(CDVInvokedUrlCommand*)command {
    NSString *topic = [command argumentAtIndex:0];
    
    [[FIRMessaging messaging] unsubscribeFromTopic:topic completion:^(NSError *error) {
        CDVPluginResult *result;
        if (error) {
            NSLog(@"[WizzoPush] Error unsubscribing from topic: %@", error);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR 
                                       messageAsString:error.localizedDescription];
        } else {
            NSLog(@"[WizzoPush] Unsubscribed from topic: %@", topic);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        }
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

#pragma mark - Token Deletion

- (void)unregister:(CDVInvokedUrlCommand*)command {
    [[FIRMessaging messaging] deleteTokenWithCompletion:^(NSError *error) {
        CDVPluginResult *result;
        if (error) {
            NSLog(@"[WizzoPush] Error deleting token: %@", error);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR 
                                       messageAsString:error.localizedDescription];
        } else {
            NSLog(@"[WizzoPush] Token deleted");
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        }
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

#pragma mark - Auto-init

- (void)isAutoInitEnabled:(CDVInvokedUrlCommand*)command {
    BOOL enabled = [FIRMessaging messaging].autoInitEnabled;
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                                messageAsString:enabled ? @"true" : @"false"];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)setAutoInitEnabled:(CDVInvokedUrlCommand*)command {
    BOOL enabled = [[command argumentAtIndex:0] boolValue];
    [FIRMessaging messaging].autoInitEnabled = enabled;
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

#pragma mark - Diagnostics

- (void)getDiagnostics:(CDVInvokedUrlCommand*)command {
    NSMutableDictionary *diagnostics = [NSMutableDictionary dictionary];
    
    diagnostics[@"platform"] = @"ios";
    diagnostics[@"iosVersion"] = [[UIDevice currentDevice] systemVersion];
    diagnostics[@"pluginVersion"] = @"1.0.0";
    
    // Check notification permission
    [[UNUserNotificationCenter currentNotificationCenter] getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
        NSString *permission;
        switch (settings.authorizationStatus) {
            case UNAuthorizationStatusAuthorized:
            case UNAuthorizationStatusProvisional:
                permission = @"granted";
                break;
            case UNAuthorizationStatusDenied:
                permission = @"denied";
                break;
            default:
                permission = @"not_determined";
                break;
        }
        diagnostics[@"notificationPermission"] = permission;
        
        diagnostics[@"hasMessageCallback"] = @(self.messageReceivedCallbackId != nil);
        diagnostics[@"hasTokenCallback"] = @(self.tokenRefreshCallbackId != nil);
        
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK 
                                                messageAsDictionary:diagnostics];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

- (void)clearDiagnostics:(CDVInvokedUrlCommand*)command {
    // Reserved for future diagnostic logging
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

#pragma mark - Firebase Analytics

- (void)logEvent:(CDVInvokedUrlCommand*)command {
    NSString *eventName = [command argumentAtIndex:0];
    NSDictionary *params = [command argumentAtIndex:1 withDefault:@{}];
    
    [FIRAnalytics logEventWithName:eventName parameters:params];
    NSLog(@"[WizzoPush] Logged event: %@", eventName);
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)setAnalyticsCollectionEnabled:(CDVInvokedUrlCommand*)command {
    BOOL enabled = [[command argumentAtIndex:0] boolValue];
    [FIRAnalytics setAnalyticsCollectionEnabled:enabled];
    NSLog(@"[WizzoPush] Analytics collection enabled: %d", enabled);
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)setUserId:(CDVInvokedUrlCommand*)command {
    NSString *userId = [command argumentAtIndex:0];
    [FIRAnalytics setUserID:userId];
    NSLog(@"[WizzoPush] Set user ID: %@", userId);
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)setUserProperty:(CDVInvokedUrlCommand*)command {
    NSString *name = [command argumentAtIndex:0];
    NSString *value = [command argumentAtIndex:1];
    [FIRAnalytics setUserPropertyString:value forName:name];
    NSLog(@"[WizzoPush] Set user property: %@ = %@", name, value);
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)setScreenName:(CDVInvokedUrlCommand*)command {
    NSString *screenName = [command argumentAtIndex:0];
    [FIRAnalytics logEventWithName:kFIREventScreenView
                        parameters:@{kFIRParameterScreenName: screenName,
                                    kFIRParameterScreenClass: screenName}];
    NSLog(@"[WizzoPush] Set screen name: %@", screenName);
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

#pragma mark - Google Sign-In

- (void)authenticateUserWithGoogle:(CDVInvokedUrlCommand*)command {
    NSString *serverClientId = [command argumentAtIndex:0];
    self.googleSignInCallbackId = command.callbackId;
    
    dispatch_async(dispatch_get_main_queue(), ^{
        // Configure GIDSignIn with client ID from Firebase + server client ID from JS
        NSString *iosClientId = [FIRApp defaultApp].options.clientID;
        if (iosClientId) {
            GIDConfiguration *config = [[GIDConfiguration alloc] initWithClientID:iosClientId
                                                                   serverClientID:serverClientId];
            GIDSignIn.sharedInstance.configuration = config;
        }
        
        // GoogleSignIn 7.0+ API
        [GIDSignIn.sharedInstance signInWithPresentingViewController:self.viewController
                                                                hint:nil
                                                    additionalScopes:@[]
                                                          completion:^(GIDSignInResult * _Nullable result, NSError * _Nullable error) {
            if (error) {
                NSLog(@"[WizzoPush] Google Sign-In error: %@", error);
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                           messageAsString:error.localizedDescription];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:self.googleSignInCallbackId];
                self.googleSignInCallbackId = nil;
                return;
            }
            
            GIDGoogleUser *user = result.user;
            
            // Refresh tokens to get idToken
            [user refreshTokensIfNeededWithCompletion:^(GIDGoogleUser * _Nullable refreshedUser, NSError * _Nullable error) {
                if (error) {
                    NSLog(@"[WizzoPush] Google token refresh error: %@", error);
                    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                               messageAsString:error.localizedDescription];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.googleSignInCallbackId];
                    self.googleSignInCallbackId = nil;
                    return;
                }
                
                GIDGoogleUser *finalUser = refreshedUser ?: user;
                
                NSDictionary *credential = @{
                    @"idToken": finalUser.idToken.tokenString ?: @"",
                    @"accessToken": finalUser.accessToken.tokenString ?: @"",
                    @"email": finalUser.profile.email ?: @"",
                    @"displayName": finalUser.profile.name ?: @"",
                    @"photoUrl": finalUser.profile.hasImage ? 
                        [[finalUser.profile imageURLWithDimension:200] absoluteString] : @"",
                    @"id": finalUser.userID ?: @""
                };
                
                NSLog(@"[WizzoPush] Google Sign-In successful: %@", finalUser.profile.email);
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                        messageAsDictionary:credential];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:self.googleSignInCallbackId];
                self.googleSignInCallbackId = nil;
            }];
        }];
    });
}

- (void)signOutGoogle:(CDVInvokedUrlCommand*)command {
    [GIDSignIn.sharedInstance signOut];
    NSLog(@"[WizzoPush] Google Sign-Out completed");
    
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

#pragma mark - Microsoft Sign-In (MSAL)

- (void)authenticateUserWithMicrosoft:(CDVInvokedUrlCommand*)command {
    self.microsoftSignInCallbackId = command.callbackId;
    
    dispatch_async(dispatch_get_main_queue(), ^{
        NSError *msalError = nil;
        
        MSALPublicClientApplicationConfig *config =
            [[MSALPublicClientApplicationConfig alloc] initWithClientId:@"b381881a-e94c-4a86-a804-1fdc249e3996"];
        config.authority = [[MSALAADAuthority alloc] initWithURL:
            [NSURL URLWithString:@"https://login.microsoftonline.com/common"] error:nil];
        
        MSALPublicClientApplication *msalApp =
            [[MSALPublicClientApplication alloc] initWithConfiguration:config error:&msalError];
        
        if (msalError || !msalApp) {
            NSLog(@"[WizzoPush] MSAL init error: %@", msalError);
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                       messageAsString:msalError.localizedDescription ?: @"MSAL init failed"];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self.microsoftSignInCallbackId];
            self.microsoftSignInCallbackId = nil;
            return;
        }
        
        MSALWebviewParameters *webParams =
            [[MSALWebviewParameters alloc] initWithAuthPresentationViewController:self.viewController];
        
        NSArray<NSString *> *scopes = @[@"User.Read", @"openid", @"profile", @"email"];
        MSALInteractiveTokenParameters *params =
            [[MSALInteractiveTokenParameters alloc] initWithScopes:scopes webviewParameters:webParams];
        params.promptType = MSALPromptTypeSelectAccount;
        
        [msalApp acquireTokenWithParameters:params
                          completionBlock:^(MSALResult * _Nullable result, NSError * _Nullable error) {
            if (error) {
                NSLog(@"[WizzoPush] Microsoft MSAL Sign-In error: %@", error);
                
                if ([error.domain isEqualToString:MSALErrorDomain] &&
                    error.code == MSALErrorUserCanceled) {
                    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                               messageAsString:@"Microsoft Sign-In cancelled"];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.microsoftSignInCallbackId];
                } else {
                    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                               messageAsString:error.localizedDescription];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.microsoftSignInCallbackId];
                }
                self.microsoftSignInCallbackId = nil;
                return;
            }
            
            if (!result) {
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                           messageAsString:@"No result returned"];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:self.microsoftSignInCallbackId];
                self.microsoftSignInCallbackId = nil;
                return;
            }
            
            MSALAccount *account = result.account;
            NSString *displayName = @"";
            NSDictionary *claims = account.accountClaims;
            if (claims[@"name"]) {
                displayName = claims[@"name"];
            }
            
            NSDictionary *credential = @{
                @"accessToken": result.accessToken ?: @"",
                @"idToken": result.idToken ?: @"",
                @"email": account.username ?: @"",
                @"displayName": displayName,
                @"uid": account.identifier ?: @"",
                @"authMethod": @"msal"
            };
            
            NSLog(@"[WizzoPush] Microsoft MSAL Sign-In successful: %@", account.username);
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                    messageAsDictionary:credential];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self.microsoftSignInCallbackId];
            self.microsoftSignInCallbackId = nil;
        }];
    });
}

@end
