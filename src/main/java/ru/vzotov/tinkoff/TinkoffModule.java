package ru.vzotov.tinkoff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.vzotov.accounting.domain.model.AccountReportRepository;
import ru.vzotov.tinkoff.domain.model.TinkoffOperation;
import ru.vzotov.tinkoff.infrastructure.fs.TinkoffReportRepositoryFiles;

@Configuration
public class TinkoffModule {

    private static final Logger log = LoggerFactory.getLogger(TinkoffModule.class);

    @Bean
    public AccountReportRepository<TinkoffOperation> accountReportRepositoryTinkoff(
            @Value("${tinkoff.reports.path}") String baseDirectoryPath) {
        log.info("Create tinkoff report repository for path {}", baseDirectoryPath);
        return new TinkoffReportRepositoryFiles(baseDirectoryPath);
    }


}
