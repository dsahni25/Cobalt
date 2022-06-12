package it.auties.whatsapp.model.message.payment;

import it.auties.protobuf.api.model.ProtobufProperty;
import it.auties.whatsapp.model.message.model.MessageContainer;
import it.auties.whatsapp.model.message.model.PaymentMessage;
import it.auties.whatsapp.model.payment.PaymentBackground;
import it.auties.whatsapp.model.payment.PaymentMoney;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.*;

/**
 * A model class that represents a message to try to place a {@link PaymentMessage}.
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@Jacksonized
@Builder(builderMethodName = "newRequestPaymentMessage", buildMethodName = "create")
@Accessors(fluent = true)
public final class RequestPaymentMessage implements PaymentMessage {
    /**
     * The currency code for {@link RequestPaymentMessage#amount}.
     * Follows the ISO-4217 Standard.
     * For a list of valid currency codes click <a href="https://en.wikipedia.org/wiki/ISO_4217">here</a>
     */
    @ProtobufProperty(index = 1, type = STRING)
    private String currency;

    /**
     * The amount of money being paid
     */
    @ProtobufProperty(index = 2, type = UINT64)
    private long amount1000;

    /**
     * The name of the Whatsapp business account that will receive the money
     */
    @ProtobufProperty(index = 3, type = STRING)
    private String requestFrom;

    /**
     * The caption message, that is the message below the payment request
     */
    @ProtobufProperty(index = 4, type = MESSAGE, concreteType = MessageContainer.class)
    private MessageContainer noteMessage;

    /**
     * The timestamp, that is the endTimeStamp in seconds since {@link java.time.Instant#EPOCH}, for the expiration of this payment request
     */
    @ProtobufProperty(index = 5, type = UINT64)
    private long expiryTimestamp;

    /**
     * The amount being paid
     */
    @ProtobufProperty(index = 6, type = MESSAGE, concreteType = PaymentMoney.class)
    private PaymentMoney amount;

    /**
     * The background of the payment
     */
    @ProtobufProperty(index = 7, type = MESSAGE, concreteType = PaymentBackground.class)
    private PaymentBackground background;
}
