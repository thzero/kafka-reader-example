package com.example.kafkametrics.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IIifMetricsRawRepository extends JpaRepository<IifMetricsRaw, Long> {

    List<IifMetricsRaw> findByAgreementProductNbr(String agreementProductNbr);

    List<IifMetricsRaw> findByAssetId(String assetId);
}
