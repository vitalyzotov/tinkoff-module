package ru.vzotov.tinkoff.application.impl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import ru.vzotov.accounting.application.AccountNotFoundException;
import ru.vzotov.accounting.application.AccountReportNotFoundException;
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
import ru.vzotov.banking.domain.model.CardNumber;
import ru.vzotov.banking.domain.model.OperationId;
import ru.vzotov.banking.domain.model.OperationType;
import ru.vzotov.person.domain.model.PersonId;
import ru.vzotov.banking.domain.model.TransactionReference;
import ru.vzotov.domain.model.Money;
import ru.vzotov.tinkoff.domain.model.TinkoffOperation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class AccountReportServiceImplTest {

    private static final AccountNumber ACCOUNT_NUMBER = new AccountNumber("40817810000016123456");
    private static final CardNumber CARD_NUMBER = new CardNumber("5536913837701234");

    private AccountReportServiceTinkoff service;
    private AccountReportId reportId;
    private AccountReportRepository<TinkoffOperation> reportRepository;
    private AccountingService accountingService;
    private AccountRepository accountRepository;
    private CardRepository cardRepository;


    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        reportRepository = Mockito.mock(AccountReportRepository.class);
        accountingService = Mockito.mock(AccountingService.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        cardRepository = Mockito.mock(CardRepository.class);

        service = new AccountReportServiceTinkoff(reportRepository, accountingService, accountRepository, cardRepository);
        reportId = new AccountReportId("test-1", LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC));

        List<TinkoffOperation> operations = Arrays.asList(
                new TinkoffOperation(
                        LocalDateTime.of(2020, Month.FEBRUARY, 21, 20, 0, 31),
                        LocalDate.of(2020, Month.FEBRUARY, 21),
                        "*1234",
                        2000d,
                        "RUR",
                        2000d,
                        "RUR",
                        null,
                        "Финан. услуги",
                        "6012",
                        "Перевод с карты",
                        0d
                ),
                new TinkoffOperation(
                        LocalDateTime.of(2020, Month.MARCH, 9, 16, 26, 49),
                        LocalDate.of(2020, Month.MARCH, 11),
                        "*1234",
                        -809d,
                        "RUR",
                        -809d,
                        "RUR",
                        8d,
                        "Животные",
                        "742",
                        "Vitavet",
                        8d
                )
        );

        Mockito.when(reportRepository.find(reportId))
                .thenReturn(new AccountReport<>(reportId, operations));

        Mockito.when(accountingService.registerOperation(
                Mockito.any(AccountNumber.class),
                Mockito.any(LocalDate.class),
                Mockito.any(TransactionReference.class),
                Mockito.any(OperationType.class),
                Mockito.any(Money.class),
                Mockito.anyString()
        )).thenReturn(new OperationId("test-op-1"), new OperationId("test-op-2"));

        Mockito.when(cardRepository.findByMask("*1234"))
                .thenReturn(Collections.singletonList(
                        new Card(CARD_NUMBER, PersonId.nextId(), YearMonth.of(2024, Month.DECEMBER), BankId.TINKOFF)
                ));

        Mockito.when(accountRepository.findAccountOfCard(
                Mockito.eq(CARD_NUMBER), Mockito.any(LocalDate.class)
        )).thenReturn(new Account(ACCOUNT_NUMBER, new PersonId("vzotov")));
    }

    @Test
    public void processAccountReport() throws AccountReportNotFoundException, AccountNotFoundException {
        service.processAccountReport(new AccountReportId("test-1", LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC)));
        Mockito.verify(accountingService).registerOperation(
                ACCOUNT_NUMBER,
                LocalDate.of(2020, Month.FEBRUARY, 21),
                new TransactionReference("753fd3bc64c3c0d168d1d0e7b3618ab3"),
                OperationType.DEPOSIT,
                Money.kopecks(200000),
                "Перевод с карты"
        );
        Mockito.verify(accountingService).registerOperation(
                ACCOUNT_NUMBER,
                LocalDate.of(2020, Month.MARCH, 11),
                new TransactionReference("157e610c7b39e7abf8e726d5305743fe"),
                OperationType.WITHDRAW,
                Money.rubles(809d),
                "Vitavet"
        );
    }

}
