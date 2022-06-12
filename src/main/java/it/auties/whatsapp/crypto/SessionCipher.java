package it.auties.whatsapp.crypto;

import it.auties.bytes.Bytes;
import it.auties.whatsapp.controller.WhatsappKeys;
import it.auties.whatsapp.model.request.Node;
import it.auties.whatsapp.model.signal.keypair.SignalKeyPair;
import it.auties.whatsapp.model.signal.message.SignalMessage;
import it.auties.whatsapp.model.signal.message.SignalPreKeyMessage;
import it.auties.whatsapp.model.signal.session.Session;
import it.auties.whatsapp.model.signal.session.SessionAddress;
import it.auties.whatsapp.model.signal.session.SessionChain;
import it.auties.whatsapp.model.signal.session.SessionState;
import it.auties.whatsapp.util.*;
import lombok.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import static it.auties.curve25519.Curve25519.sharedKey;
import static it.auties.whatsapp.model.request.Node.with;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;

public record SessionCipher(@NonNull SessionAddress address, @NonNull WhatsappKeys keys)
        implements SignalSpecification {
    public Node encrypt(byte @NonNull [] data) {
        return CipherScheduler.run(() -> {
            var currentState = loadSession().currentState();
            Validate.isTrue(keys.hasTrust(address, currentState.remoteIdentityKey()), "Untrusted key",
                    SecurityException.class);

            var chain = currentState.findChain(currentState.ephemeralKeyPair()
                            .encodedPublicKey())
                    .orElseThrow(() -> new NoSuchElementException("Missing chain for %s".formatted(address)));
            fillMessageKeys(chain, chain.counter() + 1);

            var currentKey = chain.messageKeys()
                    .get(chain.counter());
            var secrets = Hkdf.deriveSecrets(currentKey, "WhisperMessageKeys".getBytes(StandardCharsets.UTF_8));
            chain.messageKeys()
                    .remove(chain.counter());

            var iv = Bytes.of(secrets[2])
                    .cut(IV_LENGTH)
                    .toByteArray();
            var encrypted = AesCbc.encrypt(iv, data, secrets[0]);

            var encryptedMessageType = getMessageType(currentState);
            var encryptedMessage = encrypt(currentState, chain, secrets[1], encrypted);
            return with("enc", of("v", "2", "type", encryptedMessageType), encryptedMessage);
        });
    }

    private String getMessageType(SessionState currentState) {
        return currentState.hasPreKey() ?
                "pkmsg" :
                "msg";
    }

    private byte[] encrypt(SessionState state, SessionChain chain, byte[] key, byte[] encrypted) {
        var message = new SignalMessage(state.ephemeralKeyPair()
                .encodedPublicKey(), chain.counter(), state.previousCounter(), encrypted,
                encodedMessage -> createMessageSignature(state, key, encodedMessage));

        var serializedMessage = message.serialized();
        if (!state.hasPreKey()) {
            return serializedMessage;
        }

        var preKeyMessage = new SignalPreKeyMessage(state.pendingPreKey()
                .preKeyId(), state.pendingPreKey()
                .baseKey(), keys.identityKeyPair()
                .encodedPublicKey(), serializedMessage, keys.id(), state.pendingPreKey()
                .signedKeyId());

        return preKeyMessage.serialized();
    }

    private byte[] createMessageSignature(SessionState state, byte[] key, byte[] encodedMessage) {
        var macInput = Bytes.of(keys.identityKeyPair()
                        .encodedPublicKey())
                .append(state.remoteIdentityKey())
                .append(encodedMessage)
                .assertSize(encodedMessage.length + 33 + 33)
                .toByteArray();
        return Bytes.of(Hmac.calculateSha256(macInput, key))
                .cut(MAC_LENGTH)
                .toByteArray();
    }

    private void fillMessageKeys(SessionChain chain, int counter) {
        if (chain.counter() >= counter) {
            return;
        }

        Validate.isTrue(counter - chain.counter() <= 2000, "Message overflow: expected <= 2000, got %s",
                counter - chain.counter());
        Validate.isTrue(chain.key() != null, "Closed chain");

        var messagesHmac = Hmac.calculateSha256(new byte[]{1}, chain.key());
        chain.messageKeys()
                .put(chain.counter() + 1, messagesHmac);

        var keyHmac = Hmac.calculateSha256(new byte[]{2}, chain.key());
        chain.key(keyHmac);

        chain.incrementCounter();
        fillMessageKeys(chain, counter);
    }

    public byte[] decrypt(SignalPreKeyMessage message) {
        return CipherScheduler.run(() -> {
            var session = loadSession(() -> createSession(message));
            var builder = new SessionBuilder(address, keys);
            builder.createIncoming(session, message);
            var state = session.findState(message.version(), message.baseKey())
                    .orElseThrow(() -> new NoSuchElementException("Missing state"));
            return decrypt(message.signalMessage(), state);
        });
    }

    private Session createSession(SignalPreKeyMessage message) {
        Validate.isTrue(message.registrationId() != 0, "Missing registration jid");
        var newSession = new Session();
        keys.addSession(address, newSession);
        return newSession;
    }

    public byte[] decrypt(SignalMessage message) {
        return CipherScheduler.run(() -> {
            var session = loadSession();
            var errors = new ArrayList<Throwable>();
            for (var state : session.states()) {
                var result = tryDecrypt(message, state);
                if (result.data() != null) {
                    return result.data();
                }

                errors.add(result.throwable());
            }

            throw Exceptions.make(new NoSuchElementException("Cannot decrypt message: no suitable session found"),
                    errors);
        });
    }

    private DecryptionResult tryDecrypt(SignalMessage message, SessionState state) {
        try {
            Validate.isTrue(keys.hasTrust(address, state.remoteIdentityKey()), "Untrusted key");
            var result = decrypt(message, state);
            return new DecryptionResult(result, null);
        } catch (Throwable throwable) {
            return new DecryptionResult(null, throwable);
        }
    }

    private byte[] decrypt(SignalMessage message, SessionState state) {
        maybeStepRatchet(message, state);

        var chain = state.findChain(message.ephemeralPublicKey())
                .orElseThrow(() -> new NoSuchElementException("Invalid chain"));
        fillMessageKeys(chain, message.counter());

        Validate.isTrue(chain.hasMessageKey(message.counter()), "Key used already or never filled");
        var messageKey = chain.messageKeys()
                .get(message.counter());
        chain.messageKeys()
                .remove(message.counter());

        var secrets = Hkdf.deriveSecrets(messageKey, "WhisperMessageKeys".getBytes(StandardCharsets.UTF_8));

        var hmacInput = Bytes.of(state.remoteIdentityKey())
                .append(keys.identityKeyPair()
                        .encodedPublicKey())
                .append(message.serialized())
                .cut(-MAC_LENGTH)
                .toByteArray();
        var hmac = Bytes.of(Hmac.calculateSha256(hmacInput, secrets[1]))
                .cut(MAC_LENGTH)
                .toByteArray();
        Validate.isTrue(Arrays.equals(message.signature(), hmac), "Cannot decode message: Hmac validation failed",
                SecurityException.class);

        var iv = Bytes.of(secrets[2])
                .cut(IV_LENGTH)
                .toByteArray();
        var plaintext = AesCbc.decrypt(iv, message.ciphertext(), secrets[0]);
        state.pendingPreKey(null);
        return plaintext;
    }

    private void maybeStepRatchet(SignalMessage message, SessionState state) {
        if (state.hasChain(message.ephemeralPublicKey())) {
            return;
        }

        var previousRatchet = state.findChain(state.lastRemoteEphemeralKey());
        previousRatchet.ifPresent(chain -> {
            fillMessageKeys(chain, state.previousCounter());
            chain.key(null);
        });

        calculateRatchet(message, state, false);
        var previousCounter = state.findChain(state.ephemeralKeyPair()
                .encodedPublicKey());
        previousCounter.ifPresent(chain -> {
            state.previousCounter(chain.counter());
            state.removeChain(state.ephemeralKeyPair()
                    .encodedPublicKey());
        });

        state.ephemeralKeyPair(SignalKeyPair.random());
        calculateRatchet(message, state, true);
        state.lastRemoteEphemeralKey(message.ephemeralPublicKey());
    }

    private void calculateRatchet(SignalMessage message, SessionState state, boolean sending) {
        var sharedSecret = sharedKey(KeyHelper.withoutHeader(message.ephemeralPublicKey()), state.ephemeralKeyPair()
                .privateKey());
        var masterKey = Hkdf.deriveSecrets(sharedSecret, state.rootKey(),
                "WhisperRatchet".getBytes(StandardCharsets.UTF_8), 2);
        var chainKey = sending ?
                state.ephemeralKeyPair()
                        .encodedPublicKey() :
                message.ephemeralPublicKey();
        state.addChain(chainKey, new SessionChain(-1, masterKey[1]));
        state.rootKey(masterKey[0]);
    }

    private Session loadSession() {
        return loadSession(() -> null);
    }

    private Session loadSession(Supplier<Session> defaultSupplier) {
        return keys.findSessionByAddress(address)
                .orElseGet(() -> requireNonNull(defaultSupplier.get(),
                        "Missing session for %s. Known sessions: %s".formatted(address, keys.sessions())));
    }

    private record DecryptionResult(byte[] data, Throwable throwable) {

    }
}