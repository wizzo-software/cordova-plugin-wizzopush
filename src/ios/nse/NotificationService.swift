//
//  NotificationService.swift
//  cordova-plugin-wizzopush - the Notification Service Extension (iOS)
//
//  iOS draws every remote notification itself, and the only way to change what it draws is
//  this extension: when the APNs payload carries `mutable-content: 1` (POOSH sets it on every
//  app push), iOS starts this process BEFORE the banner is shown, hands it the content and
//  waits (up to ~30 seconds) for a modified copy.
//
//  What this one does, from the data keys the server sends (the same keys the Android
//  plugin draws from, see README "Sender avatar notifications"):
//
//    icon             https URL of the SENDER's picture (the person / the character who
//                     wrote the message). Downloaded here.
//    sender_name      the sender's display name.
//    sender_key       a stable id of the sender (a user id, a character key).
//    conversation_id  the thread: notifications of one conversation are grouped, and
//                     WizzoPush.clearConversation(id) removes exactly them.
//    image            https URL of a big picture to attach (optional).
//
//  Two shapes, best first:
//
//    1. A COMMUNICATION notification (iOS 15+): the sender's picture is drawn large at the
//       start of the banner where the app icon normally sits, the app icon becomes the small
//       badge on it, and the name is the title. This is the shape WhatsApp, Messages and
//       Telegram have. It needs the app to carry the
//       `com.apple.developer.usernotifications.communication` entitlement and to list
//       INSendMessageIntent under NSUserActivityTypes; the plugin hook writes both when the
//       app's config.xml says <preference name="WizzoPushCommunicationNotifications"
//       value="true" />. Without them content.updating(from:) throws and we fall through.
//
//    2. An ATTACHMENT: the picture as the notification's thumbnail (at the end of the
//       banner, the app icon stays at the start). Works on every iOS with no entitlement.
//
//  Whatever happens the notification is ALWAYS delivered: every path ends in
//  contentHandler(...), and serviceExtensionTimeWillExpire hands over the best copy we have.
//

import UserNotifications
import Intents
import UIKit

class NotificationService: UNNotificationServiceExtension {

    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttempt: UNMutableNotificationContent?

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        guard let content = request.content.mutableCopy() as? UNMutableNotificationContent else {
            contentHandler(request.content)
            return
        }
        bestAttempt = content

        let info = request.content.userInfo
        let iconUrl = Self.string(info["icon"])
        let imageUrl = Self.string(info["image"])
        let senderName = Self.string(info["sender_name"])
        let senderKey = Self.string(info["sender_key"])
        let conversationId = Self.string(info["conversation_id"])

        if let cid = conversationId, !cid.isEmpty {
            content.threadIdentifier = cid
        }

        // Nothing to draw beyond what iOS draws itself.
        if iconUrl == nil && imageUrl == nil {
            contentHandler(content)
            return
        }

        let group = DispatchGroup()
        var iconData: Data?
        var imageFile: URL?

        if let u = iconUrl, let url = URL(string: u) {
            group.enter()
            Self.download(url) { data, _ in
                iconData = data
                group.leave()
            }
        }
        if let u = imageUrl, let url = URL(string: u) {
            group.enter()
            Self.download(url) { data, response in
                if let data = data {
                    imageFile = Self.writeTemp(data, suggestedName: url.lastPathComponent,
                                               mime: response?.mimeType)
                }
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self = self else { return }
            self.finish(content, iconData: iconData, imageFile: imageFile,
                        senderName: senderName, senderKey: senderKey,
                        conversationId: conversationId, handler: contentHandler)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        if let handler = contentHandler, let content = bestAttempt {
            handler(content)
        }
    }

    // MARK: - Building the notification

    private func finish(_ content: UNMutableNotificationContent, iconData: Data?, imageFile: URL?,
                        senderName: String?, senderKey: String?, conversationId: String?,
                        handler: @escaping (UNNotificationContent) -> Void) {
        var attachments: [UNNotificationAttachment] = []
        if let file = imageFile, let a = try? UNNotificationAttachment(identifier: "wizzopush-image", url: file, options: nil) {
            attachments.append(a)
        }

        // Shape 1: the sender's picture where the app icon sits.
        if #available(iOS 15.0, *), let data = iconData, let name = senderName, !name.isEmpty {
            let avatar = INImage(imageData: data)
            let handleValue = (senderKey?.isEmpty == false) ? senderKey! : name
            let handle = INPersonHandle(value: handleValue, type: .unknown)
            let sender = INPerson(personHandle: handle,
                                  nameComponents: nil,
                                  displayName: name,
                                  image: avatar,
                                  contactIdentifier: nil,
                                  customIdentifier: senderKey)
            let intent = INSendMessageIntent(recipients: nil,
                                             outgoingMessageType: .outgoingMessageText,
                                             content: content.body,
                                             speakableGroupName: nil,
                                             conversationIdentifier: conversationId,
                                             serviceName: nil,
                                             sender: sender,
                                             attachments: nil)
            intent.setImage(avatar, forParameterNamed: \.sender)

            let interaction = INInteraction(intent: intent, response: nil)
            interaction.direction = .incoming
            interaction.donate(completion: nil)

            // A big picture rides along in both shapes; attach it before the copy is made.
            if !attachments.isEmpty { content.attachments = attachments }
            do {
                let updated = try content.updating(from: intent)
                bestAttempt = updated.mutableCopy() as? UNMutableNotificationContent ?? content
                handler(updated)
                return
            } catch {
                // The entitlement is missing or iOS refused the intent: shape 2 below.
            }
        }

        // Shape 2: the picture as the thumbnail.
        if let data = iconData, let file = Self.writeTemp(data, suggestedName: "sender.png", mime: "image/png"),
           let a = try? UNNotificationAttachment(identifier: "wizzopush-icon", url: file, options: nil) {
            attachments.insert(a, at: 0)
        }
        if !attachments.isEmpty { content.attachments = attachments }
        bestAttempt = content
        handler(content)
    }

    // MARK: - Helpers

    private static func string(_ v: Any?) -> String? {
        guard let v = v else { return nil }
        if let s = v as? String { return s.isEmpty ? nil : s }
        return String(describing: v)
    }

    private static func download(_ url: URL, _ done: @escaping (Data?, URLResponse?) -> Void) {
        let task = URLSession.shared.dataTask(with: url) { data, response, _ in
            if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
                done(nil, response)
                return
            }
            done(data, response)
        }
        task.resume()
    }

    /// UNNotificationAttachment needs a FILE with a recognised extension; the sender's
    /// picture arrives as bytes from a URL that may carry none.
    private static func writeTemp(_ data: Data, suggestedName: String, mime: String?) -> URL? {
        var ext = (suggestedName as NSString).pathExtension.lowercased()
        if !["png", "jpg", "jpeg", "gif", "webp"].contains(ext) {
            switch (mime ?? "").lowercased() {
            case "image/jpeg": ext = "jpg"
            case "image/gif": ext = "gif"
            case "image/webp": ext = "webp"
            default: ext = "png"
            }
        }
        let dir = FileManager.default.temporaryDirectory
        let file = dir.appendingPathComponent("wizzopush-\(UUID().uuidString).\(ext)")
        do {
            try data.write(to: file)
            return file
        } catch {
            return nil
        }
    }
}
