package finances.money;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The limits of {@link Freshness} from configuration. The property names are a public interface
 * (AGENTS.md, hard rule 4) and are listed with their defaults in {@code README.md}.
 */
@Configuration(proxyBeanMethods = false)
public class FreshnessConfig {

    @Bean
    Freshness.Limits freshnessLimits(
            @Value("${finances.freshness.bank-sync-hours:" + Freshness.Limits.DEFAULT_BANK_SYNC_HOURS + "}") int bankSyncHours,
            @Value("${finances.freshness.current-days:" + Freshness.Limits.DEFAULT_CURRENT_DAYS + "}") int currentDays,
            @Value("${finances.freshness.savings-days:" + Freshness.Limits.DEFAULT_SAVINGS_DAYS + "}") int savingsDays,
            @Value("${finances.freshness.investment-days:" + Freshness.Limits.DEFAULT_INVESTMENT_DAYS + "}") int investmentDays,
            @Value("${finances.freshness.debt-days:" + Freshness.Limits.DEFAULT_DEBT_DAYS + "}") int debtDays,
            @Value("${finances.freshness.after-tax-hours:" + Freshness.Limits.DEFAULT_AFTER_TAX_HOURS + "}") int afterTaxHours) {
        return new Freshness.Limits(bankSyncHours, currentDays, savingsDays, investmentDays, debtDays, afterTaxHours);
    }
}
