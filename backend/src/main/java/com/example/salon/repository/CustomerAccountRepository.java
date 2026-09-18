package com.example.salon.repository;

import com.example.salon.entity.CustomerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, Long> {

    Optional<CustomerAccount> findByCustomerNameAndCustomerPhone(String customerName, String customerPhone);

    List<CustomerAccount> findAllByOrderByUpdatedAtDesc();
}
