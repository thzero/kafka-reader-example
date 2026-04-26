package com.example.kafkametrics.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Seeds {@link PolicyMaster} and {@link PolicyAor} with fake data on startup.
 * Enabled via {@code app.seed-data.enabled=true} in application.yml.
 */
@Component
@ConditionalOnProperty(name = "app.seed-data.enabled", havingValue = "true")
public class IifDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IifDataSeeder.class);

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String[] SCENARIO_CODES = { "NEW", "RENEWAL", "REWRITE" };

    private static final int POLICY_COUNT = 1000;
    private static final int AOR_COUNT = 250;

    private final IPolicyMasterRepository policyMasterRepository;
    private final IPolicyAorRepository policyAorRepository;
    private final Random random = new Random(42);

    public IifDataSeeder(IPolicyMasterRepository policyMasterRepository,
                         IPolicyAorRepository policyAorRepository) {
        this.policyMasterRepository = policyMasterRepository;
        this.policyAorRepository = policyAorRepository;
    }

    @Override
    public void run(String... args) {
        log.info("[SEED] Seeding {} PolicyMaster and {} PolicyAor records...", POLICY_COUNT, AOR_COUNT);

        List<PolicyMaster> policies = new ArrayList<>(POLICY_COUNT);
        for (int i = 0; i < POLICY_COUNT; i++) {
            PolicyMaster pm = new PolicyMaster();
            pm.setAgreementProductNumber(randomAlphanumeric(15));
            pm.setOriginalPolicyEffectiveDate(randomDate(
                    LocalDate.of(2015, 1, 1), LocalDate.of(2025, 12, 31)));
            pm.setScenarioCd(SCENARIO_CODES[random.nextInt(SCENARIO_CODES.length)]);
            policies.add(pm);
        }
        policyMasterRepository.saveAll(policies);

        // Select AOR_COUNT distinct policies at random and create one AOR entry each
        List<PolicyMaster> shuffled = new ArrayList<>(policies);
        Collections.shuffle(shuffled, random);

        List<PolicyAor> aors = new ArrayList<>(AOR_COUNT);
        for (PolicyMaster pm : shuffled.subList(0, AOR_COUNT)) {
            PolicyAor aor = new PolicyAor();
            aor.setAgreementProductNumber(pm.getAgreementProductNumber());
            aor.setAgencyNbr("0" + randomAlphanumeric(5));
            aor.setAssigned(random.nextBoolean());
            aors.add(aor);
        }
        policyAorRepository.saveAll(aors);

        log.info("[SEED] Done — {} policies, {} AOR records seeded", POLICY_COUNT, AOR_COUNT);
    }

    private String randomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    private LocalDate randomDate(LocalDate from, LocalDate to) {
        long fromDay = from.toEpochDay();
        long toDay = to.toEpochDay();
        return LocalDate.ofEpochDay(fromDay + random.nextLong(toDay - fromDay));
    }
}
