package ru.vzotov.tinkoff.application.impl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import ru.vzotov.accounting.application.AccountNotFoundException;
import ru.vzotov.accounting.application.AccountReportNotFoundException;
import ru.vzotov.accounting.application.AccountingService;
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
import ru.vzotov.banking.domain.model.TransactionReference;
import ru.vzotov.domain.model.Money;
import ru.vzotov.person.domain.model.PersonId;
import ru.vzotov.tinkoff.domain.model.TinkoffOperation;
import ru.vzotov.tinkoff.infrastructure.fs.TinkoffReportRepositoryFiles;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.UUID;

import static ru.vzotov.tinkoff.infrastructure.fs.TinkoffReportRepositoryFiles.TINKOFF_TZ;

@RunWith(JUnit4.class)
public class OfxReportServiceImplTest {

    private static final File BASEDIR = new File("src/test/resources/account-reports");
    private static final AccountNumber ACCOUNT_NUMBER = new AccountNumber("40817810000016123456");
    private static final Account ACCOUNT = new Account(ACCOUNT_NUMBER, new PersonId("vzotov"));
    private static final CardNumber CARD_NUMBER = new CardNumber("5536913837701234");

    private AccountReportServiceTinkoff service;
    private AccountReportRepository<TinkoffOperation> reportRepository;
    private AccountingService accountingService;
    private AccountRepository accountRepository;
    private CardRepository cardRepository;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        reportRepository = new TinkoffReportRepositoryFiles(BASEDIR.getAbsolutePath(), true);
        accountingService = Mockito.mock(AccountingService.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        cardRepository = Mockito.mock(CardRepository.class);

        service = new AccountReportServiceTinkoff(reportRepository, accountingService, accountRepository, cardRepository);

        Mockito.when(accountingService.registerOperation(
                Mockito.any(AccountNumber.class),
                Mockito.any(LocalDate.class),
                Mockito.any(TransactionReference.class),
                Mockito.any(OperationType.class),
                Mockito.any(Money.class),
                Mockito.anyString()
        )).thenAnswer(i -> new OperationId(UUID.randomUUID().toString()));

        Mockito.when(cardRepository.findByMask("*1234"))
                .thenReturn(Collections.singletonList(
                        new Card(CARD_NUMBER, PersonId.nextId(), YearMonth.of(2024, Month.DECEMBER), BankId.TINKOFF)
                ));

        Mockito.when(accountRepository.findAccountOfCard(
                Mockito.eq(CARD_NUMBER), Mockito.any(LocalDate.class)
        )).thenReturn(ACCOUNT);
        Mockito.when(accountRepository.find(Mockito.eq(ACCOUNT_NUMBER))).thenReturn(ACCOUNT);
    }


    @Test
    public void processOfxReport() throws AccountReportNotFoundException, AccountNotFoundException {
        final String name = "report_1.ofx";
        service.processAccountReport(new AccountReportId(name, Instant.now()));
        Mockito.verify(accountingService, Mockito.times(9))
                .registerOperation(
                        Mockito.eq(ACCOUNT_NUMBER),
                        Mockito.any(LocalDate.class),
                        Mockito.any(TransactionReference.class),
                        Mockito.any(OperationType.class),
                        Mockito.any(Money.class),
                        Mockito.anyString()
                );
        Mockito.verify(accountingService)
                .registerOperation(
                        Mockito.eq(ACCOUNT_NUMBER),
                        Mockito.eq(
                                OffsetDateTime.of(2023, 3, 24, 23, 10, 10, 0,
                                                ZoneOffset.ofHoursMinutes(3, 0)).toInstant()
                                        .atZone(TINKOFF_TZ).toLocalDate()
                        ),
                        Mockito.any(TransactionReference.class),
                        Mockito.eq(OperationType.DEPOSIT),
                        Mockito.eq(Money.rubles(20.0)),
                        Mockito.eq("43319285777 Кэшбэк за обычные покупки")
                );
    }

}
