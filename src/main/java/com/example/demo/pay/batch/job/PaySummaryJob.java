package com.example.demo.pay.batch.job;

import com.example.demo.pay.batch.dto.PaySummaryDto;
import com.example.demo.sales.SalesSummary;
import com.example.demo.sales.SystemType;
import com.example.demo.sales.infra.SalesSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;

import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.BeanPropertyRowMapper;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaySummaryJob {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final DataSource dataSource;
    private final SalesSummaryRepository salesSummaryRepository;

    private static final int INTEGER_MAX = Integer.MAX_VALUE;

    @Bean(name = "pay.summary.job")
    public Job paySummaryJob() throws Exception {
        return jobBuilderFactory.get("pay.summary.job")
                .start(paySummaryStep())
                .incrementer(new RunIdIncrementer())
                .build();
    }

    @Bean
    public Step paySummaryStep() throws Exception {
        return stepBuilderFactory.get("paySummaryStep")
                .<PaySummaryDto, PaySummaryDto>chunk(100)
                .reader(paySummaryCursorItemReader())
                .writer(paySummaryItemWriter())
                .build();
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<PaySummaryDto> paySummaryCursorItemReader() {
        log.info("start paySummaryItemReader");
        Map<String, Object> jobParameters = StepSynchronizationManager.getContext().getJobParameters();
        Object requestDate = jobParameters.get("requestDate");
        Object storeId = jobParameters.get("storeId");

        return new JdbcCursorItemReaderBuilder<PaySummaryDto>()
                .name("paySummaryItemReader")
                .dataSource(dataSource)
                .sql(sql())
                .rowMapper(new BeanPropertyRowMapper<>(PaySummaryDto.class))
                .preparedStatementSetter(new ArgumentPreparedStatementSetter(new Object[]{storeId, requestDate}))
                .build();
    }

    private static String sql() {
        return "SELECT store_id, " +
                "SUM(total_amount) AS daily_total_sales, " +
                "SUM(vat_amount) AS daily_vat_deducted_sales, " +
                "COUNT(total_amount) AS daily_total_transaction " +
                "FROM Pay " +
                "WHERE store_id = ? AND created_date = ? " +
                "GROUP BY store_id";
    }


    @Bean
    @StepScope // stepContext 를 이용하려면 필요함
    public ItemWriter<PaySummaryDto> paySummaryItemWriter() {
        Map<String, Object> jobParameters = StepSynchronizationManager.getContext().getJobParameters();
        String requestDate = (String) jobParameters.get("requestDate");
        String writeType = (String) jobParameters.get("writeType");

        return items -> {
            log.info("=====================start=====================");
            if ("commit".equals(writeType)) {
                createSummary(requestDate, items);
            }
            else {
                doLogSummary(items);
            }
            log.info("===================== end =====================");
        };
    }

    private void doLogSummary(List<? extends PaySummaryDto> items) {
        for (PaySummaryDto item : items) {
            log.info("item {}", item);
        }
    }

    private void createSummary(String requestDate, List<? extends PaySummaryDto> items) {
        List<SalesSummary> salesSummaries = new ArrayList<>();
        for (PaySummaryDto item : items) {
            salesSummaries.add(
                    new SalesSummary(
                            item.getStoreId(),
                            item.getDailyTotalSales(),
                            item.getDailyVatDeductedSales(),
                            item.getDailyTotalTransaction(),
                            LocalDate.parse(requestDate, DateTimeFormatter.ISO_DATE),
                            SystemType.BATCH));
        }
        salesSummaryRepository.saveAll(salesSummaries);
    }

//    @Bean
//    public PagingQueryProvider pagingQueryProvider() throws Exception {
//        SqlPagingQueryProviderFactoryBean queryBean = new SqlPagingQueryProviderFactoryBean();
//        queryBean.setSelectClause("SELECT " +
//                "store_id, " +
//                "SUM(total_amount) AS 'daily_total_sales'," +
//                "SUM(vat_amount) AS 'daily_vat_deducted_sales', " +
//                "COUNT(total_amount) AS 'daily_total_transaction'");
//        queryBean.setFromClause("  FROM Pay ");
//        queryBean.setWhereClause(" WHERE CONVERT(DATE, create_time, 120) = :requestDate");
//        queryBean.setGroupClause(" GROUP BY store_id ");
//        queryBean.setSortKey("store_id");
//        queryBean.setDataSource(dataSource);
//        return queryBean.getObject();
//    }
//
//    @Bean
//    @StepScope
//    public JdbcPagingItemReader paySummaryPagingItemReader() throws Exception {
//        Map<String, Object> jobParameters = StepSynchronizationManager.getContext().getJobParameters();
//        Object requestDate = jobParameters.get("requestDate");
//
//        HashMap<String, Object> parameterValues = new HashMap<>();
//        parameterValues.put("requestDate", requestDate);
//
//
//        return new JdbcPagingItemReaderBuilder()
//                .name("paySummaryItemReader")
//                .dataSource(dataSource)
//                .queryProvider(pagingQueryProvider())
//                .parameterValues(parameterValues)
//                .pageSize(10)
//                .rowMapper(payRowMapper)
//                .build();
//    }
}
