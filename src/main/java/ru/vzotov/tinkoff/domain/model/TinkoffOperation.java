package ru.vzotov.tinkoff.domain.model;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang.builder.EqualsBuilder;
import ru.vzotov.accounting.domain.model.AccountReportOperation;
import ru.vzotov.ddd.shared.ValueObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public class TinkoffOperation implements ValueObject<TinkoffOperation>, AccountReportOperation {

    private final LocalDateTime operationDate;
    private final LocalDate paymentDate;
    private final String cardNumber;
    private final Double operationAmount;
    private final String operationCurrency;
    private final Double paymentAmount;
    private final String paymentCurrency;
    private final Double cashBack;
    private final String category;
    private final String mcc;
    private final String description;
    private final Double bonus;

    public TinkoffOperation(LocalDateTime operationDate, LocalDate paymentDate, String cardNumber, Double operationAmount, String operationCurrency, Double paymentAmount, String paymentCurrency, Double cashBack, String category, String mcc, String description, Double bonus) {
        Validate.notNull(operationDate);
        Validate.notNull(operationAmount);
        Validate.notNull(operationCurrency);
        Validate.notNull(paymentAmount);
        Validate.notNull(paymentCurrency);
        Validate.notNull(category);
        Validate.notNull(description);
        Validate.notNull(bonus);
        Validate.isTrue(mcc == null || !mcc.isEmpty());

        this.operationDate = operationDate;
        this.paymentDate = paymentDate;
        this.cardNumber = cardNumber;
        this.operationAmount = operationAmount;
        this.operationCurrency = operationCurrency;
        this.paymentAmount = paymentAmount;
        this.paymentCurrency = paymentCurrency;
        this.cashBack = cashBack;
        this.category = category;
        this.mcc = StringUtils.leftPad(mcc, 4, '0');
        this.description = description;
        this.bonus = bonus;
    }

    public boolean isHold() {
        return this.paymentDate == null;
    }

    public boolean isCardOperation() {
        return this.mcc != null && !(this.cardNumber == null || this.cardNumber.isEmpty());
    }

    public LocalDateTime operationDate() {
        return operationDate;
    }

    public LocalDate paymentDate() {
        return paymentDate;
    }

    public String cardNumber() {
        return cardNumber;
    }

    public Double operationAmount() {
        return operationAmount;
    }

    public String operationCurrency() {
        return operationCurrency;
    }

    public Double paymentAmount() {
        return paymentAmount;
    }

    public String paymentCurrency() {
        return paymentCurrency;
    }

    public Double cashBack() {
        return cashBack;
    }

    public String category() {
        return category;
    }

    public String mcc() {
        return mcc;
    }

    public String description() {
        return description;
    }

    public Double bonus() {
        return bonus;
    }

    @Override
    public boolean sameValueAs(TinkoffOperation that) {
        return that != null && new EqualsBuilder().
                append(operationDate, that.operationDate).
                append(paymentDate, that.paymentDate).
                append(cardNumber, that.cardNumber).
                append(operationAmount, that.operationAmount).
                append(operationCurrency, that.operationCurrency).
                append(paymentAmount, that.paymentAmount).
                append(paymentCurrency, that.paymentCurrency).
                append(cashBack, that.cashBack).
                append(category, that.category).
                append(mcc, that.mcc).
                append(description, that.description).
                append(bonus, that.bonus).
                isEquals();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TinkoffOperation that = (TinkoffOperation) o;
        return sameValueAs(that);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationDate
                , paymentDate
                , cardNumber
                , operationAmount
                , operationCurrency
                , paymentAmount
                , paymentCurrency
                , cashBack
                , category
                , mcc
                , description
                , bonus
        );
    }
}
