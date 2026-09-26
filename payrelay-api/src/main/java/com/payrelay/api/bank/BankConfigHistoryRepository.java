package com.payrelay.api.bank;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankConfigHistoryRepository extends JpaRepository<BankConfigHistory, Long> {

    List<BankConfigHistory> findTop50ByBankCodeOrderByVersionDesc(String bankCode);
}
