package it.auties.whatsapp.manager;

import it.auties.whatsapp.api.WhatsappListener;
import it.auties.whatsapp.exchange.Node;
import it.auties.whatsapp.exchange.Request;
import it.auties.whatsapp.protobuf.chat.Chat;
import it.auties.whatsapp.protobuf.contact.Contact;
import it.auties.whatsapp.protobuf.info.MessageInfo;
import it.auties.whatsapp.protobuf.media.MediaConnection;
import it.auties.whatsapp.util.WhatsappUtils;
import lombok.*;
import lombok.experimental.Accessors;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * This class holds all the data regarding a session with WhatsappWeb's WebSocket.
 * It also provides various methods to query this data.
 * It should not be used by multiple sessions as, being a singleton, it cannot determine and divide data coming from different sessions.
 * It should not be initialized manually.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Data
@Accessors(fluent = true)
public class WhatsappStore {
    /**
     * The non-null service used to call listeners.
     * This is needed in order to not block the socket.
     */
    @NonNull
    private final ExecutorService requestsService;

    /**
     * The non-null list of chats
     */
    @NonNull
    private final List<Chat> chats;

    /**
     * The non-null list of contacts
     */
    @NonNull
    private final List<Contact> contacts;

    /**
     * The non-null list of requests that are waiting for a response from Whatsapp
     */
    @NonNull
    private final List<Request> pendingRequests;

    /**
     * The non-null list of listeners
     */
    @NonNull
    private final List<WhatsappListener> listeners;

    /**
     * The non-null read counter
     */
    @NonNull
    private final AtomicLong readCounter;

    /**
     * The non-null write counter
     */
    @NonNull
    private final AtomicLong writeCounter;

    /**
     * The timestamp in milliseconds for the initialization of this object
     */
    private final long initializationTimeStamp;

    /**
     * The media connection associated with this store
     */
    @Getter(onMethod = @__(@NonNull))
    private MediaConnection mediaConnection;

    /**
     * Constructs a new default instance of WhatsappDataManager
     */
    public WhatsappStore(){
        this(Executors.newSingleThreadExecutor(),
                new Vector<>(), new Vector<>(),
                new Vector<>(), new Vector<>(),
                new AtomicLong(), new AtomicLong(),
                System.currentTimeMillis());
    }

    /**
     * Queries the first contact whose jid is equal to {@code jid}
     *
     * @param jid the jid to search
     * @return a non-empty Optional containing the first result if any is found otherwise an empty Optional empty
     */
    public @NonNull Optional<Contact> findContactByJid(@NonNull String jid) {
        return contacts.parallelStream()
                .filter(contact -> Objects.equals(contact.jid().toString(), jid))
                .findAny();
    }

    /**
     * Queries the first contact whose name is equal to {@code name}
     *
     * @param name the name to search
     * @return a non-empty Optional containing the first result if any is found otherwise an empty Optional empty
     */
    public @NonNull Optional<Contact> findContactByName(@NonNull String name) {
        return contacts.parallelStream()
                .filter(contact -> Objects.equals(contact.bestName(null), name))
                .findAny();
    }

