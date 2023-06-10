package ru.vzotov.tinkoff.infrastructure.fs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vzotov.accounting.domain.model.AccountReport;
import ru.vzotov.accounting.domain.model.AccountReportId;
import ru.vzotov.accounting.domain.model.AccountReportRepository;
import ru.vzotov.banking.domain.model.AccountNumber;
import ru.vzotov.tinkoff.domain.model.TinkoffOperation;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MILLI_OF_SECOND;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static java.time.temporal.ChronoField.YEAR;
import static java.util.Objects.requireNonNull;
import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

public class TinkoffReportRepositoryFiles implements AccountReportRepository<TinkoffOperation> {

    private static final Logger log = LoggerFactory.getLogger(AccountReportRepository.class);

    private static final String CSV = ".csv";
    private static final String OFX = ".ofx";
    private static final String CSV_PROCESSED = "_processed.csv";
    private static final String OFX_PROCESSED = "_processed.ofx";

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

    public static final ZoneId TINKOFF_TZ = ZoneId.of("Europe/Moscow");

    private static final Function<File, AccountReportId> ID_OF = file -> {
        try {
            BasicFileAttributes basicFileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);

            return new AccountReportId(file.getName(), basicFileAttributes.creationTime().toInstant());
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    };

    private final String baseDirectoryPath;

    private final File baseDirectory;

    private final boolean readOnly;

    public TinkoffReportRepositoryFiles(String baseDirectoryPath) {
        this(baseDirectoryPath, false);
    }

    public TinkoffReportRepositoryFiles(String baseDirectoryPath, boolean readOnly) {
        this.baseDirectoryPath = baseDirectoryPath;
        this.baseDirectory = new File(baseDirectoryPath);
        this.readOnly = readOnly;
        log.info("Checking base directory permissions {}...", this.baseDirectory.getAbsolutePath());
        Validate.isTrue(this.baseDirectory.isDirectory());
        Validate.isTrue(this.baseDirectory.canRead());
        log.info("The permissions for the base directory are valid");
    }

    protected String getBaseDirectoryPath() {
        return baseDirectoryPath;
    }

    protected File getBaseDirectory() {
        return baseDirectory;
    }

    @Override
    public AccountReport<TinkoffOperation> find(final AccountReportId reportId) {
        Validate.notNull(reportId);
        final File reportFile = new File(this.getBaseDirectory(), reportId.name());
        Validate.isTrue(reportFile.exists() && reportFile.canRead());

        final String name = reportId.name().toLowerCase();
        if (name.endsWith(CSV)) {
            return parseCSV(reportId, reportFile);
        } else if (name.endsWith(OFX)) {
            return parseOFX(reportId, reportFile);
        }
        throw new IllegalArgumentException();
    }

