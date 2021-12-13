package ru.vzotov.tinkoff.infrastructure.fs;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vzotov.accounting.domain.model.AccountReport;
import ru.vzotov.accounting.domain.model.AccountReportId;
import ru.vzotov.accounting.domain.model.AccountReportRepository;
import ru.vzotov.tinkoff.domain.model.TinkoffOperation;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static java.time.temporal.ChronoField.YEAR;

public class TinkoffReportRepositoryFiles implements AccountReportRepository<TinkoffOperation> {

    private static final Logger log = LoggerFactory.getLogger(AccountReportRepository.class);

    private static final String REPORT_EXT = ".csv";
    private static final String REPORT_PROCESSED_EXT = "_processed.csv";
    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .appendValue(DAY_OF_MONTH, 2)
            .appendLiteral('.')
            .appendValue(MONTH_OF_YEAR, 2)
            .appendLiteral('.')
            .appendValue(YEAR, 4)
            .toFormatter();

    private static final DateTimeFormatter DATETIME_FORMAT = new DateTimeFormatterBuilder()
            .appendValue(DAY_OF_MONTH, 2)
            .appendLiteral('.')
            .appendValue(MONTH_OF_YEAR, 2)
            .appendLiteral('.')
            .appendValue(YEAR, 4)
            .appendLiteral(' ')
            .appendValue(HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(SECOND_OF_MINUTE, 2)
            .toFormatter();

    private static final Function<File, AccountReportId> MAPPER = file -> {
        try {
            BasicFileAttributes basicFileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);

            return new AccountReportId(file.getName(), basicFileAttributes.creationTime().toInstant());
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    };

    private final String baseDirectoryPath;

    private final File baseDirectory;

    public TinkoffReportRepositoryFiles(String baseDirectoryPath) {
        this.baseDirectoryPath = baseDirectoryPath;
        this.baseDirectory = new File(baseDirectoryPath);
        log.info("Check base directory permissions {}", this.baseDirectory.getAbsolutePath());
        Validate.isTrue(this.baseDirectory.isDirectory());
        Validate.isTrue(this.baseDirectory.canRead());
    }

    protected String getBaseDirectoryPath() {
        return baseDirectoryPath;
    }

    protected File getBaseDirectory() {
        return baseDirectory;
    }

    @Override
    public AccountReport<TinkoffOperation> find(AccountReportId reportId) {
        Validate.notNull(reportId);
        final File reportFile = new File(this.getBaseDirectory(), reportId.name());
        Validate.isTrue(reportFile.exists() && reportFile.canRead());


        try (Reader in = new InputStreamReader(new FileInputStream(reportFile), Charset.forName("Cp1251"))) {
            final CSVFormat csvFormat = CSVFormat.Builder.create(CSVFormat.Predefined.Default.getFormat())
                    .setDelimiter(';')
                    .setTrailingDelimiter(false)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build();

            Iterable<CSVRecord> records = csvFormat.parse(in);
            final List<TinkoffOperation> operations = StreamSupport.stream(records.spliterator(), false).map(record -> {
                final String status = record.get("Статус");
                if ("FAILED".equalsIgnoreCase(status)) return null;

                final DecimalFormat decimals = createDecimalFormat(new Locale("ru"));
                final LocalDateTime operationDateTime = LocalDateTime.parse(record.get("Дата операции"), DATETIME_FORMAT);
                final LocalDate paymentDate = parseDateOrNull(record.get("Дата платежа"));
                final String cardNumber = record.get("Номер карты");
                final double operationAmount = parseDoubleOrNull(record.get("Сумма операции"), decimals);
                final String operationCurrency = mapCurrency(record.get("Валюта операции"));
                final double paymentAmount = parseDoubleOrNull(record.get("Сумма платежа"), decimals);
                final String paymentCurrency = mapCurrency(record.get("Валюта платежа"));
                final Double cashBack = parseDoubleOrNull(record.get("Кэшбэк"), decimals);
                final String category = record.get("Категория");
                final String mcc = record.get("MCC");
                final String description = record.get("Описание");
                final double bonus = parseDoubleOrNull(record.get("Бонусы (включая кэшбэк)"), decimals);

                return new TinkoffOperation(
                        operationDateTime,
                        paymentDate,
                        cardNumber,
                        operationAmount,
                        operationCurrency,
                        paymentAmount,
                        paymentCurrency,
                        cashBack,
                        category,
                        (mcc == null || mcc.isEmpty()) ? null : mcc,
                        description,
                        bonus
                );
            }).filter(Objects::nonNull).collect(Collectors.toList());

            return new AccountReport<>(reportId, operations);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Double parseDoubleOrNull(String doubleString, DecimalFormat decimals) {
        if (doubleString == null || doubleString.isEmpty()) {
            return null;
        } else {
            try {
                return decimals.parse(doubleString).doubleValue();
            } catch (ParseException e) {
                return null;
            }
        }
    }

    static LocalDate parseDateOrNull(String dateString) {
        LocalDate result = null;
        if (dateString != null && !dateString.isEmpty()) {
            try {
                result = LocalDate.parse(dateString, DATE_FORMAT);
            } catch (DateTimeParseException ignore) {
            }
        }
        return result;
    }

    static String mapCurrency(String currency) {
        if ("RUB".equalsIgnoreCase(currency)) {
            return "RUR";
        } else {
            return currency;
        }
    }

    private static DecimalFormat createDecimalFormat(Locale locale) {
        return new DecimalFormat("###.##", DecimalFormatSymbols.getInstance(locale));
    }

    @Override
    public List<AccountReportId> findAll() {
        final FileFilter filter = pathname -> pathname.getName().endsWith(REPORT_EXT);

        return Arrays.stream(Objects.requireNonNull(this.getBaseDirectory().listFiles(filter)))
                .sorted(Comparator.naturalOrder())
                .map(MAPPER)
                .collect(Collectors.toList());

    }

    @Override
    public List<AccountReportId> findUnprocessed() {
        final FileFilter filter = pathname -> pathname.getName().toLowerCase().endsWith(REPORT_EXT)
                && !pathname.getName().toLowerCase().endsWith(REPORT_PROCESSED_EXT);

        return Arrays.stream(Objects.requireNonNull(this.getBaseDirectory().listFiles(filter)))
                .sorted(Comparator.naturalOrder())
                .map(MAPPER)
                .collect(Collectors.toList());
    }

    @Override
    public void markProcessed(AccountReportId reportId) {
        Validate.notNull(reportId);

        final File reportFile = new File(this.getBaseDirectory(), reportId.name());

        final String baseName = FilenameUtils.removeExtension(reportId.name());

        final File processedReportFile = new File(this.getBaseDirectory(), baseName + REPORT_PROCESSED_EXT);

        Validate.isTrue(reportFile.exists() && reportFile.canRead() && reportFile.canWrite());
        Validate.isTrue(!processedReportFile.exists());

        try {
            FileUtils.moveFile(reportFile, processedReportFile);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to mark processed", e);
        }
    }

    @Override
    public AccountReportId save(String name, InputStream content) throws IOException {
        Validate.notEmpty(name);
        Validate.notNull(content);
        Validate.isTrue(name.endsWith(REPORT_EXT), "Invalid name of report: ", name);
        Validate.isTrue(!name.endsWith(REPORT_PROCESSED_EXT), "Saving already processed reports is not allowed:", name);

        final File reportFile = new File(this.getBaseDirectory(), name);
        Validate.isTrue(!reportFile.exists(), "Report file already exists:", name);

        final String baseName = FilenameUtils.removeExtension(name);
        final File processedReportFile = new File(this.getBaseDirectory(), baseName + REPORT_PROCESSED_EXT);
        Validate.isTrue(!processedReportFile.exists(), "Report file with this name is already processed earlier:", name);

        FileUtils.copyInputStreamToFile(content, reportFile);

        return MAPPER.apply(reportFile);
    }

}
