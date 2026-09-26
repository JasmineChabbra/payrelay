package com.payrelay.api.bank;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankConfigRepository extends JpaRepository<BankConfig, String> {

    List<BankConfig> findAllByOrderByBankCodeAsc();
}
