package com.example.kafkametrics.repository;

import com.example.kafkametrics.kafka.DatabaseException;
import com.example.kafkametrics.util.JsonNodes;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.Instant;

public class IIifMetricsRawRepositoryCustomImpl implements IIifMetricsRawRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void saveFromNode(String messageId, String agreementProductNbr, ObjectNode node) {
        IifMetricsRaw raw = new IifMetricsRaw();
        raw.setMessageId(messageId);
        raw.setAgreementProductNbr(agreementProductNbr);
        raw.setAssetId(JsonNodes.getText(node, "assetId").orElse(null));
        raw.setAssetProductEntCd(JsonNodes.getText(node, "assetProductEntCd").orElse(null));
        raw.setProductFamilyCd(JsonNodes.getText(node, "productFamilyCd").orElse(null));
        raw.setProductSubFamilyCd(JsonNodes.getText(node, "productSubFamilyCd").orElse(null));
        raw.setProcessedDt(Instant.now());
        try {
            em.persist(raw);
        } catch (Exception e) {
            throw new DatabaseException("Failed to persist IifMetricsRaw for messageId=" + messageId, e);
        }
    }
}