    AccountReport<TinkoffOperation> parseOFX(final AccountReportId reportId, final File reportFile) {
        final XMLInputFactory f = XMLInputFactory.newFactory();
        try (final FileInputStream stream = new FileInputStream(reportFile)) {
            final XMLStreamReader sr = f.createXMLStreamReader(stream);
            final XmlMapper mapper = new XmlMapper();
            final List<TinkoffOperation> operations = new ArrayList<>();
            String tagName;
            AccountNumber currentAccount = null;
            while (sr.hasNext()) {
                int eventType = sr.next();
                switch (eventType) {
                    case START_ELEMENT -> {
                        tagName = sr.getName().getLocalPart();
                        if ("BANKACCTFROM".equalsIgnoreCase(tagName)) {
                            final OfxBankAccount account = mapper.readValue(sr, OfxBankAccount.class);
                            currentAccount = new AccountNumber(account.accountId);
                        } else if ("STMTRS".equalsIgnoreCase(tagName)) {
                            currentAccount = null;
                        } else if ("STMTTRN".equalsIgnoreCase(tagName)) {
                            final Statement stmt = mapper.readValue(sr, Statement.class);

                            final LocalDateTime operationDateTime = stmt.dateTime.toInstant()
                                    .atZone(TINKOFF_TZ).toLocalDateTime();
                            final TinkoffOperation operation = new TinkoffOperation(
                                    currentAccount,
                                    operationDateTime,
                                    operationDateTime.toLocalDate(),
                                    null,
                                    stmt.amount().doubleValue(),
                                    mapCurrency(stmt.currency().code()),
                                    stmt.amount().doubleValue(),
                                    mapCurrency(stmt.currency().code()),
                                    null,
                                    stmt.memo(),
                                    null,
                                    stmt.fitId() + " " + stmt.name(),
                                    null
                            );
                            operations.add(operation);
                        }
                    }
                    case END_ELEMENT -> {
                        tagName = sr.getName().getLocalPart();
                        if ("STMTRS".equalsIgnoreCase(tagName)) {
                            currentAccount = null;
                        }
                    }
                }
            }

            return new AccountReport<>(reportId, operations);
        } catch (XMLStreamException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static LocalDateTime tzConvert(LocalDateTime dateTime, ZoneId from, ZoneId to) {
        return dateTime == null ? null : dateTime.atZone(from).toInstant().atZone(to).toLocalDateTime();
    }

    static LocalDate max(LocalDate date1, LocalDate date2) {
        return date1 == null ? date2 :
                date2 == null ? date1 :
                        date1.isAfter(date2) ? date1 : date2;
    }

    AccountReport<TinkoffOperation> parseCSV(final AccountReportId reportId, final File reportFile) {
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
                final LocalDate paymentDate = max(parseDateOrNull(record.get("Дата платежа")), operationDateTime.toLocalDate());
                final String cardNumber = StringUtils.trimToNull(record.get("Номер карты"));
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
                        null,
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
        final FileFilter filter = pathname -> {
            final String name = pathname.getName().toLowerCase();
            return name.endsWith(CSV) || name.endsWith(OFX);
        };

        return Arrays.stream(requireNonNull(this.getBaseDirectory().listFiles(filter)))
                .sorted(Comparator.naturalOrder())
                .map(ID_OF)
                .collect(Collectors.toList());

    }

    @Override
    public List<AccountReportId> findUnprocessed() {
        final FileFilter filter = pathname -> {
            final String name = pathname.getName().toLowerCase();
            return (name.endsWith(CSV) && !name.endsWith(CSV_PROCESSED)) ||
                    (name.endsWith(OFX) && !name.endsWith(OFX_PROCESSED));
        };

        return Arrays.stream(requireNonNull(this.getBaseDirectory().listFiles(filter)))
                .sorted(Comparator.naturalOrder())
                .map(ID_OF)
                .collect(Collectors.toList());
    }

    @Override
    public void markProcessed(AccountReportId reportId) {
        Validate.notNull(reportId);

        final File reportFile = new File(this.getBaseDirectory(), reportId.name());

        final boolean isCsv = reportId.name().toLowerCase().endsWith(CSV);
        final String baseName = FilenameUtils.removeExtension(reportId.name());

        final File processedReportFile = new File(
                this.getBaseDirectory(),
                baseName + (isCsv ? CSV_PROCESSED : OFX_PROCESSED)
        );

        Validate.isTrue(reportFile.exists() && reportFile.canRead() && reportFile.canWrite());
        Validate.isTrue(!processedReportFile.exists());

        try {
            if (!readOnly) {
                FileUtils.moveFile(reportFile, processedReportFile);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to mark processed", e);
        }
    }

    @Override
    public AccountReportId save(String name, InputStream content) throws IOException {
        Validate.notEmpty(name);
        Validate.notNull(content);
        Validate.isTrue(name.endsWith(CSV) || name.endsWith(OFX), "Invalid name of report: ", name);
        Validate.isTrue(!(name.endsWith(CSV_PROCESSED) || name.endsWith(OFX_PROCESSED)),
                "Saving already processed reports is not allowed:", name);

        final File reportFile = new File(this.getBaseDirectory(), name);
        Validate.isTrue(!reportFile.exists(), "Report file already exists:", name);

        final String baseName = FilenameUtils.removeExtension(name);
        final File processedCSVFile = new File(this.getBaseDirectory(), baseName + CSV_PROCESSED);
        Validate.isTrue(!processedCSVFile.exists(), "Report file with this name is already processed earlier:", name);

        final File processedOFXFile = new File(this.getBaseDirectory(), baseName + OFX_PROCESSED);
        Validate.isTrue(!processedOFXFile.exists(), "Report file with this name is already processed earlier:", name);

        FileUtils.copyInputStreamToFile(content, reportFile);

        return ID_OF.apply(reportFile);
    }

    private record OfxBankAccount(@JsonProperty("BANKID") String bankId, @JsonProperty("ACCTID") String accountId,
                                  @JsonProperty("ACCTTYPE") String accountType) {
    }

    private record Statement(
            @JsonProperty("TRNTYPE") String type,
            @JsonProperty("DTPOSTED") @JsonDeserialize(using = OfxDateSerializer.class) OffsetDateTime dateTime,
            @JsonProperty("TRNAMT") BigDecimal amount, @JsonProperty("FITID") String fitId,
            @JsonProperty("NAME") String name, @JsonProperty("MEMO") String memo,
            @JsonProperty("CURRENCY") OfxCurrency currency) {
    }

    private record OfxCurrency(
            @JsonProperty("CURSYM") String code,
            @JsonProperty("CURRATE") BigDecimal rate) {
    }

    private static class OfxDateSerializer extends StdDeserializer<OffsetDateTime> {

        private static final DateTimeFormatter OFX_DATETIME = new DateTimeFormatterBuilder()
                .appendValue(YEAR, 4)
                .appendValue(MONTH_OF_YEAR, 2)
                .appendValue(DAY_OF_MONTH, 2)
                .appendValue(HOUR_OF_DAY, 2)
                .appendValue(MINUTE_OF_HOUR, 2)
                .appendValue(SECOND_OF_MINUTE, 2)
                .optionalStart()
                .appendLiteral('.')
                .appendValue(MILLI_OF_SECOND, 3)
                .optionalEnd()
                .toFormatter();

        private static final Pattern PATTERN = Pattern.compile("(\\d{14}(\\.\\d{3})?)\\[([+-]\\d{1,2})(\\d{0,2}):(\\w+)]");

        public OfxDateSerializer() {
            this(null);
        }

        public OfxDateSerializer(Class<?> vc) {
            super(vc);
        }

        @Override
        public OffsetDateTime deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
            String value = jsonParser.getText();
            final Matcher matcher = PATTERN.matcher(value);
            Validate.isTrue(matcher.matches());
            LocalDateTime time = LocalDateTime.parse(matcher.group(1), OFX_DATETIME);

            final String offsetMinutes = matcher.group(4);
            return OffsetDateTime.of(time, ZoneOffset.ofHoursMinutes(
                    Integer.parseInt(matcher.group(3)),
                    offsetMinutes == null || offsetMinutes.length() == 0 ? 0 : Integer.parseInt(offsetMinutes)
            ));
        }
    }
}
