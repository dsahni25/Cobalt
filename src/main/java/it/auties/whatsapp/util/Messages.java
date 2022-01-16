package it.auties.whatsapp.util;

import it.auties.protobuf.decoder.ProtobufDecoder;
import it.auties.whatsapp.crypto.GroupCipher;
import it.auties.whatsapp.crypto.GroupSessionBuilder;
import it.auties.whatsapp.crypto.SessionCipher;
import it.auties.whatsapp.crypto.SignalHelper;
import it.auties.whatsapp.exchange.Node;
import it.auties.whatsapp.manager.WhatsappKeys;
import it.auties.whatsapp.manager.WhatsappStore;
import it.auties.whatsapp.protobuf.chat.Chat;
import it.auties.whatsapp.protobuf.contact.Contact;
import it.auties.whatsapp.protobuf.contact.ContactJid;
import it.auties.whatsapp.protobuf.info.MessageInfo;
import it.auties.whatsapp.protobuf.message.device.DeviceSentMessage;
import it.auties.whatsapp.protobuf.message.model.MediaMessage;
import it.auties.whatsapp.protobuf.message.model.MessageContainer;
import it.auties.whatsapp.protobuf.message.model.MessageKey;
import it.auties.whatsapp.protobuf.message.server.ProtocolMessage;
import it.auties.whatsapp.protobuf.message.server.SenderKeyDistributionMessage;
import it.auties.whatsapp.protobuf.signal.message.SignalDistributionMessage;
import it.auties.whatsapp.protobuf.signal.message.SignalMessage;
import it.auties.whatsapp.protobuf.signal.message.SignalPreKeyMessage;
import it.auties.whatsapp.protobuf.signal.sender.SenderKeyName;
import it.auties.whatsapp.protobuf.sync.HistorySync;
import it.auties.whatsapp.protobuf.sync.PushName;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import static java.util.Arrays.copyOfRange;

@UtilityClass
public class Messages {
    public List<MessageInfo> decodeMessages(Node node, WhatsappStore store, WhatsappKeys keys) {
        var timestamp = node.attributes().getLong("t");
        var id = node.attributes().getRequiredString("id");
        var from = node.attributes().getJid("from")
                .orElseThrow(() -> new NoSuchElementException("Missing from"));
        var recipient = node.attributes().getJid("recipient")
                .orElse(from);
        var participant = node.attributes().getJid("participant")
                .orElse(null);
        var messageBuilder = MessageInfo.newMessageInfo();
        var keyBuilder = MessageKey.newMessageKey();
        switch (from.type()){
            case USER, OFFICIAL_BUSINESS_ACCOUNT, STATUS, ANNOUNCEMENT -> {
                keyBuilder.chatJid(recipient);
                messageBuilder.senderJid(from);
            }

            case GROUP, GROUP_CALL, BROADCAST -> {
                var sender = Objects.requireNonNull(participant, "Missing participant in group message");
                keyBuilder.chatJid(from);
                messageBuilder.senderJid(sender);
            }

            default -> throw new IllegalArgumentException("Cannot decode message, unsupported type: %s".formatted(from.type().name()));
        }

        var key = keyBuilder.id(id).create();
        var info = messageBuilder.store(store)
                .key(key)
                .timestamp(timestamp)
                .create();

        return node.findNodes("enc")
                .stream()
                .map(messageNode -> decodeMessage(info, messageNode, from, store, keys))
                .toList();
    }

    private MessageInfo decodeMessage(MessageInfo info, Node node, ContactJid from, WhatsappStore store, WhatsappKeys keys) {
        var encodedMessage = node.bytes();
        var messageType = node.attributes().getString("type");
        var buffer = decodeCipheredMessage(info, encodedMessage, messageType, keys);
        info.message(decodeMessageContainer(buffer));
        handleSenderKeyMessage(store, keys, from, info);
        return info;
    }

    private byte[] decodeCipheredMessage(MessageInfo info, byte[] message, String type, WhatsappKeys keys) {
        return switch (type) {
            case "skmsg" -> {
                var senderName = new SenderKeyName(info.chatJid().toString(), info.senderJid().toSignalAddress());
                var signalGroup = new GroupCipher(senderName, keys);
                yield signalGroup.decrypt(message);
            }

            case "pkmsg" -> {
                var session = new SessionCipher(info.chatJid().toSignalAddress(), keys);
                var preKey = SignalPreKeyMessage.ofSerialized(message);
                yield session.decrypt(preKey);
            }

            case "msg" -> {
                var session = new SessionCipher(info.chatJid().toSignalAddress(), keys);
                var signalMessage = SignalMessage.ofSerialized(message);
                yield session.decrypt(signalMessage);
            }

            default -> throw new IllegalArgumentException("Unsupported encoded message type: %s".formatted(type));
        };
    }

