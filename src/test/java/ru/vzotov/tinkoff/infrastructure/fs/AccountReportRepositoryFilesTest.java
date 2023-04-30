package ru.vzotov.tinkoff.infrastructure.fs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vzotov.accounting.domain.model.AccountReport;
import ru.vzotov.accounting.domain.model.AccountReportId;
import ru.vzotov.banking.domain.model.MccCode;
import ru.vzotov.domain.model.Money;
import ru.vzotov.tinkoff.domain.model.TinkoffOperation;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(JUnit4.class)
public class AccountReportRepositoryFilesTest {

    public static final File BASEDIR = new File("src/test/resources/account-reports");
    private static final Logger log = LoggerFactory.getLogger(AccountReportRepositoryFilesTest.class);

    @Test
    public void find() {
        TinkoffReportRepositoryFiles repo = new TinkoffReportRepositoryFiles(BASEDIR.getAbsolutePath(), true);
        List<AccountReportId> all = repo.findAll();
        List<AccountReport<TinkoffOperation>> reports = new ArrayList<>();
        for (AccountReportId id : all) {
            reports.add(repo.find(id));
        }
        assertThat(reports).hasSize(5);
        List<TinkoffOperation> operations = reports.get(1).operations();
        assertThat(operations).hasSize(22);

        TinkoffOperation operation = operations.get(0);
        assertThat(operation.operationDate()).isEqualTo(LocalDateTime.of(2020, 2, 26, 16, 40, 22));
        assertThat(operation.operationAmount()).isEqualTo(-113.50d);

        operations = reports.get(1).operations();
        for (TinkoffOperation row : operations) {
            if (row.isCardOperation()) {
                log.info("Try to process operation {}", row);
                assertThat(row.cardNumber()).isNotEmpty();
                assertThat(row.mcc()).isNotNull();
                row.operationDate().toLocalDate();
                new Money(row.operationAmount(), Currency.getInstance(row.operationCurrency()));
                new MccCode(row.mcc());
            }
        }
    }

    @Test
    public void parseOfx() {
        final TinkoffReportRepositoryFiles repo = new TinkoffReportRepositoryFiles(BASEDIR.getAbsolutePath(), true);
        final String name = "report_1.ofx";
        repo.parseOFX(new AccountReportId(name, Instant.now()), new File(BASEDIR, name));
    }

    @Test
    public void compareOfxAndCsv() {
        final TinkoffReportRepositoryFiles repo = new TinkoffReportRepositoryFiles(BASEDIR.getAbsolutePath(), true);
        final String ofxName = "report_1.ofx";
        final String csvName = "report_2.csv";
        final AccountReport<TinkoffOperation> csvOperations = repo.parseCSV(new AccountReportId(csvName, Instant.now()), new File(BASEDIR, csvName));
        final AccountReport<TinkoffOperation> ofxOperations = repo.parseOFX(new AccountReportId(ofxName, Instant.now()), new File(BASEDIR, ofxName));
        assertThat(ofxOperations.operations())
                .usingElementComparatorIgnoringFields("accountNumber", "mcc", "description", "bonus", "cashBack")
                .hasSameElementsAs(csvOperations.operations());
    }

}