    /**
     * Queries every contact whose name is equal to {@code name}
     *
     * @param name the name to search
     * @return a Set containing every result
     */
    public @NonNull Set<Contact> findContactsByName(@NonNull String name) {
        return contacts.parallelStream()
                .filter(contact -> Objects.equals(contact.bestName(null), name))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Queries the first chat whose jid is equal to {@code jid}
     *
     * @param jid the jid to search
     * @return a non-empty Optional containing the first result if any is found otherwise an empty Optional empty
     */
    public @NonNull Optional<Chat> findChatByJid(@NonNull String jid) {
        return chats.parallelStream()
                .filter(chat -> Objects.equals(chat.jid().toString(), jid))
                .findAny();
    }

    /**
     * Queries the message in {@code chat} whose jid is equal to {@code jid}
     *
     * @param chat the chat to search in
     * @param id   the jid to search
     * @return a non-empty Optional containing the result if it is found otherwise an empty Optional empty
     */
    public @NonNull Optional<MessageInfo> findMessageById(@NonNull Chat chat, @NonNull String id) {
        return chat.messages()
                .parallelStream()
                .filter(message -> Objects.equals(message.key().id(), id))
                .findAny();
    }

    /**
     * Queries the chat associated with {@code message}
     *
     * @param message the message to use as context
     * @return a non-empty Optional containing the result if it is found otherwise an empty Optional empty
     */
    public @NonNull Optional<Chat> findChatByMessage(@NonNull MessageInfo message) {
        return findChatByJid(message.key().chatJid().toString());
    }

    /**
     * Queries the first chat whose name is equal to {@code name}
     *
     * @param name the name to search
     * @return a non-empty Optional containing the first result if any is found otherwise an empty Optional empty
     */
    public @NonNull Optional<Chat> findChatByName(@NonNull String name) {
        return chats.parallelStream()
                .filter(chat -> Objects.equals(chat.name(), name))
                .findAny();
    }

    /**
     * Queries every chat whose name is equal to {@code name}
     *
     * @param name the name to search
     * @return a Set containing every result
     */
    public @NonNull Set<Chat> findChatsByName(@NonNull String name) {
        return chats.parallelStream()
                .filter(chat -> Objects.equals(chat.name(), name))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Queries the first request whose id is equal to {@code id}
     *
     * @param id the id to search, can be null
     * @return a non-empty Optional containing the first result if any is found otherwise an empty Optional empty
     */
    public @NonNull Optional<Request> findPendingRequest(String id) {
        return id == null ? Optional.empty() : pendingRequests.parallelStream()
                .filter(request -> Objects.equals(request.id(), id))
                .findAny();
    }

    /**
     * Queries the first request whose id equals the one stored by the response and, if any is found, it completes it
     *
     * @param response the response to complete the request with
     * @return true if any request matching {@code response} is found
     */
    public boolean resolvePendingRequest(@NonNull Node response, boolean exceptionally) {
        return findPendingRequest(WhatsappUtils.readNullableId(response))
                .map(request -> deleteAndComplete(response, request, exceptionally))
                .isPresent();
    }

    /**
     * Adds a chat in memory
     *
     * @param chat the chat to add
     * @return the input chat
     */
    public @NonNull Chat addChat(@NonNull Chat chat) {
        chat.messages().forEach(message -> message.store(this));
        chats.add(chat);
        return chat;
    }

    /**
     * Adds a contact in memory
     *
     * @param contact the contact to add
     * @return the input contact
     */
    public @NonNull Contact addContact(@NonNull Contact contact) {
        contacts.add(contact);
        return contact;
    }

    /**
     * Returns the chats pinned to the top
     *
     * @return a non null list of chats
     */
    public List<Chat> pinnedChats(){
        return chats.parallelStream()
                .filter(Chat::isPinned)
                .toList();
    }

    /**
     * Resets to zero both the read and write counter
     */
    public void clearCounters(){
        this.readCounter.set(0);
        this.writeCounter.set(0);
    }

    /**
     * Clears all the data that this object holds
     */
    public void clear() {
        chats.clear();
        contacts.clear();
        pendingRequests.clear();
        clearCounters();
    }

    /**
     * Executes an operation on every registered listener on the listener thread
     * This should be used to be sure that when a listener should be called it's called on a thread that is not the WebSocket's.
     * If this condition isn't met, if the thread is put on hold to wait for a response for a pending request, the WebSocket will freeze.
     *
     * @param consumer the operation to execute
     */
    public void callListeners(@NonNull Consumer<WhatsappListener> consumer){
        listeners.forEach(listener ->
                requestsService.execute(() -> consumer.accept(listener)));
    }

    private Request deleteAndComplete(Node response, Request request, boolean exceptionally) {
        pendingRequests.remove(request);
        request.complete(response, exceptionally);
        return request;
    }
}