    private MessageContainer decodeMessageContainer(byte[] buffer) {
        try {
            var bufferWithNoPadding = copyOfRange(buffer, 0, buffer.length - buffer[buffer.length - 1]);
            return ProtobufDecoder.forType(MessageContainer.class)
                    .decode(bufferWithNoPadding);
        }catch (IOException exception){
            throw new IllegalArgumentException("Cannot decode message", exception);
        }
    }

    @SneakyThrows
    private void handleSenderKeyMessage(WhatsappStore store, WhatsappKeys keys, ContactJid from, MessageInfo info) {
        switch (info.message().content()){
            case SenderKeyDistributionMessage distributionMessage -> handleDistributionMessage(keys, from, distributionMessage);
            case ProtocolMessage protocolMessage -> handleProtocolMessage(store, keys, info, protocolMessage);
            case DeviceSentMessage deviceSentMessage -> saveMessage(info.message(deviceSentMessage.message()));
            case MediaMessage mediaMessage -> {
                mediaMessage.store(store);
                saveMessage(info);
            }
            default -> saveMessage(info);
        }
    }

    private void saveMessage(MessageInfo info) {
        info.chat()
                .orElseThrow(() -> new NoSuchElementException("Missing chat: %s".formatted(info.chatJid())))
                .messages()
                .add(info);
    }

    private void handleDistributionMessage(WhatsappKeys keys, ContactJid from, SenderKeyDistributionMessage distributionMessage) {
        var groupName = new SenderKeyName(distributionMessage.groupId(), from.toSignalAddress());
        var builder = new GroupSessionBuilder(keys);
        var message = SignalDistributionMessage.ofSerialized(distributionMessage.data());
        builder.process(groupName, message);
    }

    @SneakyThrows
    private void handleProtocolMessage(WhatsappStore store, WhatsappKeys keys, MessageInfo info, ProtocolMessage protocolMessage){
        switch(protocolMessage.type()) {
            case HISTORY_SYNC_NOTIFICATION -> {
                var compressed = Medias.download(protocolMessage.historySyncNotification(), store);
                var decompressed = SignalHelper.deflate(compressed);
                var history = ProtobufDecoder.forType(HistorySync.class)
                        .decode(decompressed);
                    switch(history.syncType()) {
                        case FULL, INITIAL_BOOTSTRAP -> history.conversations()
                                .forEach(store::addChat);
                        case INITIAL_STATUS_V3 -> history.statusV3Messages()
                                .stream()
                                .peek(message -> message.store(store))
                                .forEach(status -> handleStatusMessage(store, status));
                        case RECENT -> history.conversations()
                                .forEach(recent -> handleRecentMessage(store, recent));
                        case PUSH_NAME -> history.pushNames()
                                .forEach(pushName -> handNewPushName(store, pushName));
                    }

                // Send receipt
            }

            case APP_STATE_SYNC_KEY_SHARE -> {
                var newKeys = protocolMessage.appStateSyncKeyShare()
                        .keys();
                keys.appStateKeys().addAll(newKeys);
                // Re-sync app state
            }

            case REVOKE -> {
                var chat = info.chat()
                        .orElseThrow(() -> new NoSuchElementException("Missing chat"));
                var message = store.findMessageById(chat, protocolMessage.key().id())
                        .orElseThrow(() -> new NoSuchElementException("Missing message"));
                chat.messages().add(message);
                store.callListeners(listener -> listener.onMessageDeleted(message, true));
            }

            case EPHEMERAL_SETTING -> {
                var chat = info.chat()
                        .orElseThrow(() -> new NoSuchElementException("Missing chat"));
                chat.ephemeralMessagesToggleTime(info.timestamp())
                        .ephemeralMessageDuration(protocolMessage.ephemeralExpiration());
                store.callListeners(listener -> listener.onChatEphemeralStatusChange(chat));
            }
        }
    }

    private void handleStatusMessage(WhatsappStore store, MessageInfo status) {
        var chat = status.chat()
                .orElseGet(() -> createStatusChat(status.chatJid(), store));
        chat.messages().add(status);
    }

    private Chat createStatusChat(ContactJid jid, WhatsappStore store) {
        var chat = Chat.builder()
                .name("Official Whatsapp Status")
                .jid(jid)
                .build();
        store.addChat(chat);
        return chat;
    }

    private void handNewPushName(WhatsappStore store, PushName pushName) {
        var oldContact = store.findContactByJid(pushName.id()).orElseGet(() -> {
            var jid = ContactJid.ofUser(pushName.id());
            var newContact = Contact.ofJid(jid);
            store.addContact(newContact);
            return newContact;
        });

        oldContact.chosenName(pushName.pushname());
    }

    private void handleRecentMessage(WhatsappStore store, Chat recent) {
        var oldChat = store.findChatByJid(recent.jid().toString());
        if(oldChat.isEmpty()){
            return;
        }

        recent.messages()
                .stream()
                .peek(message -> message.store(store))
                .forEach(oldChat.get().messages()::add);
    }
}