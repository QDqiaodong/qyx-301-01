package com.example.salon.repository;

import com.example.salon.entity.InvoiceNoSequence;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvoiceNoSequenceRepository extends JpaRepository<InvoiceNoSequence, String> {

    /**
     * 行级写锁取计数器：两人前后脚开票时票号流水串行递增，绝不重号。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM InvoiceNoSequence s WHERE s.scopeKey = :scopeKey")
    Optional<InvoiceNoSequence> findForUpdate(@Param("scopeKey") String scopeKey);
}
