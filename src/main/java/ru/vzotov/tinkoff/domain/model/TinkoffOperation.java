package ru.vzotov.tinkoff.domain.model;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang.builder.EqualsBuilder;
import ru.vzotov.accounting.domain.model.AccountReportOperation;
import ru.vzotov.banking.domain.model.AccountNumber;
import ru.vzotov.ddd.shared.ValueObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record TinkoffOperation(AccountNumber accountNumber, LocalDateTime operationDate, LocalDate paymentDate,
                               String cardNumber, Double operationAmount, String operationCurrency,
                               Double paymentAmount, String paymentCurrency, Double cashBack, String category,
                               String mcc, String description,
                               Double bonus) implements ValueObject<TinkoffOperation>, AccountReportOperation {

    public TinkoffOperation(AccountNumber accountNumber, LocalDateTime operationDate, LocalDate paymentDate, String cardNumber, Double operationAmount, String operationCurrency, Double paymentAmount, String paymentCurrency, Double cashBack, String category, String mcc, String description, Double bonus) {
        Validate.notNull(operationDate);
        Validate.notNull(operationAmount);
        Validate.notNull(operationCurrency);
        Validate.notNull(paymentAmount);
        Validate.notNull(paymentCurrency);
        Validate.notNull(description);
        Validate.isTrue(mcc == null || !mcc.isEmpty());

        this.accountNumber = accountNumber;
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

    @Override
    public boolean sameValueAs(TinkoffOperation that) {
        return that != null && new EqualsBuilder().
                append(accountNumber, that.accountNumber).
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
    public String toString() {
        return "tinkoff{" +
                "AN=" + accountNumber +
                ", OD=" + operationDate +
                ", PD=" + paymentDate +
                ", CARD='" + cardNumber + '\'' +
                ", OA=" + operationAmount +
                ", OC='" + operationCurrency + '\'' +
                ", PA=" + paymentAmount +
                ", PC='" + paymentCurrency + '\'' +
                ", CB=" + cashBack +
                ", CAT='" + category + '\'' +
                ", MCC='" + mcc + '\'' +
                ", D='" + description + '\'' +
                ", B=" + bonus +
                '}';
    }
}
