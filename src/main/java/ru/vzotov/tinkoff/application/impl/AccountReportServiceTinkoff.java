package ru.vzotov.tinkoff.application.impl;

import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import ru.vzotov.accounting.application.AccountNotFoundException;
import ru.vzotov.accounting.application.AccountReportNotFoundException;
import ru.vzotov.accounting.application.AccountReportService;
import ru.vzotov.accounting.application.AccountingService;
import ru.vzotov.accounting.domain.model.AccountReport;
import ru.vzotov.accounting.domain.model.AccountReportId;
import ru.vzotov.accounting.domain.model.AccountReportRepository;
import ru.vzotov.accounting.domain.model.AccountRepository;
import ru.vzotov.accounting.domain.model.CardRepository;
import ru.vzotov.banking.domain.model.Account;
import ru.vzotov.banking.domain.model.AccountNumber;
import ru.vzotov.banking.domain.model.BankId;
import ru.vzotov.banking.domain.model.Card;
import ru.vzotov.banking.domain.model.MccCode;
import ru.vzotov.banking.domain.model.OperationId;
import ru.vzotov.banking.domain.model.OperationType;
import ru.vzotov.banking.domain.model.TransactionReference;
import ru.vzotov.domain.model.Money;
import ru.vzotov.tinkoff.domain.model.TinkoffOperation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ru.vzotov.banking.domain.model.OperationType.DEPOSIT;
import static ru.vzotov.banking.domain.model.OperationType.WITHDRAW;

@Service
@Qualifier("AccountReportServiceTinkoff")
public class AccountReportServiceTinkoff implements AccountReportService {

    private static final Logger log = LoggerFactory.getLogger(AccountReportServiceTinkoff.class);

    private final AccountReportRepository<TinkoffOperation> accountReportRepository;

    private final AccountingService accountingService;

    private final AccountRepository accountRepository;

    private final CardRepository cardRepository;

    AccountReportServiceTinkoff(
            @Autowired @Qualifier("accountReportRepositoryTinkoff") AccountReportRepository<TinkoffOperation> accountReportRepository,
            @Autowired AccountingService accountingService,
            @Autowired AccountRepository accountRepository,
            @Autowired CardRepository cardRepository
    ) {
        this.accountReportRepository = accountReportRepository;
        this.accountingService = accountingService;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
    }

    @Override
    public BankId bankId() {
        return BankId.TINKOFF;
    }

    @Override
    public AccountReportId save(String name, InputStream content) throws IOException {
        Validate.notNull(name);
        Validate.notNull(content);
        return accountReportRepository.save(name, content);
    }

    @Override
    public void processAccountReport(AccountReportId reportId) throws AccountReportNotFoundException, AccountNotFoundException {
        Validate.notNull(reportId);

        final AccountReport<TinkoffOperation> report = accountReportRepository.find(reportId);
        if (report == null) {
            throw new AccountReportNotFoundException();
        }

        // Index of cards by their card number in report
        final Map<String, Card> cards = new HashMap<>();

        for (TinkoffOperation row : report.operations()) {
            final OperationType type = row.operationAmount() < 0d ? WITHDRAW : DEPOSIT;

            Card card = null;
            if (row.cardNumber() != null && !row.cardNumber().isEmpty()) {
                card = cards.get(row.cardNumber());
                if (card == null) {
                    List<Card> cardList = cardRepository.findByMask(row.cardNumber())
                            .stream()
                            .filter(c -> BankId.TINKOFF.equals(c.issuer()))
                            .collect(Collectors.toList());

                    if (cardList.isEmpty()) {
                        log.error("Unable to find card by mask {}", row.cardNumber());
                        return;
                    } else if (cardList.size() == 1) {
                        card = cardList.get(0);
                        cards.put(row.cardNumber(), card);
                    } else {
                        log.error("Multiple cards found by mask {}", row.cardNumber());
                        return;
                    }
                }
            }

            final Currency currency = Currency.getInstance(row.operationCurrency());

            final Account account;
            if (card == null) {
                account = accountRepository.find(BankId.TINKOFF, currency).stream()
                        .min(Comparator.comparing(a -> a.accountNumber().number()))
                        .orElse(null);
                if (account == null) {
                    log.error("Unable to find account for tinkoff and currency {}", currency);
                    return;
                }
            } else {
                account = accountRepository.findAccountOfCard(card.cardNumber(), row.operationDate().toLocalDate());
                if (account == null) {
                    log.error("Unable to find account for card {} and date {}", card.cardNumber(), row.operationDate());
                    return;
                }
            }

            final AccountNumber accountNumber = account.accountNumber();
            final Money amount = new Money(Math.abs(row.operationAmount()), currency);

            if (row.isHold()) { // Handle hold records
                accountingService.registerHoldOperation(
                        accountNumber,
                        row.operationDate().toLocalDate(),
                        type,
                        amount,
                        row.description()
                );
            } else {
                final String transactionId = DigestUtils.md5DigestAsHex(
                        (row.operationDate().toString() + "_" + row.cardNumber() + "_" + row.operationAmount().toString())
                                .getBytes(StandardCharsets.UTF_8)
                );

                OperationId operationId = accountingService.registerOperation(
                        accountNumber,
                        row.paymentDate(),
                        new TransactionReference(transactionId),
                        type,
                        amount,
                        row.description()
                );

                if (row.isCardOperation() && card != null) {
                    accountingService.registerCardOperation(
                            operationId,
                            card.cardNumber(),
                            null,
                            row.paymentDate(),
                            row.operationDate().toLocalDate(),
                            amount,
                            null,
                            new MccCode(row.mcc())
                    );
                }

                accountingService.removeMatchingHoldOperations(operationId);
            }
        }

        accountReportRepository.markProcessed(reportId);
    }

    @Override
    public void processNewReports() {
        List<AccountReportId> reports = accountReportRepository.findUnprocessed();

        log.info("Found {} unprocessed reports", reports.size());

        for (AccountReportId reportId : reports) {
            log.info("Start processing of report {}", reportId);
            try {
                processAccountReport(reportId);

                log.info("Processing of report {} finished", reportId);
            } catch (AccountReportNotFoundException | AccountNotFoundException e) {
                log.warn("Processing failed for report {}", reportId);
            }
        }

    }
}
