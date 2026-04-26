package com.example.kafkametrics.processor.metrics;

import com.example.kafkametrics.config.AppProperties;
import com.example.kafkametrics.kafka.RequiredFieldException;
import com.example.kafkametrics.repository.IIifMetricsRawRepository;
import com.example.kafkametrics.repository.IPolicyAorRepository;
import com.example.kafkametrics.repository.IPolicyMasterRepository;
import com.example.kafkametrics.repository.PolicyAor;
import com.example.kafkametrics.repository.PolicyMaster;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Enriches IIF metrics payloads with {@link PolicyMaster} and {@link PolicyAor} data keyed by
 * {@code agreementProductNbr}.
 *
 * <p>Results are cached via Caffeine (TTL 15 min, max 10 000 entries per cache) so repeated
 * messages for the same agreement hit memory only. Cache misses are stored as
 * {@code Optional.empty()} to prevent repeated DB queries for unknown keys.
 */
@Service
public class IifMetricsRawProcessorService {

    private static final Logger log = LoggerFactory.getLogger(IifMetricsRawProcessorService.class);

    private final IPolicyMasterRepository policyMasterRepository;
    private final IPolicyAorRepository policyAorRepository;
    private final IIifMetricsRawRepository iifMetricsRawRepository;
    private final long lookupTimeoutMs;

    public IifMetricsRawProcessorService(IPolicyMasterRepository policyMasterRepository,
                                          IPolicyAorRepository policyAorRepository,
                                          IIifMetricsRawRepository iifMetricsRawRepository,
                                          AppProperties appProperties) {
        this.policyMasterRepository = policyMasterRepository;
        this.policyAorRepository = policyAorRepository;
        this.iifMetricsRawRepository = iifMetricsRawRepository;
        this.lookupTimeoutMs = appProperties.getProcessing().getLookupTimeoutMs();
    }

    public void enrich(String messageId, String agreementProductNbr, ObjectNode node) {
        CompletableFuture<Optional<PolicyMaster>> policyFuture =
                CompletableFuture.supplyAsync(() -> lookupPolicy(agreementProductNbr))
                        .orTimeout(lookupTimeoutMs, TimeUnit.MILLISECONDS);
        CompletableFuture<Optional<PolicyAor>> aorFuture =
                CompletableFuture.supplyAsync(() -> lookupAor(agreementProductNbr))
                        .orTimeout(lookupTimeoutMs, TimeUnit.MILLISECONDS);

        PolicyMaster pm = policyFuture.join()
                .orElseThrow(() -> new RequiredFieldException("agreementProductNbr"));
        PolicyAor aor = aorFuture.join()
                .orElseThrow(() -> new RequiredFieldException("agreementProductNbr (AoR)"));

        node.put("originalPolicyEffectiveDate", Optional.ofNullable(pm.getOriginalPolicyEffectiveDate())
                .orElseThrow(() -> new RequiredFieldException("originalPolicyEffectiveDate")).toString());
        node.put("scenarioCd", Optional.ofNullable(pm.getScenarioCd())
                .orElseThrow(() -> new RequiredFieldException("scenarioCd")));
        node.put("agencyNbr", Optional.ofNullable(aor.getAgencyNbr())
                .orElseThrow(() -> new RequiredFieldException("agencyNbr")));
        node.put("assigned", Optional.ofNullable(aor.getAssigned())
                .orElseThrow(() -> new RequiredFieldException("assigned")));

        iifMetricsRawRepository.saveFromNode(messageId, agreementProductNbr, node);
    }

    @Cacheable("policyMaster")
    public Optional<PolicyMaster> lookupPolicy(String agreementProductNbr) {
        List<PolicyMaster> results = policyMasterRepository.findByAgreementProductNumber(agreementProductNbr);
        if (results.isEmpty()) {
            log.warn("No PolicyMaster found for agreementProductNbr={}", agreementProductNbr);
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }

    @Cacheable("policyAor")
    public Optional<PolicyAor> lookupAor(String agreementProductNbr) {
        List<PolicyAor> results = policyAorRepository.findByAgreementProductNumber(agreementProductNbr);
        if (results.isEmpty()) {
            log.warn("No PolicyAor found for agreementProductNbr={}", agreementProductNbr);
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }
}
